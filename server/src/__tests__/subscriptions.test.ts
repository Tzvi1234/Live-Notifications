import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import express from 'express';
import type { AddressInfo } from 'node:net';

import { createSubscriptionsRouter } from '../routes/subscriptions.js';
import type { ApiDeps } from '../routes/deps.js';
import { createMemoryStore } from '../store/memory.js';
import { createLogger } from '../logger.js';
import type { Store } from '../store/index.js';

const silent = createLogger({ level: 'error' });

/** The router only ever touches `store` and `logger`; the rest of ApiDeps is scenery. */
function deps(store: Store): ApiDeps {
  return { store, logger: silent } as unknown as ApiDeps;
}

async function withServer(
  store: Store,
  run: (base: string) => Promise<void>,
): Promise<void> {
  const app = express();
  app.use(express.json());
  app.use('/v1', createSubscriptionsRouter(deps(store)));
  const server = app.listen(0);
  await new Promise<void>((resolve) => server.once('listening', resolve));
  try {
    await run(`http://127.0.0.1:${(server.address() as AddressInfo).port}/v1`);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}

const TOKEN = 'a'.repeat(64);

function put(base: string, body: unknown): Promise<Response> {
  return fetch(`${base}/subscriptions`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
}

describe('PUT /v1/subscriptions', () => {
  it('keeps a handful of deliberately chosen competitions', async () => {
    const store = createMemoryStore(silent);
    await withServer(store, async (base) => {
      const response = await put(base, { token: TOKEN, teamIds: [42], leagueIds: [39, 140] });
      assert.equal(response.status, 204);
      assert.deepEqual((await store.getSubscription(TOKEN))?.leagueIds, [39, 140]);
    });
  });

  it('drops a whole catalogue uploaded as if it were a preference', async () => {
    // THE bug behind the notification storm. The app wrote every competition it offers
    // for browsing into this field, and `tokensForMatch` matches on `league_ids &&`, so
    // the phone was woken for every goal, card, substitution, kick-off and full time in
    // all thirty-one of them. The app no longer sends leagues at all - this is the guard
    // for a phone still running the old build, which re-uploads the catalogue on launch.
    const store = createMemoryStore(silent);
    const catalogue = Array.from({ length: 31 }, (_, index) => index + 1);
    await withServer(store, async (base) => {
      const response = await put(base, { token: TOKEN, teamIds: [42], leagueIds: catalogue });
      // Not an error: the device is an old build doing what it was told, and a 4xx would
      // only make it retry. It is accepted, and the league list is thrown away.
      assert.equal(response.status, 204);
      const stored = await store.getSubscription(TOKEN);
      assert.deepEqual(stored?.leagueIds, []);
      // The teams are the real subscription and must survive untouched.
      assert.deepEqual(stored?.teamIds, [42]);
    });
  });

  it('drops the list whole rather than keeping an arbitrary slice', async () => {
    const store = createMemoryStore(silent);
    await withServer(store, async (base) => {
      await put(base, { token: TOKEN, teamIds: [], leagueIds: [1, 2, 3, 4, 5, 6] });
      // Truncating to the first five would leave a subscription nobody asked for and
      // would look, from the shelf, exactly like the bug.
      assert.deepEqual((await store.getSubscription(TOKEN))?.leagueIds, []);
    });
  });
});
