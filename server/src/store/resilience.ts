/**
 * Keeping the service alive when the database is not.
 *
 * Render deletes a free Postgres instance after thirty days, and the service that was
 * pointed at it does not find out politely: `DATABASE_URL` still parses, the hostname
 * simply stops resolving. Every store call then throws `ENOTFOUND`, every route that
 * touches one returns 500, and the poller's leader lock fails on every tick — forever,
 * because nothing in the process ever reconsiders the choice it made at boot.
 *
 * A football app that can still serve fixtures is worth far more than one that is
 * entirely down, so a database that is *gone* is treated as a database that was never
 * configured. A database that is *there and refusing us* is a different thing and stays
 * loud: falling back silently would hide a wrong password behind a working-looking
 * service, and the operator would never learn why nothing persists.
 */

import type { Logger } from '../logger.js';
import type { Store } from './index.js';

/** What a failed connection attempt means for the choice of store. */
export type ConnectionFault =
  /** Nothing is listening, or it is not there any more. Serve on without it. */
  | 'unreachable'
  /** Something answered and said no. An operator has to fix this; do not paper over it. */
  | 'rejected'
  /** Not a connection problem at all — a bad migration, a syntax error, a bug. */
  | 'other';

/** Node's socket-level failures, plus the Postgres codes that mean the same thing. */
const UNREACHABLE_SYSCALL_CODES = new Set([
  'ENOTFOUND',
  'ECONNREFUSED',
  'ETIMEDOUT',
  'EHOSTUNREACH',
  'ENETUNREACH',
  'ECONNRESET',
  'EAI_AGAIN',
]);

/**
 * `3D000` is "database does not exist", which sounds like a configuration error and is
 * usually the same event as ENOTFOUND: the instance was deleted and something else now
 * answers on its address.
 */
const UNREACHABLE_PG_CODES = new Set(['3D000', '57P03', '08001', '08006', '08004']);

/** Credentials and permissions: reachable, and deliberately refusing us. */
const REJECTED_PG_CODES = new Set(['28P01', '28000', '42501']);

export function classifyConnectionFault(error: unknown): ConnectionFault {
  const code = codeOf(error);
  if (code !== undefined) {
    if (UNREACHABLE_SYSCALL_CODES.has(code) || UNREACHABLE_PG_CODES.has(code)) {
      return 'unreachable';
    }
    if (REJECTED_PG_CODES.has(code)) return 'rejected';
  }
  // TLS and host-based refusals arrive as a message rather than as a code worth
  // enumerating, and `pg` does not always attach one - so this is checked whether or not
  // there was a code, not only as a fallback when there was none.
  const message = error instanceof Error ? error.message.toLowerCase() : '';
  if (message.includes('does not support ssl') || message.includes('no pg_hba.conf entry')) {
    return 'rejected';
  }
  return 'other';
}

function codeOf(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null) return undefined;
  const code = (error as { code?: unknown }).code;
  return typeof code === 'string' ? code : undefined;
}

/**
 * How many connection failures in a row before the database is written off.
 *
 * More than one because a single reset during a Render maintenance window is normal and
 * recovers on its own; small because every attempt in between costs a caller its request.
 */
const FAILURES_BEFORE_FALLBACK = 3;

/**
 * Wraps a store so that a database which dies *while running* is survivable.
 *
 * Built with a Proxy rather than forty delegating methods: the `Store` interface grows
 * every time the app does, and a hand-written decorator is a list that silently stops
 * being complete. The single cast below is the price of that, and it is checked by the
 * `Store` return type on both sides.
 */
export function withDatabaseFallback(
  primary: Store,
  makeFallback: () => Store,
  logger: Logger,
): Store {
  let active: Store = primary;
  let consecutiveFaults = 0;

  const failOver = (error: unknown): void => {
    if (active !== primary) return;
    active = makeFallback();
    logger.error(
      'store: giving up on postgres and continuing in memory — it has failed to connect ' +
        `${FAILURES_BEFORE_FALLBACK} times running. Fixtures and the catalogue keep ` +
        'working; anything already persisted is out of reach, and because the dedupe ' +
        'gate starts empty again, events already pushed can look new and be pushed ' +
        'twice. Point DATABASE_URL at a live database and restart.',
      { error, kind: active.kind },
    );
  };

  const handler: ProxyHandler<Store> = {
    get(_target, property) {
      if (property === 'kind') return active.kind;

      const value = Reflect.get(active, property, active);
      if (typeof value !== 'function') return value;

      return (...args: unknown[]): unknown => {
        // Re-read `active` per call: it may have flipped since this method was looked up.
        const current = active;
        const bound = Reflect.get(current, property, current) as (
          ...callArgs: unknown[]
        ) => unknown;
        let outcome: unknown;
        try {
          outcome = bound.apply(current, args);
        } catch (error) {
          return onFailure(error, property, args);
        }
        if (!(outcome instanceof Promise)) {
          consecutiveFaults = 0;
          return outcome;
        }
        return outcome.then(
          (resolved) => {
            if (current === active) consecutiveFaults = 0;
            return resolved;
          },
          (error: unknown) => onFailure(error, property, args),
        );
      };
    },
  };

  function onFailure(
    error: unknown,
    property: string | symbol,
    args: unknown[],
  ): Promise<unknown> {
    if (active !== primary || classifyConnectionFault(error) !== 'unreachable') {
      consecutiveFaults = 0;
      return Promise.reject(error);
    }
    consecutiveFaults += 1;
    if (consecutiveFaults < FAILURES_BEFORE_FALLBACK) return Promise.reject(error);

    failOver(error);
    // Retry once against the fallback so the request that paid for the discovery still
    // gets an answer, rather than being the one call that fails for no visible reason.
    const retry = Reflect.get(active, property, active) as (...a: unknown[]) => unknown;
    if (typeof retry !== 'function') return Promise.reject(error);
    return Promise.resolve(retry.apply(active, args)).catch(() => {
      // The memory store failing is not a connectivity problem; surface the original.
      throw error;
    });
  }

  return new Proxy(primary, handler);
}

export const CONNECTION_FAULT_TEST_HOOKS = {
  FAILURES_BEFORE_FALLBACK,
};
