import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { ApiFootballClient } from '../provider/apiFootball.js';
import { RateLimiter } from '../provider/rateLimiter.js';
import {
  createPersistentCache,
  type CacheQueryable,
  type CachedResponse,
} from '../provider/persistentCache.js';

/** A clock and a timer the test drives, so pacing is checked without waiting for it. */
function fakeClock() {
  let now = 0;
  const sleeps: number[] = [];
  return {
    now: () => now,
    advance: (ms: number) => {
      now += ms;
    },
    sleeps,
    // Every sleep moves the clock by exactly what was asked for, which is what a perfect
    // timer would do and is enough to prove the pacing arithmetic.
    sleep: async (ms: number) => {
      sleeps.push(ms);
      now += ms;
    },
  };
}

describe('RateLimiter', () => {
  test('a burst inside the allowance goes straight through', async () => {
    const clock = fakeClock();
    // 100/min at the default 80% headroom = 80 tokens.
    const limiter = new RateLimiter({ perMinute: 100, now: clock.now, sleep: clock.sleep });

    for (let i = 0; i < 80; i += 1) await limiter.acquire();
    assert.equal(clock.sleeps.length, 0, 'nothing should have waited');
    assert.equal(limiter.getState().available, 0);
  });

  test('the eighty-first caller waits rather than being refused', async () => {
    const clock = fakeClock();
    const limiter = new RateLimiter({ perMinute: 100, now: clock.now, sleep: clock.sleep });

    for (let i = 0; i < 80; i += 1) await limiter.acquire();
    await limiter.acquire();

    // This is the whole point of the bucket: over-asking costs latency, never a 429. The
    // old design only learned the window was tight from a response already issued, by
    // which time the burst that filled it had happened.
    assert.equal(clock.sleeps.length, 1);
    assert.equal(clock.sleeps[0], Math.ceil(60_000 / 80));
  });

  test('tokens come back continuously, not in windows', async () => {
    const clock = fakeClock();
    const limiter = new RateLimiter({ perMinute: 100, now: clock.now, sleep: clock.sleep });

    for (let i = 0; i < 80; i += 1) await limiter.acquire();
    assert.equal(limiter.getState().available, 0);

    // Half a minute back is half the allowance back, not "wait for the window to flip".
    clock.advance(30_000);
    assert.equal(limiter.getState().available, 40);
  });

  test('an upgraded plan widens the bucket at once', async () => {
    const clock = fakeClock();
    const limiter = new RateLimiter({ perMinute: 300, now: clock.now, sleep: clock.sleep });
    assert.equal(limiter.getState().perMinute, 240);

    // The provider states 450/min on the Ultra tier. Waiting a full minute to believe it
    // would throttle the plan the user just paid for.
    limiter.observeLimit(450);
    assert.equal(limiter.getState().perMinute, 360);
    assert.equal(limiter.getState().available, 360);
  });

  test('a narrower limit never deadlocks', async () => {
    const clock = fakeClock();
    const limiter = new RateLimiter({ perMinute: 300, now: clock.now, sleep: clock.sleep });
    limiter.observeLimit(1);
    assert.ok(limiter.getState().perMinute >= 1);
    await limiter.acquire();
  });

  test('one caller failing does not poison the queue behind it', async () => {
    const clock = fakeClock();
    const limiter = new RateLimiter({ perMinute: 60, now: clock.now, sleep: clock.sleep });
    // Callers chain onto each other; if a rejection propagated along the chain, every
    // later request in the process would fail with somebody else's error.
    const all = await Promise.allSettled([
      limiter.acquire(),
      limiter.acquire(),
      limiter.acquire(),
    ]);
    assert.ok(all.every((r) => r.status === 'fulfilled'));
  });
});

describe('the limiter paces real provider calls', () => {
  test('a burst of calls is spread rather than issued at once', async () => {
    const clock = fakeClock();
    let calls = 0;
    const client = new ApiFootballClient({
      apiKey: 'k',
      now: clock.now,
      limiter: new RateLimiter({ perMinute: 10, now: clock.now, sleep: clock.sleep }),
      fetchImpl: (async () => {
        calls += 1;
        return new Response(JSON.stringify({ errors: [], response: [{ fixture: { id: calls } }] }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        });
      }) as unknown as typeof fetch,
    });

    // 10/min at 80% = 8 tokens. Twelve distinct live calls must therefore wait four times.
    for (let i = 0; i < 12; i += 1) await client.liveFixtures([i]);
    assert.equal(calls, 12);
    assert.equal(clock.sleeps.length, 4);
  });
});

describe('persistent cache', () => {
  /** A stand-in for `pg`, so the SQL shape is exercised without a database. */
  function fakeDb() {
    const rows = new Map<string, { payload: unknown; expires_at: string; stale_until: string }>();
    let failing = false;
    const db: CacheQueryable = {
      async query<T>(text: string, values: readonly unknown[] = []) {
        if (failing) throw new Error('connection terminated');
        if (text.startsWith('SELECT')) {
          const row = rows.get(String(values[0]));
          return { rows: (row ? [row] : []) as T[], rowCount: row ? 1 : 0 };
        }
        if (text.startsWith('INSERT')) {
          rows.set(String(values[0]), {
            payload: JSON.parse(String(values[1])),
            expires_at: String(values[2]),
            stale_until: String(values[3]),
          });
          return { rows: [] as T[], rowCount: 1 };
        }
        if (text.startsWith('DELETE')) {
          const cutoff = new Date(String(values[0])).getTime();
          let gone = 0;
          for (const [key, row] of rows) {
            if (new Date(row.stale_until).getTime() <= cutoff) {
              rows.delete(key);
              gone += 1;
            }
          }
          return { rows: [] as T[], rowCount: gone };
        }
        throw new Error(`unexpected query: ${text}`);
      },
    };
    return { db, rows, fail: (on: boolean) => (failing = on) };
  }

  const warnings: string[] = [];
  const logger = { warn: (m: string) => warnings.push(m) };

  test('what one instance fetched, the next one reads', async () => {
    const { db } = fakeDb();
    const cache = createPersistentCache(db, logger);
    const entry: CachedResponse = {
      value: [{ team: { id: 42 } }],
      expiresAt: 10_000,
      staleUntil: 20_000,
    };

    await cache.write('/teams?league=39', entry);
    const read = await cache.read('/teams?league=39');
    assert.deepEqual(read?.value, entry.value);
    assert.equal(read?.expiresAt, 10_000);
  });

  test('a missing key is a miss, not an error', async () => {
    const { db } = fakeDb();
    const cache = createPersistentCache(db, logger);
    assert.equal(await cache.read('/nothing'), undefined);
  });

  test('a database that has gone away degrades to a miss', async () => {
    // A cache that cannot reach its store must cost a request, never a request handler.
    const { db, fail } = fakeDb();
    const cache = createPersistentCache(db, logger);
    fail(true);

    assert.equal(await cache.read('/teams?league=39'), undefined);
    await cache.write('/teams?league=39', { value: [], expiresAt: 1, staleUntil: 2 });
    assert.equal(await cache.sweep(0), 0);
  });

  test('the sweep only takes what is past its grace', async () => {
    const { db, rows } = fakeDb();
    const cache = createPersistentCache(db, logger);
    await cache.write('old', { value: [1], expiresAt: 100, staleUntil: 200 });
    await cache.write('fresh', { value: [2], expiresAt: 100, staleUntil: 10_000 });

    assert.equal(await cache.sweep(5_000), 1);
    assert.equal(rows.has('old'), false);
    assert.equal(rows.has('fresh'), true);
  });
});

describe('the two cache halves work as one', () => {
  test('a restarted process answers from the database without an upstream call', async () => {
    const shared = new Map<string, { payload: unknown; expires_at: string; stale_until: string }>();
    const db: CacheQueryable = {
      async query<T>(text: string, values: readonly unknown[] = []) {
        if (text.startsWith('SELECT')) {
          const row = shared.get(String(values[0]));
          return { rows: (row ? [row] : []) as T[], rowCount: row ? 1 : 0 };
        }
        shared.set(String(values[0]), {
          payload: JSON.parse(String(values[1])),
          expires_at: String(values[2]),
          stale_until: String(values[3]),
        });
        return { rows: [] as T[], rowCount: 1 };
      },
    };
    const cache = createPersistentCache(db, { warn: () => {} });

    let calls = 0;
    const fetchImpl = (async () => {
      calls += 1;
      return new Response(JSON.stringify({ errors: [], response: [{ team: { id: 33 } }] }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    }) as unknown as typeof fetch;

    const first = new ApiFootballClient({ apiKey: 'k', fetchImpl, now: () => 0 });
    first.usePersistentCache(cache);
    await first.teams({ league: 39, season: 2026 });
    assert.equal(calls, 1);

    // A deploy: new process, empty Map, same database. This is the case that was costing a
    // fresh upstream call per competition several times a day.
    const second = new ApiFootballClient({ apiKey: 'k', fetchImpl, now: () => 1_000 });
    second.usePersistentCache(cache);
    const teams = await second.teams({ league: 39, season: 2026 });

    assert.equal(calls, 1, 'the second process must not call upstream');
    assert.equal(teams.length, 1);
  });
});
