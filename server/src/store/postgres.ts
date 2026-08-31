/**
 * Postgres-backed store. The only implementation that survives a restart, and therefore
 * the only one that can honestly claim "this event was already pushed".
 */

import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import type { Pool as PgPool, PoolClient, PoolConfig } from 'pg';

import {
  MATCH_PHASES,
  type DeviceRecord,
  type MatchPhase,
  type SubscriptionRecord,
  type TrackedMatchState,
} from '../types.js';
import type { Logger } from '../logger.js';
import {
  matchIdFromEventId,
  normalizePreferences,
  normalizeSubscription,
  type DeviceRegistration,
  type MatchTargets,
  type PruneStats,
  type PushTarget,
  type Store,
  type StoreKind,
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

/**
 * schema.sql is data, not code, so it does not follow the TypeScript build automatically.
 * Try the compiled location first, then the source tree, so `tsx src/index.ts` and a
 * `dist/` deploy that copied the file both work.
 */
async function readSchemaSql(): Promise<string> {
  const candidates = [
    new URL('./schema.sql', import.meta.url),
    new URL('../../src/store/schema.sql', import.meta.url),
  ];
  for (const candidate of candidates) {
    try {
      return await readFile(candidate, 'utf8');
    } catch {
      continue;
    }
  }
  throw new Error(
    `[store] schema.sql not found (looked in ${candidates.map((c) => c.pathname).join(', ')}). ` +
      'The build must copy src/store/*.sql into dist/store/.',
  );
}

class PostgresStore implements Store {
  readonly kind: StoreKind = 'postgres';

  private readonly pool: PgPool;
  private readonly logger: Logger;
  /** The advisory lock lives on a connection, so leadership means holding this client. */
  private leaderClient: PoolClient | undefined;
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
    const client = await this.pool.connect();
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
    } finally {
      client.release();
    }
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
    const client = await this.pool.connect();
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
    } finally {
      client.release();
    }
  }

  async removeTokens(tokens: string[]): Promise<number> {
    if (tokens.length === 0) return 0;
    const result = await this.pool.query('DELETE FROM devices WHERE token = ANY($1::text[])', [
      tokens,
    ]);
    return result.rowCount ?? 0;
  }

  async acquireLeaderLock(ttlMs: number): Promise<boolean> {
    if (this.closed) return false;

    if (this.leaderClient) {
      // Within the lease we trust the last check and skip a round trip per poll tick.
      // ttlMs is exactly the window in which we may still believe we lead after the
      // connection died server-side; keep it at a small multiple of the poll interval.
      if (Date.now() < this.leaseExpiresAt) return true;
      try {
        // Postgres frees a session advisory lock when its connection dies, so a dead
        // connection means leadership is already gone even though we still hold a client.
        await this.leaderClient.query('SELECT 1');
        this.leaseExpiresAt = Date.now() + ttlMs;
        return true;
      } catch (error) {
        this.logger.warn('leader lock connection lost, re-acquiring', { error });
        this.discardLeaderClient(error);
      }
    }

    const client = await this.pool.connect();
    try {
      const { rows } = await client.query<{ locked: boolean }>(
        'SELECT pg_try_advisory_lock($1) AS locked',
        [LEADER_LOCK_KEY],
      );
      if (rows[0]?.locked !== true) {
        client.release();
        return false;
      }
      this.leaderClient = client;
      this.leaseExpiresAt = Date.now() + ttlMs;
      this.logger.info('leader lock acquired', { lockKey: LEADER_LOCK_KEY, ttlMs });
      return true;
    } catch (error) {
      client.release(error as Error);
      throw error;
    }
  }

  async releaseLeaderLock(): Promise<void> {
    const client = this.leaderClient;
    if (!client) return;
    this.leaderClient = undefined;
    this.leaseExpiresAt = 0;
    try {
      await client.query('SELECT pg_advisory_unlock($1)', [LEADER_LOCK_KEY]);
      client.release();
      this.logger.info('leader lock released', { lockKey: LEADER_LOCK_KEY });
    } catch (error) {
      // Destroy the connection instead of returning it: dropping it releases the lock too.
      client.release(error as Error);
      this.logger.warn('leader lock release failed; connection discarded', { error });
    }
  }

  private discardLeaderClient(error: unknown): void {
    this.leaderClient?.release(error instanceof Error ? error : new Error(String(error)));
    this.leaderClient = undefined;
    this.leaseExpiresAt = 0;
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

  // Without this, an error on an idle pooled client (Postgres restart, Render maintenance)
  // is an unhandled 'error' event and takes the process down.
  pool.on('error', (error) => {
    scoped.error('idle postgres client error', { error });
  });

  await ensureSchema(pool, scoped);
  return new PostgresStore(pool, scoped);
}

async function ensureSchema(pool: PgPool, logger: Logger): Promise<void> {
  const sql = await readSchemaSql();
  const client = await pool.connect();
  try {
    // Two instances booting together can both run CREATE INDEX IF NOT EXISTS and collide
    // in the system catalogue; serialize the whole file behind one advisory lock.
    await client.query('SELECT pg_advisory_lock($1)', [SCHEMA_LOCK_KEY]);
    await client.query(sql);
    logger.info('schema applied');
  } finally {
    await client.query('SELECT pg_advisory_unlock($1)', [SCHEMA_LOCK_KEY]).catch(() => undefined);
    client.release();
  }
}
