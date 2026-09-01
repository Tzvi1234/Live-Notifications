/**
 * Persistence behind one interface, with two interchangeable implementations.
 *
 * Everything the poller needs to be *correct across restarts* lives here: which events
 * have already been pushed (the dedupe gate), which device wants which match, how far a
 * match's detail payload has advanced, and which process is allowed to poll at all.
 */

import type { CacheQueryable } from '../provider/persistentCache.js';
import {
  DEFAULT_SUBSCRIPTION_PREFERENCES,
  type DeviceRecord,
  type GroupMemberRecord,
  type GroupMessageRecord,
  type GroupRecord,
  type LeaderboardRow,
  type PredictionRecord,
  type ScoreJson,
  type SubscriptionPreferences,
  type SubscriptionRecord,
  type TrackedMatchState,
  type UserRecord,
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

  /**
   * A raw query handle, present only on a real database.
   *
   * Exposed for the provider's response cache and nothing else. That cache is not domain
   * data - it is a scratch table whose rows are all disposable - so giving it its own
   * typed Store methods would put six cache operations into an interface about devices,
   * subscriptions and predictions. Absent on the memory store, which is exactly right:
   * a cache with nowhere to persist to is a cache that always misses.
   */
  readonly cacheQueryable?: CacheQueryable | undefined;

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
   * Only the holder may poll. `markEventSent` is what keeps a goal to one notification even
   * when two instances race; this lock is what stops the second one spending the day's
   * provider budget a second time and fighting over `match_state` while Render runs the old
   * and the new instance side by side through a deploy.
   *
   * `ttlMs` is a client-side lease on the answer, not a server-side expiry: the lock lives
   * on a connection and dies with it. Keep it a small multiple of the poll interval.
   */
  acquireLeaderLock(ttlMs: number): Promise<boolean>;
  releaseLeaderLock(): Promise<void>;

  /* -- Accounts ---------------------------------------------------------- */

  /**
   * Called by `requireUser` on every authenticated request, so it is one statement and it
   * also serves as the "seen at" touch. `profile` only ever *fills in* a missing display
   * name or avatar: the values a session token carries are Clerk's, and overwriting on
   * every request would undo `PATCH /v1/me` on the user's very next call.
   */
  upsertUser(clerkUserId: string, profile?: UserProfileSeed | undefined): Promise<UserRecord>;
  getUser(clerkUserId: string): Promise<UserRecord | undefined>;
  /** Only the keys present are written; returns undefined for an unknown user. */
  updateUserProfile(clerkUserId: string, patch: UserProfilePatch): Promise<UserRecord | undefined>;

  /* -- Groups ------------------------------------------------------------ */

  createGroup(input: CreateGroupInput): Promise<GroupRecord>;
  getGroup(groupId: number): Promise<GroupRecord | undefined>;
  getGroupByInviteCode(inviteCode: string): Promise<GroupRecord | undefined>;
  listGroupsForUser(userId: string): Promise<GroupRecord[]>;
  /** Owner-only at the route; returns undefined for an unknown group. */
  updateGroup(groupId: number, patch: UpdateGroupPatch): Promise<GroupRecord | undefined>;
  deleteGroup(groupId: number): Promise<boolean>;
  listGroupMembers(groupId: number): Promise<GroupMemberRecord[]>;
  /**
   * The gate every group route runs first. It is a membership test rather than a fetch
   * because a non-member must not be able to tell an existing group from an absent one.
   */
  isGroupMember(groupId: number, userId: string): Promise<boolean>;
  /** False when the user was already a member, so joining twice is not an error. */
  addGroupMember(groupId: number, userId: string): Promise<boolean>;
  removeGroupMember(groupId: number, userId: string): Promise<boolean>;

  /* -- Predictions ------------------------------------------------------- */

  /**
   * Returns undefined when the fixture has already kicked off — the write is refused by the
   * `kickoff_at > now()` clause itself, not by a check above it, so a handler that forgot
   * the 409 still cannot let a late prediction through.
   */
  putPrediction(input: PutPredictionInput): Promise<PredictionRecord | undefined>;
  /**
   * The caller's own rows plus, for fixtures that have kicked off, everyone else's. The
   * `kickoff_at <= now()` half is part of the query and not a filter applied to its result:
   * a prediction that must stay private is never read out of the table at all.
   */
  listPredictions(input: ListPredictionsInput): Promise<PredictionRecord[]>;
  leaderboard(groupId: number): Promise<LeaderboardRow[]>;

  /**
   * Fixture ids with unscored predictions whose kick-off is between `notBefore` and
   * `notAfter` — the poller's settlement queue. The lower bound is what stops a fixture the
   * provider never resolves (cancelled, or an id withdrawn) from costing one request per
   * tick forever; those predictions simply stay unscored and count zero.
   */
  fixturesAwaitingSettlement(
    notBefore: Date,
    notAfter: Date,
    limit: number,
  ): Promise<number[]>;
  /** Scores every unsettled prediction for the fixture; returns how many rows it scored. */
  /**
   * Settles every open prediction on a fixture.
   *
   * [context] carries what the rulebook needs beyond the scoreline - the competition round,
   * which decides the stage multiplier. Optional so a caller that only knows the score
   * still settles correctly, at a league match's rate.
   */
  settleFixture(
    fixtureId: number,
    finalScore: ScoreJson,
    context?: SettlementContext | undefined,
  ): Promise<number>;
  /**
   * Moves the kick-off snapshot of a postponed fixture's unsettled predictions. The lock and
   * the visibility rule both read that column, so a rescheduled match re-opens for edits and
   * leaves the settlement queue until its new date.
   */
  rescheduleFixture(fixtureId: number, kickoffAt: Date): Promise<number>;

  /* -- Chat -------------------------------------------------------------- */

  postGroupMessage(input: PostGroupMessageInput): Promise<GroupMessageRecord>;
  listGroupMessages(groupId: number, since: Date): Promise<GroupMessageRecord[]>;
  /** Backs the per-user post rate limit. */
  countRecentGroupMessages(groupId: number, userId: string, since: Date): Promise<number>;

  close(): Promise<void>;
}

export interface UserProfileSeed {
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
}

/** `null` clears the field; an absent key leaves it alone. */
export interface UserProfilePatch {
  displayName?: string | null | undefined;
  avatarUrl?: string | null | undefined;
}

export interface CreateGroupInput {
  name: string;
  ownerId: string;
  inviteCode: string;
  leagueIds: number[];
  teamIds: number[];
}

export interface UpdateGroupPatch {
  name?: string | undefined;
  leagueIds?: number[] | undefined;
  teamIds?: number[] | undefined;
}

export interface PutPredictionInput {
  groupId: number;
  fixtureId: number;
  userId: string;
  home: number;
  away: number;
  /** Read from the provider fixture at write time; see the column comment in 002. */
  kickoffAt: Date;
  now: Date;
}

export interface ListPredictionsInput {
  groupId: number;
  fixtureIds: number[];
  /** The only user whose unlocked predictions are readable. */
  viewerId: string;
  now: Date;
}

export interface PostGroupMessageInput {
  groupId: number;
  userId: string;
  text: string;
}

export type { CacheQueryable };

/** What the rulebook needs about a fixture beyond its final score. */
export interface SettlementContext {
  /** The provider's `league.round`, verbatim. Decides the stage multiplier. */
  round?: string | undefined;
}

export interface StoreConfig {
  readonly databaseUrl?: string | undefined;
}

/**
 * Bounds one subscription row: a client sending a million ids would otherwise write a
 * row large enough to slow every `tokensForMatch` scan behind it.
 */
export const MAX_IDS_PER_LIST = 500;

/**
 * `team_ids` and `league_ids` are INTEGER[]; a wider value does not round-trip, it makes
 * Postgres reject the whole INSERT ("out of range for type integer"), which would lose every
 * other id in the same request. Dropping the impossible one is the lesser failure.
 */
const MAX_INT4 = 2_147_483_647;

/** Positive integers only, de-duplicated, order preserved, length capped, range capped. */
export function normalizeIds(
  values: readonly unknown[] | undefined,
  max: number = MAX_INT4,
): number[] {
  if (!values) return [];
  const seen = new Set<number>();
  for (const value of values) {
    const id = typeof value === 'string' ? Number(value) : value;
    if (typeof id !== 'number' || !Number.isSafeInteger(id) || id <= 0 || id > max) continue;
    seen.add(id);
    if (seen.size >= MAX_IDS_PER_LIST) break;
  }
  return [...seen];
}

/**
 * The app sends JSON booleans; the strings are accepted because every default here except
 * `substitutions` is `true`, so treating a literal "false" as junk would switch a category
 * back ON for someone who turned it off.
 */
function bool(value: unknown, fallback: boolean): boolean {
  if (typeof value === 'boolean') return value;
  if (value === 'true') return true;
  if (value === 'false') return false;
  return fallback;
}

/**
 * Only a number or a non-blank numeric string counts. `Number()` alone would turn `null`,
 * `""`, `[]` and `false` into 0, and 0 is not "no opinion" here — it is a valid setting that
 * silently switches a device's pre-match notifications off.
 */
function minutes(value: unknown, fallback: number): number {
  const parsed =
    typeof value === 'number'
      ? value
      : typeof value === 'string' && value.trim().length > 0
        ? Number(value)
        : Number.NaN;
  return Number.isFinite(parsed) ? Math.min(1440, Math.max(0, Math.trunc(parsed))) : fallback;
}

/**
 * Preferences arrive from clients and from JSONB columns written by older builds, so a
 * missing or junk key falls back to the shipped default instead of rejecting the row.
 */
export function normalizePreferences(value: unknown): SubscriptionPreferences {
  const input = (value ?? {}) as Partial<Record<keyof SubscriptionPreferences, unknown>>;
  return {
    goals: bool(input.goals, DEFAULT_SUBSCRIPTION_PREFERENCES.goals),
    cards: bool(input.cards, DEFAULT_SUBSCRIPTION_PREFERENCES.cards),
    substitutions: bool(input.substitutions, DEFAULT_SUBSCRIPTION_PREFERENCES.substitutions),
    kickoffAndFullTime: bool(
      input.kickoffAndFullTime,
      DEFAULT_SUBSCRIPTION_PREFERENCES.kickoffAndFullTime,
    ),
    lineups: bool(input.lineups, DEFAULT_SUBSCRIPTION_PREFERENCES.lineups),
    preMatchLeadMinutes: minutes(
      input.preMatchLeadMinutes,
      DEFAULT_SUBSCRIPTION_PREFERENCES.preMatchLeadMinutes,
    ),
  };
}

export function normalizeSubscription(subscription: SubscriptionRecord): SubscriptionRecord {
  return {
    token: subscription.token,
    teamIds: normalizeIds(subscription.teamIds),
    leagueIds: normalizeIds(subscription.leagueIds),
    // match_ids is BIGINT[], matching the client's `long`, so only JS's own integer ceiling
    // applies here.
    matchIds: normalizeIds(subscription.matchIds, Number.MAX_SAFE_INTEGER),
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
    const { classifyConnectionFault, withDatabaseFallback } = await import('./resilience.js');
    const makeMemory = async (): Promise<Store> => {
      const { createMemoryStore } = await import('./memory.js');
      return createMemoryStore(logger);
    };
    try {
      const store = await createPostgresStore(databaseUrl, logger);
      logger.info('store: postgres', { kind: store.kind });
      // The database can also die later, and did: Render deletes a free instance after
      // thirty days without the URL ever ceasing to parse.
      const fallback = await makeMemory();
      return withDatabaseFallback(store, () => fallback, logger);
    } catch (error) {
      const fault = classifyConnectionFault(error);
      // A database that is there and refusing us is a misconfiguration somebody has to
      // see. Serving happily on memory would hide a wrong password behind a service that
      // looks fine until the day somebody asks where the data went.
      if (fault !== 'unreachable') throw error;
      const store = await makeMemory();
      logger.error(
        'store: DATABASE_URL is set but the database cannot be reached, so this instance ' +
          'is running in memory. Football, the catalogue and notifications all work; ' +
          'accounts, groups and predictions will not survive a restart, and already-' +
          'notified events can be pushed again. On Render a free Postgres is deleted ' +
          'after 30 days - create a new one and update DATABASE_URL.',
        { error, kind: store.kind },
      );
      return store;
    }
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
