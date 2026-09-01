/**
 * The second half of the provider cache: the half that survives a restart.
 *
 * The client's in-memory `Map` is fast and is the right first stop, but Render replaces the
 * instance on every deploy and spins it down when idle, so it is cold several times a day.
 * Cold costs requests, not just latency: the team list for thirty competitions is thirty
 * upstream calls that were already made yesterday and answered identically.
 *
 * Deliberately a NARROW interface rather than the Store: this is a cache, and a cache that
 * cannot reach its backing store must degrade to "miss", never to "fail". Every method here
 * swallows its errors for that reason - a database hiccup has to cost a request, not a
 * request handler.
 */

export interface CachedResponse {
  value: unknown[];
  expiresAt: number;
  staleUntil: number;
}

export interface PersistentCache {
  read(key: string): Promise<CachedResponse | undefined>;
  write(key: string, entry: CachedResponse): Promise<void>;
  /** Removes everything past its grace window. Returns how many rows went. */
  sweep(now: number): Promise<number>;
}

export interface CacheLogger {
  warn: (message: string, meta?: Record<string, unknown>) => void;
}

/** What this needs from a database handle, which is far less than the Store exposes. */
export interface CacheQueryable {
  query<T = unknown>(
    text: string,
    values?: readonly unknown[],
  ): Promise<{ rows: T[]; rowCount: number | null }>;
}

interface CacheRow {
  payload: unknown;
  expires_at: Date | string;
  stale_until: Date | string;
}

/** A cache for a deployment with no database. Every read misses; nothing breaks. */
export const NO_PERSISTENT_CACHE: PersistentCache = {
  read: async () => undefined,
  write: async () => {},
  sweep: async () => 0,
};

export function createPersistentCache(db: CacheQueryable, logger: CacheLogger): PersistentCache {
  /**
   * Logged once per kind of failure rather than per call.
   *
   * A cache whose database has gone away would otherwise write a line for every request,
   * which buries the one line that says the database went away.
   */
  const complained = new Set<string>();
  const complain = (what: string, error: unknown): void => {
    if (complained.has(what)) return;
    complained.add(what);
    logger.warn(`response cache: ${what} failed; continuing without it`, {
      error: error instanceof Error ? error.message : String(error),
    });
  };

  return {
    async read(key: string): Promise<CachedResponse | undefined> {
      try {
        const result = await db.query<CacheRow>(
          'SELECT payload, expires_at, stale_until FROM response_cache WHERE cache_key = $1',
          [key],
        );
        const row = result.rows[0];
        if (row === undefined) return undefined;
        // The column is JSONB and every writer stores an array, but a hand-edited row or a
        // schema from a future version must not crash a request handler.
        if (!Array.isArray(row.payload)) return undefined;
        return {
          value: row.payload,
          expiresAt: toMillis(row.expires_at),
          staleUntil: toMillis(row.stale_until),
        };
      } catch (error) {
        complain('read', error);
        return undefined;
      }
    },

    async write(key: string, entry: CachedResponse): Promise<void> {
      try {
        await db.query(
          `INSERT INTO response_cache (cache_key, payload, expires_at, stale_until, written_at)
           VALUES ($1, $2::jsonb, $3, $4, now())
           ON CONFLICT (cache_key) DO UPDATE
             SET payload     = EXCLUDED.payload,
                 expires_at  = EXCLUDED.expires_at,
                 stale_until = EXCLUDED.stale_until,
                 written_at  = now()`,
          [
            key,
            JSON.stringify(entry.value),
            new Date(entry.expiresAt).toISOString(),
            new Date(entry.staleUntil).toISOString(),
          ],
        );
      } catch (error) {
        complain('write', error);
      }
    },

    async sweep(now: number): Promise<number> {
      try {
        const result = await db.query('DELETE FROM response_cache WHERE stale_until <= $1', [
          new Date(now).toISOString(),
        ]);
        return result.rowCount ?? 0;
      } catch (error) {
        complain('sweep', error);
        return 0;
      }
    },
  };
}

/** `pg` returns TIMESTAMPTZ as a Date, but a stubbed or JSON-serialised row may not. */
function toMillis(value: Date | string): number {
  return value instanceof Date ? value.getTime() : new Date(value).getTime();
}
