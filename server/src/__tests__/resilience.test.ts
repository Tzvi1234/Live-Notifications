import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { createMemoryStore } from '../store/memory.js';
import type { Store } from '../store/index.js';
import { classifyConnectionFault, withDatabaseFallback } from '../store/resilience.js';
import { createLogger } from '../logger.js';

const silent = createLogger({ level: 'error' });

function pgError(code: string): Error {
  return Object.assign(new Error(`boom ${code}`), { code });
}

describe('classifyConnectionFault', () => {
  it('treats a vanished database as unreachable', () => {
    // The exact failure the live service hit: Render deletes a free Postgres after 30
    // days and the hostname simply stops resolving.
    assert.equal(classifyConnectionFault(pgError('ENOTFOUND')), 'unreachable');
    assert.equal(classifyConnectionFault(pgError('ECONNREFUSED')), 'unreachable');
    assert.equal(classifyConnectionFault(pgError('3D000')), 'unreachable');
  });

  it('keeps a refusal distinct from an absence', () => {
    // A wrong password must never be papered over: the service would look healthy while
    // quietly persisting nothing.
    assert.equal(classifyConnectionFault(pgError('28P01')), 'rejected');
    assert.equal(classifyConnectionFault(new Error('no pg_hba.conf entry for host')), 'rejected');
  });

  it('does not claim a bug is a connection problem', () => {
    assert.equal(classifyConnectionFault(new Error('syntax error at or near')), 'other');
    assert.equal(classifyConnectionFault(pgError('42703')), 'other');
    assert.equal(classifyConnectionFault(undefined), 'other');
  });
});

describe('withDatabaseFallback', () => {
  function failingStore(error: Error): Store {
    const base = createMemoryStore(silent);
    return new Proxy(base, {
      get(_t, property) {
        if (property === 'kind') return 'postgres';
        const value = Reflect.get(base, property, base);
        if (typeof value !== 'function') return value;
        return () => Promise.reject(error);
      },
    }) as Store;
  }

  it('gives up only after several failures in a row', async () => {
    const store = withDatabaseFallback(
      failingStore(pgError('ENOTFOUND')),
      () => createMemoryStore(silent),
      silent,
    );

    // A single reset during a maintenance window recovers on its own and must not cost
    // the process its database.
    await assert.rejects(() => store.getMatchState(1));
    assert.equal(store.kind, 'postgres');
    await assert.rejects(() => store.getMatchState(1));
    assert.equal(store.kind, 'postgres');

    // The third failure is a pattern. The call that discovers it still gets an answer.
    assert.equal(await store.getMatchState(1), undefined);
    assert.equal(store.kind, 'memory');
    assert.equal(await store.getMatchState(2), undefined);
  });

  it('never fails over on an error that is not about connectivity', async () => {
    const store = withDatabaseFallback(
      failingStore(pgError('42703')),
      () => createMemoryStore(silent),
      silent,
    );
    for (let attempt = 0; attempt < 5; attempt += 1) {
      await assert.rejects(() => store.getMatchState(1));
    }
    // A column that does not exist is a bug, and hiding it behind a memory store would
    // turn a loud deploy failure into a silent data-loss one.
    assert.equal(store.kind, 'postgres');
  });

  it('leaves a healthy store alone', async () => {
    const healthy = createMemoryStore(silent);
    const store = withDatabaseFallback(healthy, () => createMemoryStore(silent), silent);
    await store.putMatchState({
      matchId: 7,
      phase: 'FIRST_HALF',
      lastSequence: 3,
      sentEventIds: new Set<string>(),
      lineupsSent: false,
    });
    assert.equal((await store.getMatchState(7))?.lastSequence, 3);
  });
});
