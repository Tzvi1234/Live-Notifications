/**
 * Postgres-backed store. The only implementation that survives a restart, and therefore
 * the only one that can honestly claim "this event was already pushed".
 */

import { randomUUID } from 'node:crypto';
import pg from 'pg';
import type { Pool as PgPool, PoolClient, PoolConfig } from 'pg';

import { CORRECT_OUTCOME_POINTS, EXACT_SCORE_POINTS } from '../game/scoring.js';
import {
  MATCH_PHASES,
  type DeviceRecord,
  type GroupMemberRecord,
  type GroupMessageRecord,
  type GroupRecord,
  type LeaderboardRow,
  type MatchPhase,
  type PredictionRecord,
  type ScoreJson,
  type SubscriptionRecord,
  type TrackedMatchState,
  type UserRecord,
} from '../types.js';
import type { Logger } from '../logger.js';
import { applyMigrations, loadMigrations } from './migrations.js';
import {
  matchIdFromEventId,
  normalizePreferences,
  normalizeSubscription,
  type CreateGroupInput,
  type DeviceRegistration,
  type ListPredictionsInput,
  type MatchTargets,
  type PostGroupMessageInput,
  type PruneStats,
  type PushTarget,
  type PutPredictionInput,
  type Store,
  type StoreKind,
  type UpdateGroupPatch,
  type UserProfilePatch,
  type UserProfileSeed,
} from './index.js';

const { Pool } = pg;

/**
 * Advisory lock ids are process-wide integers with no namespacing, so the value only has
 * to be stable across replicas of *this* service and distinct from any other lock it takes.
 */
const LEADER_LOCK_KEY = 4_711_030_921;
const SCHEMA_LOCK_KEY = 4_711_030_922;

const VALID_PHASES: ReadonlySet<string> = new Set(MATCH_PHASES);

interface DeviceRow {
  token: string;
  device_id: string;
  platform: string;
  app_version: string | null;
  time_zone: string | null;
  locale: string | null;
  created_at: Date;
  last_seen_at: Date;
}

interface SubscriptionRow {
  token: string;
  team_ids: number[] | null;
  league_ids: number[] | null;
  /** BIGINT[] arrives from node-pg as decimal strings, never as numbers. */
  match_ids: string[] | null;
  preferences: unknown;
}

interface MatchStateRow {
  phase: string;
  score_home: number | null;
  score_away: number | null;
  elapsed: number | null;
  last_sequence: number;
  lineups_sent: boolean;
  sent_event_ids: string[] | null;
}

interface UserRow {
  clerk_user_id: string;
  display_name: string | null;
  avatar_url: string | null;
  created_at: Date;
  last_seen_at: Date;
}

interface GroupRow {
  id: string;
  name: string;
  owner_id: string;
  invite_code: string;
  league_ids: number[] | null;
  team_ids: number[] | null;
  member_count: string;
  created_at: Date;
}

interface GroupMemberRow {
  user_id: string;
  display_name: string | null;
  avatar_url: string | null;
  joined_at: Date;
  is_owner: boolean;
}

interface PredictionRow {
  group_id: string;
  fixture_id: string;
  user_id: string;
  display_name: string | null;
  avatar_url: string | null;
  home: number;
  away: number;
  kickoff_at: Date;
  points: number | null;
  exact: boolean | null;
  outcome: boolean | null;
  updated_at: Date;
}

interface LeaderboardRowShape {
  user_id: string;
  display_name: string | null;
  avatar_url: string | null;
  points: number;
  exact_count: number;
  outcome_count: number;
  settled_count: number;
}

interface MessageRow {
  id: string;
  group_id: string;
  user_id: string;
  display_name: string | null;
  avatar_url: string | null;
  text: string;
  created_at: Date;
}

function toUserRecord(row: UserRow): UserRecord {
  return {
    clerkUserId: row.clerk_user_id,
    displayName: row.display_name ?? undefined,
    avatarUrl: row.avatar_url ?? undefined,
    createdAt: row.created_at.getTime(),
    lastSeenAt: row.last_seen_at.getTime(),
  };
}

/**
 * BIGSERIAL and BIGINT arrive from node-pg as decimal strings — the same reason
 * `SubscriptionRow.match_ids` is `string[]`. Group and fixture ids are well inside the safe
 * integer range, so the conversion is lossless; it is the missing `Number()` that bites,
 * by making `group.id === groupId` quietly false everywhere.
 */
function toGroupRecord(row: GroupRow): GroupRecord {
  return {
    id: Number(row.id),
    name: row.name,
    ownerId: row.owner_id,
    inviteCode: row.invite_code,
    leagueIds: row.league_ids ?? [],
    teamIds: row.team_ids ?? [],
    memberCount: Number(row.member_count),
    createdAt: row.created_at.getTime(),
  };
}

function toGroupMemberRecord(row: GroupMemberRow): GroupMemberRecord {
  return {
    userId: row.user_id,
    displayName: row.display_name ?? undefined,
    avatarUrl: row.avatar_url ?? undefined,
    joinedAt: row.joined_at.getTime(),
    isOwner: row.is_owner,
  };
}

function toPredictionRecord(row: PredictionRow): PredictionRecord {
  return {
    groupId: Number(row.group_id),
    fixtureId: Number(row.fixture_id),
    userId: row.user_id,
    displayName: row.display_name ?? undefined,
    avatarUrl: row.avatar_url ?? undefined,
    home: row.home,
    away: row.away,
    kickoffAt: row.kickoff_at.getTime(),
    points: row.points ?? undefined,
    exact: row.exact ?? undefined,
    correctOutcome: row.outcome ?? undefined,
    updatedAt: row.updated_at.getTime(),
  };
}

function toMessageRecord(row: MessageRow): GroupMessageRecord {
  return {
    id: Number(row.id),
    groupId: Number(row.group_id),
    userId: row.user_id,
    displayName: row.display_name ?? undefined,
    avatarUrl: row.avatar_url ?? undefined,
    text: row.text,
    createdAt: row.created_at.getTime(),
  };
}

/**
 * Every group read has to carry its leagues, its teams and how many members it has, and all
 * three live in tables of their own. Aggregating them in lateral sub-selects keeps that to
 * one round trip and one row per group, instead of a join that multiplies the group out by
 * teams x leagues x members and has to be de-duplicated in Node.
 */
const GROUP_COLUMNS = `
  g.id, g.name, g.owner_id, g.invite_code, g.created_at,
  COALESCE(l.ids, '{}') AS league_ids,
  COALESCE(t.ids, '{}') AS team_ids,
  COALESCE(m.count, 0)  AS member_count`;

const GROUP_AGGREGATES = `
  LEFT JOIN LATERAL (
    SELECT array_agg(league_id ORDER BY league_id) AS ids
      FROM group_leagues WHERE group_id = g.id
  ) l ON TRUE
  LEFT JOIN LATERAL (
    SELECT array_agg(team_id ORDER BY team_id) AS ids
      FROM group_teams WHERE group_id = g.id
  ) t ON TRUE
  LEFT JOIN LATERAL (
    SELECT count(*) AS count FROM group_members WHERE group_id = g.id
  ) m ON TRUE`;

function toDeviceRecord(row: DeviceRow): DeviceRecord {
  return {
    token: row.token,
    deviceId: row.device_id,
    platform: row.platform,
    appVersion: row.app_version ?? undefined,
    timeZone: row.time_zone ?? undefined,
    locale: row.locale ?? undefined,
    createdAt: row.created_at.getTime(),
    lastSeenAt: row.last_seen_at.getTime(),
  };
}

function toPhase(value: string): MatchPhase {
  return VALID_PHASES.has(value) ? (value as MatchPhase) : 'UNKNOWN';
}

/**
 * Render's managed Postgres terminates TLS with a certificate from its own CA. The
 * internal (`*.internal`) hostnames never leave Render's network and take no TLS at all.
 */
function sslFor(databaseUrl: string): PoolConfig['ssl'] {
  let host: string;
  try {
    host = new URL(databaseUrl).hostname;
  } catch {
    return undefined;
  }
  if (host === 'localhost' || host === '127.0.0.1' || host === '::1' || host.endsWith('.internal')) {
    return undefined;
  }
  return { rejectUnauthorized: false };
}

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value));
}

/** `release()` throws when it is called twice; a double release must not mask the real failure. */
function releaseQuietly(client: PoolClient, error?: Error): void {
  try {
    client.release(error);
  } catch {
    // Already back in the pool.
  }
}

/**
 * THE CHECKED-OUT CLIENT TRAP.
 *
 * node-pg's pool removes its own 'error' listener from a client while that client is checked
 * out (pg-pool `_acquireClient`) and only reattaches it on release, so `pool.on('error')`
 * covers idle connections only. A connection killed server-side — Postgres restart, Render
 * maintenance, failover — while no query is in flight therefore emits 'error' on an
 * EventEmitter with no listener, which Node throws as an uncaught exception and which takes
 * the whole service down. That is exactly the moment a transaction sits between two of its
 * statements, and it is the permanent condition of the leader-lock connection.
 *
 * So every checkout here carries a listener for as long as it is held, and a connection that
 * reported an error is destroyed rather than returned to the pool.
 */
async function withClient<T>(pool: PgPool, run: (client: PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  let connectionError: Error | undefined;
  const onError = (error: Error): void => {
    connectionError = error;
  };
  client.on('error', onError);
  try {
    return await run(client);
  } finally {
    client.off('error', onError);
    releaseQuietly(client, connectionError);
  }
}

class PostgresStore implements Store {
  readonly kind: StoreKind = 'postgres';

  private readonly pool: PgPool;
  private readonly logger: Logger;
  /** The advisory lock lives on a connection, so leadership means holding this client. */
  private leaderClient: PoolClient | undefined;
  /** Held so it can be detached again; see `withClient` for why it has to exist at all. */
  private leaderClientErrorListener: ((error: Error) => void) | undefined;
  private leaseExpiresAt = 0;
  private closed = false;

  constructor(pool: PgPool, logger: Logger) {
    this.pool = pool;
    this.logger = logger;
  }

  async upsertDevice(device: DeviceRegistration): Promise<DeviceRecord> {
    // device_id is deliberately absent from the SET list: a re-register of a known token
    // keeps the id the app already stored and logged against.
    const { rows } = await this.pool.query<DeviceRow>(
      `INSERT INTO devices (token, device_id, platform, app_version, time_zone, locale)
       VALUES ($1, $2, $3, $4, $5, $6)
       ON CONFLICT (token) DO UPDATE SET
         platform     = EXCLUDED.platform,
         app_version  = EXCLUDED.app_version,
         time_zone    = EXCLUDED.time_zone,
         locale       = EXCLUDED.locale,
         last_seen_at = now()
       RETURNING token, device_id, platform, app_version, time_zone, locale, created_at, last_seen_at`,
      [
        device.token,
        randomUUID(),
        device.platform ?? 'android',
        device.appVersion ?? null,
        device.timeZone ?? null,
        device.locale ?? null,
      ],
    );
    return toDeviceRecord(rows[0]!);
  }

  async deleteDevice(token: string): Promise<boolean> {
    const result = await this.pool.query('DELETE FROM devices WHERE token = $1', [token]);
    return (result.rowCount ?? 0) > 0;
  }

  async touchDevice(token: string): Promise<void> {
    await this.pool.query('UPDATE devices SET last_seen_at = now() WHERE token = $1', [token]);
  }

  async putSubscription(subscription: SubscriptionRecord): Promise<void> {
    const normalized = normalizeSubscription(subscription);
    await withClient(this.pool, async (client) => {
      try {
        await client.query('BEGIN');
        // A client can PUT /v1/subscriptions before POST /v1/devices lands (retry after a
        // dropped response, reinstall, cold start). The FK would reject the row and the
        // device would silently receive nothing, so register a placeholder first.
        await client.query(
          'INSERT INTO devices (token, device_id) VALUES ($1, $2) ON CONFLICT (token) DO NOTHING',
          [normalized.token, randomUUID()],
        );
        await client.query(
          `INSERT INTO subscriptions (token, team_ids, league_ids, match_ids, preferences, updated_at)
           VALUES ($1, $2::int[], $3::int[], $4::bigint[], $5::jsonb, now())
           ON CONFLICT (token) DO UPDATE SET
             team_ids    = EXCLUDED.team_ids,
             league_ids  = EXCLUDED.league_ids,
             match_ids   = EXCLUDED.match_ids,
             preferences = EXCLUDED.preferences,
             updated_at  = now()`,
          [
            normalized.token,
            normalized.teamIds,
            normalized.leagueIds,
            normalized.matchIds,
            JSON.stringify(normalized.preferences),
          ],
        );
        await client.query('COMMIT');
      } catch (error) {
        await client.query('ROLLBACK').catch(() => undefined);
        throw error;
      }
    });
  }

  async getSubscription(token: string): Promise<SubscriptionRecord | undefined> {
    const { rows } = await this.pool.query<SubscriptionRow>(
      'SELECT token, team_ids, league_ids, match_ids, preferences FROM subscriptions WHERE token = $1',
      [token],
    );
    const row = rows[0];
    if (!row) return undefined;
    return {
      token: row.token,
      teamIds: row.team_ids ?? [],
      leagueIds: row.league_ids ?? [],
      matchIds: (row.match_ids ?? []).map(Number),
      preferences: normalizePreferences(row.preferences),
    };
  }

  async tokensForMatch(match: MatchTargets): Promise<PushTarget[]> {
    // `&&` (array overlap) is the operator the GIN indexes answer; `= ANY(column)` would
    // force a sequential scan over every subscription on every event of every match.
    const { rows } = await this.pool.query<{ token: string; preferences: unknown }>(
      `SELECT d.token, s.preferences
         FROM subscriptions s
         JOIN devices d ON d.token = s.token
        WHERE s.match_ids  && $1::bigint[]
           OR s.league_ids && $2::int[]
           OR s.team_ids   && $3::int[]`,
      [[match.id], [match.leagueId], [match.home.id, match.away.id]],
    );
    return rows.map((row) => ({
      token: row.token,
      preferences: normalizePreferences(row.preferences),
    }));
  }

  async markEventSent(eventId: string, matchId?: number): Promise<boolean> {
    const resolvedMatchId = matchId ?? matchIdFromEventId(eventId);
    const result = await this.pool.query(
      `INSERT INTO sent_events (event_id, match_id, sent_at)
       VALUES ($1, $2, now())
       ON CONFLICT (event_id) DO NOTHING
       RETURNING event_id`,
      [eventId, resolvedMatchId],
    );
    // RETURNING yields a row only when this statement inserted it: the conflicting caller
    // gets zero rows, and exactly one of any number of concurrent pollers pushes.
    return (result.rowCount ?? 0) > 0;
  }

  async getMatchState(matchId: number): Promise<TrackedMatchState | undefined> {
    const { rows } = await this.pool.query<MatchStateRow>(
      `SELECT m.phase, m.score_home, m.score_away, m.elapsed, m.last_sequence, m.lineups_sent,
              COALESCE(e.ids, '{}') AS sent_event_ids
         FROM match_state m
         LEFT JOIN LATERAL (
              SELECT array_agg(event_id) AS ids FROM sent_events WHERE match_id = m.match_id
         ) e ON TRUE
        WHERE m.match_id = $1`,
      [matchId],
    );
    const row = rows[0];
    if (!row) return undefined;
    return {
      matchId,
      phase: toPhase(row.phase),
      score:
        row.score_home === null || row.score_away === null
          ? undefined
          : { home: row.score_home, away: row.score_away },
      elapsed: row.elapsed ?? undefined,
      lastSequence: row.last_sequence,
      sentEventIds: new Set(row.sent_event_ids ?? []),
      lineupsSent: row.lineups_sent,
    };
  }

  async putMatchState(state: TrackedMatchState): Promise<void> {
    // `state.sentEventIds` is not written: sent_events is that set's only writer, and a
    // whole-set overwrite here could drop an id a concurrent poller just claimed.
    await this.pool.query(
      `INSERT INTO match_state
         (match_id, phase, score_home, score_away, elapsed, last_sequence, lineups_sent, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, now())
       ON CONFLICT (match_id) DO UPDATE SET
         phase         = EXCLUDED.phase,
         score_home    = EXCLUDED.score_home,
         score_away    = EXCLUDED.score_away,
         elapsed       = EXCLUDED.elapsed,
         last_sequence = GREATEST(match_state.last_sequence, EXCLUDED.last_sequence),
         lineups_sent  = match_state.lineups_sent OR EXCLUDED.lineups_sent,
         updated_at    = now()`,
      [
        state.matchId,
        state.phase,
        state.score?.home ?? null,
        state.score?.away ?? null,
        state.elapsed ?? null,
        state.lastSequence,
        state.lineupsSent,
      ],
    );
  }

  async nextSequence(matchId: number): Promise<number> {
    // One statement, so two instances asking at once get two different numbers instead of
    // both reading N and both writing N+1.
    const { rows } = await this.pool.query<{ last_sequence: number }>(
      `INSERT INTO match_state (match_id, last_sequence, updated_at)
       VALUES ($1, 1, now())
       ON CONFLICT (match_id) DO UPDATE SET
         last_sequence = match_state.last_sequence + 1,
         updated_at    = now()
       RETURNING last_sequence`,
      [matchId],
    );
    return rows[0]!.last_sequence;
  }

  async pruneOlderThan(date: Date): Promise<PruneStats> {
    return withClient(this.pool, async (client) => {
      try {
        await client.query('BEGIN');
        const sentEvents = await client.query('DELETE FROM sent_events WHERE sent_at < $1', [date]);
        const matchStates = await client.query('DELETE FROM match_state WHERE updated_at < $1', [
          date,
        ]);
        // Deleted explicitly rather than by ON DELETE CASCADE so the count is reportable.
        const subscriptions = await client.query(
          `DELETE FROM subscriptions s
            USING devices d
            WHERE d.token = s.token AND d.last_seen_at < $1`,
          [date],
        );
        const devices = await client.query('DELETE FROM devices WHERE last_seen_at < $1', [date]);
        await client.query('COMMIT');
        return {
          devices: devices.rowCount ?? 0,
          subscriptions: subscriptions.rowCount ?? 0,
          sentEvents: sentEvents.rowCount ?? 0,
          matchStates: matchStates.rowCount ?? 0,
        };
      } catch (error) {
        await client.query('ROLLBACK').catch(() => undefined);
        throw error;
      }
    });
  }

  async removeTokens(tokens: string[]): Promise<number> {
    if (tokens.length === 0) return 0;
    const result = await this.pool.query('DELETE FROM devices WHERE token = ANY($1::text[])', [
      tokens,
    ]);
    return result.rowCount ?? 0;
  }

  /* ---------------------------------------------------------------------- */
  /* Accounts                                                                */
  /* ---------------------------------------------------------------------- */

  async upsertUser(clerkUserId: string, profile?: UserProfileSeed | undefined): Promise<UserRecord> {
    // COALESCE, not EXCLUDED: this runs on every authenticated request, and the session
    // token's claims are Clerk's copy of the profile. Overwriting from them would undo a
    // PATCH /v1/me on the user's very next call, so a claim only ever fills a blank.
    const { rows } = await this.pool.query<UserRow>(
      `INSERT INTO users (clerk_user_id, display_name, avatar_url)
       VALUES ($1, $2, $3)
       ON CONFLICT (clerk_user_id) DO UPDATE SET
         display_name = COALESCE(users.display_name, EXCLUDED.display_name),
         avatar_url   = COALESCE(users.avatar_url, EXCLUDED.avatar_url),
         last_seen_at = now()
       RETURNING clerk_user_id, display_name, avatar_url, created_at, last_seen_at`,
      [clerkUserId, profile?.displayName ?? null, profile?.avatarUrl ?? null],
    );
    return toUserRecord(rows[0]!);
  }

  async getUser(clerkUserId: string): Promise<UserRecord | undefined> {
    const { rows } = await this.pool.query<UserRow>(
      `SELECT clerk_user_id, display_name, avatar_url, created_at, last_seen_at
         FROM users WHERE clerk_user_id = $1`,
      [clerkUserId],
    );
    const row = rows[0];
    return row ? toUserRecord(row) : undefined;
  }

  async updateUserProfile(
    clerkUserId: string,
    patch: UserProfilePatch,
  ): Promise<UserRecord | undefined> {
    // A key the caller did not send arrives as undefined and the corresponding flag keeps
    // the column; `null` is a value here and clears it. One statement either way, so a
    // partial PATCH cannot race a concurrent one into a half-applied row.
    const { rows } = await this.pool.query<UserRow>(
      `UPDATE users SET
         display_name = CASE WHEN $2::boolean THEN $3 ELSE display_name END,
         avatar_url   = CASE WHEN $4::boolean THEN $5 ELSE avatar_url   END,
         last_seen_at = now()
       WHERE clerk_user_id = $1
       RETURNING clerk_user_id, display_name, avatar_url, created_at, last_seen_at`,
      [
        clerkUserId,
        patch.displayName !== undefined,
        patch.displayName ?? null,
        patch.avatarUrl !== undefined,
        patch.avatarUrl ?? null,
      ],
    );
    const row = rows[0];
    return row ? toUserRecord(row) : undefined;
  }

  /* ---------------------------------------------------------------------- */
  /* Groups                                                                  */
  /* ---------------------------------------------------------------------- */

  async createGroup(input: CreateGroupInput): Promise<GroupRecord> {
    return withClient(this.pool, async (client) => {
      try {
        await client.query('BEGIN');
        const { rows } = await client.query<{ id: string }>(
          'INSERT INTO groups (name, owner_id, invite_code) VALUES ($1, $2, $3) RETURNING id',
          [input.name, input.ownerId, input.inviteCode],
        );
        const groupId = rows[0]!.id;
        // The owner is an ordinary member too, so every later membership check — including
        // the owner's own — has exactly one answer to consult.
        await client.query(
          'INSERT INTO group_members (group_id, user_id) VALUES ($1, $2)',
          [groupId, input.ownerId],
        );
        await this.replaceGroupSelections(client, groupId, input.leagueIds, input.teamIds);
        await client.query('COMMIT');
        return {
          id: Number(groupId),
          name: input.name,
          ownerId: input.ownerId,
          inviteCode: input.inviteCode,
          leagueIds: [...input.leagueIds],
          teamIds: [...input.teamIds],
          memberCount: 1,
          createdAt: Date.now(),
        };
      } catch (error) {
        await client.query('ROLLBACK').catch(() => undefined);
        throw error;
      }
    });
  }

  async getGroup(groupId: number): Promise<GroupRecord | undefined> {
    const { rows } = await this.pool.query<GroupRow>(
      `SELECT ${GROUP_COLUMNS} FROM groups g ${GROUP_AGGREGATES} WHERE g.id = $1`,
      [groupId],
    );
    const row = rows[0];
    return row ? toGroupRecord(row) : undefined;
  }

  async getGroupByInviteCode(inviteCode: string): Promise<GroupRecord | undefined> {
    const { rows } = await this.pool.query<GroupRow>(
      `SELECT ${GROUP_COLUMNS} FROM groups g ${GROUP_AGGREGATES} WHERE g.invite_code = $1`,
      [inviteCode],
    );
    const row = rows[0];
    return row ? toGroupRecord(row) : undefined;
  }

  async listGroupsForUser(userId: string): Promise<GroupRecord[]> {
    const { rows } = await this.pool.query<GroupRow>(
      `SELECT ${GROUP_COLUMNS}
         FROM groups g
         JOIN group_members gm ON gm.group_id = g.id AND gm.user_id = $1
         ${GROUP_AGGREGATES}
        ORDER BY g.created_at, g.id`,
      [userId],
    );
    return rows.map(toGroupRecord);
  }

  async updateGroup(groupId: number, patch: UpdateGroupPatch): Promise<GroupRecord | undefined> {
    const updated = await withClient(this.pool, async (client) => {
      try {
        await client.query('BEGIN');
        const { rowCount } = await client.query(
          'UPDATE groups SET name = COALESCE($2, name), updated_at = now() WHERE id = $1',
          [groupId, patch.name ?? null],
        );
        if ((rowCount ?? 0) === 0) {
          await client.query('ROLLBACK');
          return false;
        }
        // Replaced wholesale, like PUT /v1/subscriptions: the app holds the authoritative
        // list, and a merge would leave both sides guessing who last removed a team.
        await this.replaceGroupSelections(client, String(groupId), patch.leagueIds, patch.teamIds);
        await client.query('COMMIT');
        return true;
      } catch (error) {
        await client.query('ROLLBACK').catch(() => undefined);
        throw error;
      }
    });
    // Re-read outside the checkout: reaching into the pool for a second client while still
    // holding one is how a pool of 8 deadlocks under load.
    return updated ? this.getGroup(groupId) : undefined;
  }

  async deleteGroup(groupId: number): Promise<boolean> {
    // Members, leagues, teams, predictions and messages all go with it by ON DELETE CASCADE.
    const result = await this.pool.query('DELETE FROM groups WHERE id = $1', [groupId]);
    return (result.rowCount ?? 0) > 0;
  }

  async listGroupMembers(groupId: number): Promise<GroupMemberRecord[]> {
    const { rows } = await this.pool.query<GroupMemberRow>(
      `SELECT gm.user_id, u.display_name, u.avatar_url, gm.joined_at,
              (g.owner_id = gm.user_id) AS is_owner
         FROM group_members gm
         JOIN groups g ON g.id = gm.group_id
         JOIN users  u ON u.clerk_user_id = gm.user_id
        WHERE gm.group_id = $1
        ORDER BY is_owner DESC, gm.joined_at, gm.user_id`,
      [groupId],
    );
    return rows.map(toGroupMemberRecord);
  }

  async isGroupMember(groupId: number, userId: string): Promise<boolean> {
    const { rowCount } = await this.pool.query(
      'SELECT 1 FROM group_members WHERE group_id = $1 AND user_id = $2',
      [groupId, userId],
    );
    return (rowCount ?? 0) > 0;
  }

  async addGroupMember(groupId: number, userId: string): Promise<boolean> {
    // DO NOTHING rather than an existence check: posting the code twice is a retry, not an
    // error, and the database is the only place the two can be told apart without a race.
    const result = await this.pool.query(
      `INSERT INTO group_members (group_id, user_id) VALUES ($1, $2)
       ON CONFLICT (group_id, user_id) DO NOTHING`,
      [groupId, userId],
    );
    return (result.rowCount ?? 0) > 0;
  }

  async removeGroupMember(groupId: number, userId: string): Promise<boolean> {
    const result = await this.pool.query(
      'DELETE FROM group_members WHERE group_id = $1 AND user_id = $2',
      [groupId, userId],
    );
    return (result.rowCount ?? 0) > 0;
  }

  /** Both selection tables, replaced together; `undefined` leaves one of them alone. */
  private async replaceGroupSelections(
    client: PoolClient,
    groupId: string,
    leagueIds: number[] | undefined,
    teamIds: number[] | undefined,
  ): Promise<void> {
    if (leagueIds !== undefined) {
      await client.query('DELETE FROM group_leagues WHERE group_id = $1', [groupId]);
      if (leagueIds.length > 0) {
        await client.query(
          `INSERT INTO group_leagues (group_id, league_id)
           SELECT $1, unnest($2::int[]) ON CONFLICT DO NOTHING`,
          [groupId, leagueIds],
        );
      }
    }
    if (teamIds !== undefined) {
      await client.query('DELETE FROM group_teams WHERE group_id = $1', [groupId]);
      if (teamIds.length > 0) {
        await client.query(
          `INSERT INTO group_teams (group_id, team_id)
           SELECT $1, unnest($2::int[]) ON CONFLICT DO NOTHING`,
          [groupId, teamIds],
        );
      }
    }
  }

  /* ---------------------------------------------------------------------- */
  /* Predictions                                                             */
  /* ---------------------------------------------------------------------- */

  async putPrediction(input: PutPredictionInput): Promise<PredictionRecord | undefined> {
    // THE KICK-OFF LOCK, in the statement rather than above it.
    //
    // The insert's WHERE refuses a first prediction for a fixture already under way; the
    // DO UPDATE's WHERE refuses an edit to one. Neither reads the clock the caller passed —
    // `now()` is the database's — so two members' phones disagreeing about the time, or a
    // handler that skipped its own 409, still cannot record a prediction after kick-off.
    // Zero rows back means refused, which is the 409 the route reports.
    const { rows } = await this.pool.query<PredictionRow>(
      `INSERT INTO predictions (group_id, fixture_id, user_id, home, away, kickoff_at)
       SELECT $1, $2, $3, $4, $5, $6::timestamptz
        WHERE $6::timestamptz > now()
       ON CONFLICT (group_id, fixture_id, user_id) DO UPDATE SET
         home       = EXCLUDED.home,
         away       = EXCLUDED.away,
         kickoff_at = EXCLUDED.kickoff_at,
         updated_at = now()
         WHERE predictions.kickoff_at > now()
       RETURNING group_id, fixture_id, user_id, NULL::text AS display_name,
                 NULL::text AS avatar_url, home, away, kickoff_at, points, exact, outcome,
                 updated_at`,
      [
        input.groupId,
        input.fixtureId,
        input.userId,
        input.home,
        input.away,
        input.kickoffAt.toISOString(),
      ],
    );
    const row = rows[0];
    return row ? toPredictionRecord(row) : undefined;
  }

  async listPredictions(input: ListPredictionsInput): Promise<PredictionRecord[]> {
    if (input.fixtureIds.length === 0) return [];
    // THE VISIBILITY RULE, in the WHERE clause rather than in the route.
    //
    // Before kick-off the only rows this can return are the viewer's own: another member's
    // prediction is never read out of the table, so no serialiser, no cache and no future
    // handler can leak it. `now()` again, so the boundary is the database's clock.
    const { rows } = await this.pool.query<PredictionRow>(
      `SELECT p.group_id, p.fixture_id, p.user_id, u.display_name, u.avatar_url,
              p.home, p.away, p.kickoff_at, p.points, p.exact, p.outcome, p.updated_at
         FROM predictions p
         JOIN users u ON u.clerk_user_id = p.user_id
        WHERE p.group_id = $1
          AND p.fixture_id = ANY($2::bigint[])
          AND (p.user_id = $3 OR p.kickoff_at <= now())
        ORDER BY p.fixture_id, p.user_id`,
      [input.groupId, input.fixtureIds, input.viewerId],
    );
    return rows.map(toPredictionRecord);
  }

  async leaderboard(groupId: number): Promise<LeaderboardRow[]> {
    // LEFT JOIN from the membership so a member who has not predicted yet still appears,
    // on zero, rather than vanishing from the board until their first settled fixture.
    const { rows } = await this.pool.query<LeaderboardRowShape>(
      `SELECT gm.user_id,
              u.display_name,
              u.avatar_url,
              COALESCE(SUM(p.points), 0)::int                             AS points,
              COUNT(*) FILTER (WHERE p.exact)::int                        AS exact_count,
              COUNT(*) FILTER (WHERE p.outcome)::int                      AS outcome_count,
              COUNT(*) FILTER (WHERE p.points IS NOT NULL)::int           AS settled_count
         FROM group_members gm
         JOIN users u ON u.clerk_user_id = gm.user_id
         LEFT JOIN predictions p
                ON p.group_id = gm.group_id AND p.user_id = gm.user_id
        WHERE gm.group_id = $1
        GROUP BY gm.user_id, u.display_name, u.avatar_url
        ORDER BY points DESC, exact_count DESC, COALESCE(u.display_name, gm.user_id)`,
      [groupId],
    );
    return rows.map((row) => ({
      userId: row.user_id,
      displayName: row.display_name ?? undefined,
      avatarUrl: row.avatar_url ?? undefined,
      points: row.points,
      exactCount: row.exact_count,
      correctOutcomeCount: row.outcome_count,
      settledCount: row.settled_count,
    }));
  }

  async fixturesAwaitingSettlement(
    notBefore: Date,
    notAfter: Date,
    limit: number,
  ): Promise<number[]> {
    const { rows } = await this.pool.query<{ fixture_id: string }>(
      `SELECT fixture_id
         FROM predictions
        WHERE points IS NULL
          AND kickoff_at BETWEEN $1 AND $2
        GROUP BY fixture_id
        ORDER BY min(kickoff_at)
        LIMIT $3`,
      [notBefore.toISOString(), notAfter.toISOString(), limit],
    );
    return rows.map((row) => Number(row.fixture_id));
  }

  async settleFixture(fixtureId: number, finalScore: ScoreJson): Promise<number> {
    // One statement for the whole fixture rather than a read-score-write loop, so a second
    // poller settling the same fixture writes the same values instead of racing. The point
    // values are bound parameters: src/game/scoring.ts stays the only definition of them.
    const result = await this.pool.query(
      `UPDATE predictions SET
         exact      = (home = $2::int AND away = $3::int),
         outcome    = sign((home - away)::numeric) = sign(($2::int - $3::int)::numeric),
         points     = CASE
                        WHEN home = $2::int AND away = $3::int THEN $4::int
                        WHEN sign((home - away)::numeric)
                             = sign(($2::int - $3::int)::numeric) THEN $5::int
                        ELSE 0
                      END,
         settled_at = now(),
         updated_at = now()
       WHERE fixture_id = $1 AND points IS NULL`,
      [fixtureId, finalScore.home, finalScore.away, EXACT_SCORE_POINTS, CORRECT_OUTCOME_POINTS],
    );
    return result.rowCount ?? 0;
  }

  async rescheduleFixture(fixtureId: number, kickoffAt: Date): Promise<number> {
    const result = await this.pool.query(
      `UPDATE predictions SET kickoff_at = $2::timestamptz, updated_at = now()
        WHERE fixture_id = $1 AND points IS NULL AND kickoff_at <> $2::timestamptz`,
      [fixtureId, kickoffAt.toISOString()],
    );
    return result.rowCount ?? 0;
  }

  /* ---------------------------------------------------------------------- */
  /* Chat                                                                    */
  /* ---------------------------------------------------------------------- */

  async postGroupMessage(input: PostGroupMessageInput): Promise<GroupMessageRecord> {
    const { rows } = await this.pool.query<MessageRow>(
      `WITH inserted AS (
         INSERT INTO group_messages (group_id, user_id, text) VALUES ($1, $2, $3)
         RETURNING id, group_id, user_id, text, created_at
       )
       SELECT i.id, i.group_id, i.user_id, u.display_name, u.avatar_url, i.text, i.created_at
         FROM inserted i JOIN users u ON u.clerk_user_id = i.user_id`,
      [input.groupId, input.userId, input.text],
    );
    return toMessageRecord(rows[0]!);
  }

  async listGroupMessages(groupId: number, since: Date): Promise<GroupMessageRecord[]> {
    const { rows } = await this.pool.query<MessageRow>(
      `SELECT m.id, m.group_id, m.user_id, u.display_name, u.avatar_url, m.text, m.created_at
         FROM group_messages m
         JOIN users u ON u.clerk_user_id = m.user_id
        WHERE m.group_id = $1 AND m.created_at >= $2
        ORDER BY m.created_at, m.id`,
      [groupId, since.toISOString()],
    );
    return rows.map(toMessageRecord);
  }

  async countRecentGroupMessages(groupId: number, userId: string, since: Date): Promise<number> {
    const { rows } = await this.pool.query<{ count: string }>(
      `SELECT count(*) AS count FROM group_messages
        WHERE group_id = $1 AND user_id = $2 AND created_at >= $3`,
      [groupId, userId, since.toISOString()],
    );
    return Number(rows[0]?.count ?? 0);
  }

  async acquireLeaderLock(ttlMs: number): Promise<boolean> {
    if (this.closed) return false;

    if (this.leaderClient) {
      // Within the lease we trust the last check and skip a round trip per poll tick. A
      // connection killed server-side drops leadership immediately through the listener
      // `adoptLeaderClient` attaches, so ttlMs only ever hides a partition that reported
      // nothing at all; keep it at a small multiple of the poll interval regardless.
      if (Date.now() < this.leaseExpiresAt) return true;
      try {
        // Postgres frees a session advisory lock when its connection dies, so a dead
        // connection means leadership is already gone even though we still hold a client.
        await this.leaderClient.query('SELECT 1');
        this.leaseExpiresAt = Date.now() + ttlMs;
        return true;
      } catch (error) {
        this.logger.warn('leader lock connection lost, re-acquiring', { error });
        this.dropLeaderClient(error);
      }
    }

    const client = await this.pool.connect();
    try {
      const { rows } = await client.query<{ locked: boolean }>(
        'SELECT pg_try_advisory_lock($1) AS locked',
        [LEADER_LOCK_KEY],
      );
      if (rows[0]?.locked !== true) {
        releaseQuietly(client);
        return false;
      }
      this.adoptLeaderClient(client, ttlMs);
      this.logger.info('leader lock acquired', { lockKey: LEADER_LOCK_KEY, ttlMs });
      return true;
    } catch (error) {
      releaseQuietly(client, toError(error));
      throw error;
    }
  }

  async releaseLeaderLock(): Promise<void> {
    // Detached before the round trip: a concurrent acquire must not be handed a live lease
    // on a connection that is being torn down. Shutdown does call this from two places.
    const client = this.detachLeaderClient();
    if (!client) return;
    try {
      await client.query('SELECT pg_advisory_unlock($1)', [LEADER_LOCK_KEY]);
      releaseQuietly(client);
      this.logger.info('leader lock released', { lockKey: LEADER_LOCK_KEY });
    } catch (error) {
      // Destroy the connection instead of returning it: dropping it releases the lock too.
      releaseQuietly(client, toError(error));
      this.logger.warn('leader lock release failed; connection discarded', { error });
    }
  }

  /**
   * The leader connection is held idle for the life of the process, which makes it the one
   * most likely to be killed with no query in flight. Without this listener that kill is an
   * uncaught 'error' event — see `withClient`. Dropping leadership here also means the next
   * tick re-acquires instead of waiting out the lease.
   */
  private adoptLeaderClient(client: PoolClient, ttlMs: number): void {
    const onError = (error: Error): void => {
      if (this.leaderClient !== client) return;
      this.logger.warn('leader lock connection failed; leadership dropped', { error });
      this.dropLeaderClient(error);
    };
    client.on('error', onError);
    this.leaderClient = client;
    this.leaderClientErrorListener = onError;
    this.leaseExpiresAt = Date.now() + ttlMs;
  }

  /** Gives up leadership and returns the client, still checked out, listener removed. */
  private detachLeaderClient(): PoolClient | undefined {
    const client = this.leaderClient;
    const listener = this.leaderClientErrorListener;
    this.leaderClient = undefined;
    this.leaderClientErrorListener = undefined;
    this.leaseExpiresAt = 0;
    if (client && listener) client.off('error', listener);
    return client;
  }

  private dropLeaderClient(error: unknown): void {
    const client = this.detachLeaderClient();
    if (client) releaseQuietly(client, toError(error));
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    await this.releaseLeaderLock();
    await this.pool.end();
  }
}

export async function createPostgresStore(databaseUrl: string, logger: Logger): Promise<Store> {
  const scoped = logger.child({ component: 'store.postgres' });
  const pool = new Pool({
    connectionString: databaseUrl,
    ssl: sslFor(databaseUrl),
    // Render's smallest Postgres plans allow few connections and the service also runs a
    // poller; a small pool leaves headroom for the leader lock's dedicated connection.
    max: 8,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 10_000,
  });

  // Without this, an error on an *idle* pooled client (Postgres restart, Render maintenance)
  // is an unhandled 'error' event and takes the process down. It says nothing about clients
  // that are checked out at the time; those are guarded individually, see `withClient`.
  pool.on('error', (error) => {
    scoped.error('idle postgres client error', { error });
  });

  await ensureSchema(pool, scoped);
  return new PostgresStore(pool, scoped);
}

/**
 * Runs the migrations under the advisory lock the replayed schema.sql used to be run under.
 * The lock is still the point: two instances booting together during a deploy would
 * otherwise both find `schema_migrations` empty and both try to apply the same file.
 */
async function ensureSchema(pool: PgPool, logger: Logger): Promise<void> {
  const migrations = await loadMigrations();
  await withClient(pool, async (client) => {
    try {
      await client.query('SELECT pg_advisory_lock($1)', [SCHEMA_LOCK_KEY]);
      await applyMigrations(client, migrations, logger);
    } finally {
      await client.query('SELECT pg_advisory_unlock($1)', [SCHEMA_LOCK_KEY]).catch(() => undefined);
    }
  });
}
