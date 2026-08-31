/**
 * Persistence behind one interface, with two interchangeable implementations.
 *
 * Everything the poller needs to be *correct across restarts* lives here: which events
 * have already been pushed (the dedupe gate), which device wants which match, how far a
 * match's detail payload has advanced, and which process is allowed to poll at all.
 */

import {
  DEFAULT_SUBSCRIPTION_PREFERENCES,
  type DeviceRecord,
  type SubscriptionPreferences,
  type SubscriptionRecord,
  type TrackedMatchState,
} from '../types.js';
import type { Logger } from '../logger.js';

export type StoreKind = 'postgres' | 'memory';

/** What `POST /v1/devices` carries; `deviceId` is minted by the store, never by the client. */
export interface DeviceRegistration {
  token: string;
  platform?: string | undefined;
  appVersion?: string | undefined;
  timeZone?: string | undefined;
  locale?: string | undefined;
}

/**
 * The subset of a match the subscription query needs. `MatchJson` satisfies it
 * structurally, so callers can pass a whole match without a mapping step.
 */
export interface MatchTargets {
  id: number;
  leagueId: number;
  home: { id: number };
  away: { id: number };
}

export interface PushTarget {
  token: string;
  preferences: SubscriptionPreferences;
}

export interface PruneStats {
  devices: number;
  subscriptions: number;
  sentEvents: number;
  matchStates: number;
}

export interface Store {
  readonly kind: StoreKind;

  upsertDevice(device: DeviceRegistration): Promise<DeviceRecord>;
  deleteDevice(token: string): Promise<boolean>;
  /** Bumps `lastSeenAt` so `pruneOlderThan` can retire tokens nobody is using. */
  touchDevice(token: string): Promise<void>;

  putSubscription(subscription: SubscriptionRecord): Promise<void>;
  getSubscription(token: string): Promise<SubscriptionRecord | undefined>;
  /** Every device subscribed to either team, the league, or this fixture explicitly. */
  tokensForMatch(match: MatchTargets): Promise<PushTarget[]>;

  /**
   * The idempotency gate. Returns true **only for the call that inserted the row**, so
   * two pollers racing on the same event produce exactly one push. Never returns true
   * twice for one id, which is what stops a provider re-report re-notifying.
   */
  markEventSent(eventId: string, matchId?: number): Promise<boolean>;

  getMatchState(matchId: number): Promise<TrackedMatchState | undefined>;
  /** `sentEventIds` on the argument is ignored: `markEventSent` is that set's only writer. */
  putMatchState(state: TrackedMatchState): Promise<void>;
  /** Monotonic per match; the client drops any detail payload with a lower sequence. */
  nextSequence(matchId: number): Promise<number>;

  /**
   * Retention sweep: drops sent_events and match_state older than `date`, and devices whose
   * `lastSeenAt` predates it (their subscriptions go with them). Keep the cutoff generous —
   * a device is only touched when the app talks to the API, so a short cutoff silently
   * unsubscribes users whose phone was pushing notifications but never opened.
   * Deleting a sent_events row re-arms a push for that event id; never prune inside a
   * window where a match could still be in play.
   */
  pruneOlderThan(date: Date): Promise<PruneStats>;
  /** Drops tokens FCM reported as `registration-token-not-registered`. */
  removeTokens(tokens: string[]): Promise<number>;

  /**
   * Only the holder may poll and push. Render overlaps the old and new instance during a
   * deploy, and two pollers on one dataset means two notifications per goal for anyone
   * whose event lands in the window before the loser's `markEventSent` fails.
   */
  acquireLeaderLock(ttlMs: number): Promise<boolean>;
  releaseLeaderLock(): Promise<void>;

  close(): Promise<void>;
}

export interface StoreConfig {
  readonly databaseUrl?: string | undefined;
}

/**
 * Bounds one subscription row: a client sending a million ids would otherwise write a
 * row large enough to slow every `tokensForMatch` scan behind it.
 */
export const MAX_IDS_PER_LIST = 500;

/** Positive integers only, de-duplicated, order preserved, length capped. */
export function normalizeIds(values: readonly unknown[] | undefined): number[] {
  if (!values) return [];
  const seen = new Set<number>();
  for (const value of values) {
    const id = typeof value === 'string' ? Number(value) : value;
    if (typeof id !== 'number' || !Number.isSafeInteger(id) || id <= 0) continue;
    seen.add(id);
    if (seen.size >= MAX_IDS_PER_LIST) break;
  }
  return [...seen];
}

function bool(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

/**
 * Preferences arrive from clients and from JSONB columns written by older builds, so a
 * missing or junk key falls back to the shipped default instead of rejecting the row.
 */
export function normalizePreferences(value: unknown): SubscriptionPreferences {
  const input = (value ?? {}) as Partial<Record<keyof SubscriptionPreferences, unknown>>;
  const lead = Number(input.preMatchLeadMinutes);
  return {
    goals: bool(input.goals, DEFAULT_SUBSCRIPTION_PREFERENCES.goals),
    cards: bool(input.cards, DEFAULT_SUBSCRIPTION_PREFERENCES.cards),
    substitutions: bool(input.substitutions, DEFAULT_SUBSCRIPTION_PREFERENCES.substitutions),
    kickoffAndFullTime: bool(
      input.kickoffAndFullTime,
      DEFAULT_SUBSCRIPTION_PREFERENCES.kickoffAndFullTime,
    ),
    lineups: bool(input.lineups, DEFAULT_SUBSCRIPTION_PREFERENCES.lineups),
    preMatchLeadMinutes: Number.isFinite(lead)
      ? Math.min(1440, Math.max(0, Math.trunc(lead)))
      : DEFAULT_SUBSCRIPTION_PREFERENCES.preMatchLeadMinutes,
  };
}

export function normalizeSubscription(subscription: SubscriptionRecord): SubscriptionRecord {
  return {
    token: subscription.token,
    teamIds: normalizeIds(subscription.teamIds),
    leagueIds: normalizeIds(subscription.leagueIds),
    matchIds: normalizeIds(subscription.matchIds),
    preferences: normalizePreferences(subscription.preferences),
  };
}

/**
 * `sent_events.match_id` is derived from the id when the caller does not pass one:
 * `eventId()` in types.ts always prefixes the match id, and `getMatchState` hydrates the
 * dedupe set with `WHERE match_id = ...`, so a wrong prefix would silently replay every
 * event of that match after a restart. Loud failure beats that.
 */
export function matchIdFromEventId(eventId: string): number {
  const separator = eventId.indexOf(':');
  const parsed = separator > 0 ? Number(eventId.slice(0, separator)) : Number.NaN;
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(
      `[store] cannot derive a match id from event id "${eventId}"; ` +
        'pass matchId explicitly or build the id with eventId().',
    );
  }
  return parsed;
}

/**
 * Postgres when DATABASE_URL is set, in-memory otherwise. Dynamic import so a deployment
 * without a database never has to have `pg` installed or resolvable.
 */
export async function createStore(config: StoreConfig, logger: Logger): Promise<Store> {
  const databaseUrl = config.databaseUrl;
  if (databaseUrl) {
    const { createPostgresStore } = await import('./postgres.js');
    const store = await createPostgresStore(databaseUrl, logger);
    logger.info('store: postgres', { kind: store.kind });
    return store;
  }

  const { createMemoryStore } = await import('./memory.js');
  const store = createMemoryStore(logger);
  logger.warn(
    'store: in-memory (DATABASE_URL unset) — dedupe state, devices and subscriptions are ' +
      'lost on every restart and Render restarts on each deploy and on idle spin-down. ' +
      'After a restart every already-notified event looks new, so subscribers are ' +
      're-notified for goals they were told about before. Set DATABASE_URL in production.',
    { kind: store.kind },
  );
  return store;
}
