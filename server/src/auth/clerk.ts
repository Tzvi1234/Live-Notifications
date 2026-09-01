/**
 * Clerk session verification, and the `users` row every authenticated route joins against.
 *
 * ONE verification path, not two. `@clerk/express` offers `clerkMiddleware()` + `getAuth()`
 * as well as `clerkClient.authenticateRequest()`; this file uses the latter exclusively.
 * The middleware form reads its keys out of `process.env` on its own, which would put a
 * second source of configuration next to src/config.ts and make an unconfigured instance
 * fail somewhere inside the SDK instead of answering the 503 below. Passing the keys
 * explicitly also means CLERK_JWT_KEY actually reaches the verifier, which is what makes
 * verification networkless: with it, a session JWT is checked against a local public key and
 * costs no round trip to Clerk on the request path.
 *
 * All three variables are optional. An instance configured only for live notifications must
 * still boot, so a missing CLERK_SECRET_KEY is a warning at startup and a 503 on the
 * authenticated routes — never a crash, and never an open door.
 */

import { createClerkClient, type ClerkClient } from '@clerk/express';
import type { NextFunction, Request, RequestHandler, Response } from 'express';

import type { KickoffConfig } from '../config.js';
import type { Logger } from '../logger.js';
import type { Store, UserProfileSeed } from '../store/index.js';
import type { UserRecord } from '../types.js';
import { serviceUnavailable, unauthorized } from '../routes/validation.js';

const CLERK_UNCONFIGURED =
  'Accounts are not configured on this server. Set CLERK_SECRET_KEY (and ideally ' +
  'CLERK_PUBLISHABLE_KEY and CLERK_JWT_KEY) to enable sign-in, groups and predictions.';

/**
 * The display name and avatar a Clerk session token can carry. They are only there when the
 * instance's JWT template adds them — the default session token carries neither — so every
 * one of these is read defensively and any of them may be absent.
 */
const NAME_CLAIMS = ['name', 'full_name', 'fullName', 'username'] as const;
const AVATAR_CLAIMS = ['picture', 'image_url', 'imageUrl', 'avatar_url'] as const;

const MAX_CLAIM_LENGTH = 256;

export interface ClerkAuth {
  /** Whether the authenticated routes can answer at all; false => they all 503. */
  readonly enabled: boolean;
  readonly requireUser: RequestHandler;
}

export interface ClerkAuthOptions {
  readonly config: KickoffConfig;
  readonly store: Store;
  readonly logger: Logger;
}

/**
 * The caller of the request in flight. Throws rather than returning undefined: every route
 * that calls it is mounted behind `requireUser`, so an absent user is a mounting bug and
 * has to be loud rather than becoming an anonymous read.
 */
export function currentUser(res: Response): UserRecord {
  const user = res.locals.user as UserRecord | undefined;
  if (user === undefined) {
    throw new Error('[auth] currentUser() called on a route that is not behind requireUser.');
  }
  return user;
}

export function createClerkAuth(options: ClerkAuthOptions): ClerkAuth {
  const { config, store } = options;
  const logger = options.logger.child({ component: 'auth.clerk' });

  const secretKey = config.clerkSecretKey;
  if (secretKey === undefined) {
    logger.warn(
      'CLERK_SECRET_KEY is unset: /v1/me and /v1/groups/* will answer 503. The catalogue, ' +
        'fixture, device and subscription routes are unaffected.',
    );
    return { enabled: false, requireUser: unavailableHandler() };
  }

  if (config.clerkJwtKey === undefined) {
    logger.warn(
      'CLERK_JWT_KEY is unset: every request will verify its session token against Clerk ' +
        'over the network instead of against a local public key. Copy the JWKS public key ' +
        'from the Clerk dashboard (API keys -> Show JWT public key -> PEM) to avoid that.',
    );
  }

  // Built once. The client caches the JWKS it fetches, so re-creating it per request would
  // turn every call into a network round trip even when CLERK_JWT_KEY is set.
  const clerk: ClerkClient = createClerkClient({
    secretKey,
    ...(config.clerkPublishableKey !== undefined
      ? { publishableKey: config.clerkPublishableKey }
      : {}),
    ...(config.clerkJwtKey !== undefined ? { jwtKey: config.clerkJwtKey } : {}),
  });

  const requireUser: RequestHandler = (req: Request, res: Response, next: NextFunction) => {
    void authenticate(req, res, next);
  };

  async function authenticate(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const state = await clerk.authenticateRequest(toWebRequest(req), {
        // Repeated here as well as on the client: `authenticateRequest` re-reads its options
        // per call and falls back to the environment for anything absent, and the
        // environment is deliberately not where this service's configuration lives.
        ...(config.clerkJwtKey !== undefined ? { jwtKey: config.clerkJwtKey } : {}),
        ...(config.clerkPublishableKey !== undefined
          ? { publishableKey: config.clerkPublishableKey }
          : {}),
      });

      // Null for a handshake state, which a browser gets and a bearer-token client never
      // does; either way it is an unauthenticated request.
      const auth = state.toAuth();
      const userId = auth?.userId;
      if (auth === null || typeof userId !== 'string' || userId.length === 0) {
        logger.debug('session token rejected', { reason: state.reason, path: req.path });
        // The reason is Clerk's own vocabulary ("token-expired", "token-invalid") and the
        // app needs it to decide between refreshing the token and sending the user back to
        // the sign-in screen. It says nothing about the account, so it is safe to echo.
        next(
          unauthorized(
            'A valid Clerk session token is required in "Authorization: Bearer <jwt>"' +
              (state.reason ? ` (${state.reason}).` : '.'),
          ),
        );
        return;
      }

      // One statement, and it doubles as the "seen at" touch. The seed only fills blanks;
      // see `upsertUser` for why a claim must never overwrite what PATCH /v1/me set.
      res.locals.user = await store.upsertUser(userId, profileFromClaims(auth.sessionClaims));
      next();
    } catch (error) {
      // A Clerk outage or a malformed key is a 503, not a 401: telling the app its token is
      // bad would make it sign the user out over a problem on this side.
      logger.error('session verification failed', { path: req.path, error });
      next(serviceUnavailable('Could not verify the session; try again shortly.'));
    }
  }

  return { enabled: true, requireUser };
}

/** Every authenticated route, answering the same way, when Clerk is not configured. */
function unavailableHandler(): RequestHandler {
  return (_req: Request, _res: Response, next: NextFunction) => {
    next(serviceUnavailable(CLERK_UNCONFIGURED));
  };
}

/**
 * `authenticateRequest` takes a WHATWG Request, not an Express one. Only the headers and the
 * URL matter here — the body is never read, and the app authenticates with a bearer header
 * rather than the cookies a browser would send.
 */
function toWebRequest(req: Request): Request_ {
  const headers = new Headers();
  for (const [name, value] of Object.entries(req.headers)) {
    if (Array.isArray(value)) for (const entry of value) headers.append(name, entry);
    else if (typeof value === 'string') headers.set(name, value);
  }
  // `req.protocol` honours X-Forwarded-Proto because app.ts sets `trust proxy`; without a
  // Host header there is nothing to build an absolute URL from, and the URL is only used
  // by Clerk's multi-domain handling, which this service does not use.
  const host = req.get('host') ?? 'localhost';
  return new Request(`${req.protocol}://${host}${req.originalUrl}`, {
    method: req.method,
    headers,
  });
}

/** Express's `Request` shadows the global one inside this module. */
type Request_ = globalThis.Request;

function profileFromClaims(claims: unknown): UserProfileSeed | undefined {
  if (typeof claims !== 'object' || claims === null) return undefined;
  const record = claims as Record<string, unknown>;

  const seed: UserProfileSeed = {};
  const displayName = firstClaim(record, NAME_CLAIMS);
  const avatarUrl = firstClaim(record, AVATAR_CLAIMS);
  // Built key by key: `UserProfileSeed` declares bare optional properties, which reject an
  // explicit undefined under exactOptionalPropertyTypes.
  if (displayName !== undefined) seed.displayName = displayName;
  if (avatarUrl !== undefined) seed.avatarUrl = avatarUrl;
  return displayName === undefined && avatarUrl === undefined ? undefined : seed;
}

function firstClaim(claims: Record<string, unknown>, names: readonly string[]): string | undefined {
  for (const name of names) {
    const value = claims[name];
    if (typeof value !== 'string') continue;
    const trimmed = value.trim();
    // Capped because it lands in a column and in log lines, and a custom JWT template can
    // put anything at all in a claim.
    if (trimmed.length > 0) return trimmed.slice(0, MAX_CLAIM_LENGTH);
  }
  return undefined;
}
