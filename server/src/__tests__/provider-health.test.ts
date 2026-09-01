import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import {
  ApiFootballClient,
  ProviderError,
  classifyProviderProblem,
  publicFaultReason,
} from '../provider/apiFootball.js';

const KEY = 'test-key';

/** A minute, so a TTL of one second is expirable by moving the clock a little. */
const TTL_SECONDS = 1;

interface FakeResponse {
  status?: number;
  headers?: Record<string, string>;
  body?: unknown;
  /** Set to throw at the transport layer instead of answering. */
  throws?: Error;
}

/** Answers each call from a queue, so a success can be followed by a failure. */
function fakeFetch(queue: FakeResponse[]): { impl: typeof fetch; calls: () => number } {
  let calls = 0;
  const impl = (async () => {
    const next = queue[Math.min(calls, queue.length - 1)];
    calls += 1;
    if (next === undefined) throw new Error('no response queued');
    if (next.throws) throw next.throws;
    return new Response(JSON.stringify(next.body ?? { errors: [], response: [] }), {
      status: next.status ?? 200,
      headers: { 'content-type': 'application/json', ...(next.headers ?? {}) },
    });
  }) as unknown as typeof fetch;
  return { impl, calls: () => calls };
}

function clientFor(queue: FakeResponse[], now: () => number) {
  const fetched = fakeFetch(queue);
  const client = new ApiFootballClient({
    apiKey: KEY,
    cacheTtlSeconds: TTL_SECONDS,
    fetchImpl: fetched.impl,
    now,
  });
  return { client, calls: fetched.calls };
}

describe('classifyProviderProblem', () => {
  test('an HTTP 403 is a rejected key, whatever the body says', () => {
    // This is the shape API-Football answers with when the key header is missing or wrong,
    // and it carries no rate-limit headers - which is why the quota counters look frozen
    // while every endpoint fails.
    assert.equal(classifyProviderProblem(403, undefined), 'auth');
    assert.equal(classifyProviderProblem(401, 'anything'), 'auth');
  });

  test('the envelope names the same failures at HTTP 200', () => {
    // The provider reports auth and plan problems inside a 200 body as often as it does
    // with a status code, so both spellings have to be recognised.
    assert.equal(
      classifyProviderProblem(200, 'token: Missing application key, Check our documentation'),
      'auth',
    );
    assert.equal(
      classifyProviderProblem(200, 'requests: Your account is not subscribed to this plan.'),
      'plan',
    );
  });

  test('a 429 is the one fault that fixes itself', () => {
    assert.equal(classifyProviderProblem(429, undefined), 'rate-limited');
  });

  test('anything else is just the provider having a bad minute', () => {
    assert.equal(classifyProviderProblem(503, 'gateway'), 'upstream');
  });

  test('every kind has a sentence an operator can act on', () => {
    for (const kind of ['transport', 'auth', 'plan', 'rate-limited', 'upstream', 'malformed'] as const) {
      const reason = publicFaultReason(kind);
      assert.ok(reason.length > 0, `${kind} has no reason`);
      // The provider's own words name the account's state; ours must not repeat them.
      assert.ok(!reason.includes('subscribed to this plan'));
    }
  });
});

describe('provider health', () => {
  test('a rejected key is reported, not swallowed', async () => {
    const { client } = clientFor([{ status: 403, body: { errors: { token: 'Missing application key' } } }], () => 0);

    await assert.rejects(() => client.leagues(true), ProviderError);

    const health = client.getHealth();
    assert.equal(health.reachable, false);
    assert.equal(health.lastFault?.kind, 'auth');
    assert.equal(health.lastFault?.status, 403);
    // The operator gets the provider's own sentence; the public surface gets the kind.
    assert.match(String(health.lastFault?.detail), /application key/);
  });

  test('a later success clears the fault', async () => {
    let now = 0;
    const { client } = clientFor(
      [
        { status: 403 },
        { status: 200, body: { errors: [], response: [{ league: { id: 39 } }] } },
      ],
      () => now,
    );

    await assert.rejects(() => client.leagues(true));
    assert.equal(client.getHealth().reachable, false);

    // Past the TTL, so the second call actually goes out rather than reading the cache.
    now += 5_000;
    await client.leagues(true);
    assert.equal(client.getHealth().reachable, true);
  });

  test('a transport failure is told apart from a rejection', async () => {
    const { client } = clientFor([{ throws: new TypeError('fetch failed') }], () => 0);
    await assert.rejects(() => client.leagues(true));
    assert.equal(client.getHealth().lastFault?.kind, 'transport');
  });
});

describe('stale-while-error', () => {
  // A day's fixture list, because it runs on the client's general TTL. The catalogue calls
  // deliberately cache for half a day and would need the clock moved that far to expire.
  const DAY = { date: '2026-09-01' };

  test('an expired answer is served when the refresh fails', async () => {
    let now = 0;
    const { client, calls } = clientFor(
      [
        { status: 200, body: { errors: [], response: [{ fixture: { id: 7 } }] } },
        { status: 403, body: { errors: { token: 'Token is invalid' } } },
      ],
      () => now,
    );

    const first = await client.fixtures(DAY);
    assert.equal(first.length, 1);
    assert.equal(calls(), 1);

    // Inside the TTL nothing goes out at all.
    await client.fixtures(DAY);
    assert.equal(calls(), 1);

    // Past the TTL the refresh runs, and fails. A slightly old list is a far better answer
    // than an error screen.
    now += 2_000;
    const stale = await client.fixtures(DAY);
    assert.equal(calls(), 2);
    assert.deepEqual(stale, first);
  });

  test('the grace window is not forever', async () => {
    let now = 0;
    const { client } = clientFor(
      [
        { status: 200, body: { errors: [], response: [{ fixture: { id: 7 } }] } },
        { status: 403 },
      ],
      () => now,
    );

    await client.fixtures(DAY);
    // Seven hours: past both the TTL and the six-hour grace, so there is nothing left to
    // serve and the caller is told the truth instead.
    now += 7 * 60 * 60 * 1000;
    await assert.rejects(() => client.fixtures(DAY), ProviderError);
  });

  test('nothing stale is served for a call that was never cacheable', async () => {
    // Live fixtures are never cached, so an outage there must surface rather than replay
    // a scoreline from before it started.
    const { client } = clientFor([{ status: 403 }], () => 0);
    await assert.rejects(() => client.liveFixtures(), ProviderError);
  });
});

describe('accountStatus', () => {
  test('reads the object envelope /status answers with', async () => {
    // Every other endpoint returns a list. This one returns an object, and the list-shaped
    // wrapper would turn the only endpoint that can explain an outage into an empty array.
    const { client } = clientFor(
      [
        {
          status: 200,
          body: {
            errors: [],
            response: {
              account: { firstname: 'T', email: 'someone@example.com' },
              subscription: { plan: 'Pro', end: '2027-01-01', active: true },
              requests: { current: 12, limit_day: 7500 },
            },
          },
        },
      ],
      () => 0,
    );

    const status = await client.accountStatus();
    assert.equal(status?.subscription?.active, true);
    assert.equal(status?.subscription?.plan, 'Pro');
    assert.equal(status?.requests?.limit_day, 7500);
  });

  test('says plainly when the plan is not active', async () => {
    const { client } = clientFor(
      [
        {
          status: 200,
          body: { errors: [], response: { subscription: { plan: 'Free', active: false } } },
        },
      ],
      () => 0,
    );

    const status = await client.accountStatus();
    assert.equal(status?.subscription?.active, false);
  });
});
