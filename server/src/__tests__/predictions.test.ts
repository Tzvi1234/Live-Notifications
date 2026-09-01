/**
 * The two rules the prediction game rests on, exercised against the store rather than
 * against a route: both are the store's to enforce, precisely so that a handler cannot
 * forget them. The in-memory store is the one a test can run; the Postgres store carries
 * the same two conditions in the SQL of `putPrediction` and `listPredictions`.
 */

import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

import { createMemoryStore } from '../store/memory.js';
import type { Logger } from '../logger.js';
import type { Store } from '../store/index.js';
import { CORRECT_OUTCOME_POINTS, EXACT_SCORE_POINTS } from '../game/scoring.js';

/** The real logger writes JSON to stdout, which would land in the middle of the TAP stream. */
const silentLogger: Logger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
  child: () => silentLogger,
};

const OWNER = 'user_owner';
const FRIEND = 'user_friend';
const FIXTURE = 1234;

const NOW = new Date('2026-05-01T12:00:00Z');
const BEFORE_KICKOFF = new Date('2026-05-01T14:00:00Z');
const AFTER_KICKOFF = new Date('2026-05-01T11:00:00Z');

let store: Store;
let groupId: number;

beforeEach(async () => {
  store = createMemoryStore(silentLogger);
  await store.upsertUser(OWNER, { displayName: 'Owner' });
  await store.upsertUser(FRIEND, { displayName: 'Friend' });
  const group = await store.createGroup({
    name: 'Sunday League',
    ownerId: OWNER,
    inviteCode: 'ABCD2345',
    leagueIds: [39],
    teamIds: [42, 50],
  });
  groupId = group.id;
  await store.addGroupMember(groupId, FRIEND);
});

describe('the kick-off lock', () => {
  test('accepts a prediction before kick-off', async () => {
    const record = await store.putPrediction({
      groupId,
      fixtureId: FIXTURE,
      userId: OWNER,
      home: 2,
      away: 1,
      kickoffAt: BEFORE_KICKOFF,
      now: NOW,
    });
    assert.ok(record);
    assert.equal(record.home, 2);
    assert.equal(record.away, 1);
    // Unscored until the poller settles the fixture.
    assert.equal(record.points, undefined);
  });

  test('refuses a first prediction for a fixture already under way', async () => {
    const record = await store.putPrediction({
      groupId,
      fixtureId: FIXTURE,
      userId: OWNER,
      home: 2,
      away: 1,
      kickoffAt: AFTER_KICKOFF,
      now: NOW,
    });
    // undefined is what the route turns into the 409; nothing was written.
    assert.equal(record, undefined);
    const stored = await store.listPredictions({
      groupId,
      fixtureIds: [FIXTURE],
      viewerId: OWNER,
      now: NOW,
    });
    assert.deepEqual(stored, []);
  });

  test('refuses an EDIT once the fixture has kicked off, keeping the original', async () => {
    await store.putPrediction({
      groupId,
      fixtureId: FIXTURE,
      userId: OWNER,
      home: 2,
      away: 1,
      kickoffAt: BEFORE_KICKOFF,
      now: NOW,
    });

    // Same fixture, same kick-off — but the clock has moved past it.
    const afterKickOff = new Date(BEFORE_KICKOFF.getTime() + 60_000);
    const edited = await store.putPrediction({
      groupId,
      fixtureId: FIXTURE,
      userId: OWNER,
      home: 5,
      away: 0,
      kickoffAt: BEFORE_KICKOFF,
      now: afterKickOff,
    });
    assert.equal(edited, undefined);

    const [stored] = await store.listPredictions({
      groupId,
      fixtureIds: [FIXTURE],
      viewerId: OWNER,
      now: afterKickOff,
    });
    assert.equal(stored?.home, 2);
    assert.equal(stored?.away, 1);
  });

  test('allows an edit while the fixture is still to come', async () => {
    await store.putPrediction({
      groupId,
      fixtureId: FIXTURE,
      userId: OWNER,
      home: 2,
      away: 1,
      kickoffAt: BEFORE_KICKOFF,
      now: NOW,
    });
    const edited = await store.putPrediction({
      groupId,
      fixtureId: FIXTURE,
      userId: OWNER,
      home: 0,
      away: 3,
      kickoffAt: BEFORE_KICKOFF,
      now: NOW,
    });
    assert.equal(edited?.home, 0);
    assert.equal(edited?.away, 3);
  });
});

describe('nobody sees another member’s prediction before kick-off', () => {
  beforeEach(async () => {
    for (const userId of [OWNER, FRIEND]) {
      await store.putPrediction({
        groupId,
        fixtureId: FIXTURE,
        userId,
        home: userId === OWNER ? 2 : 0,
        away: userId === OWNER ? 1 : 4,
        kickoffAt: BEFORE_KICKOFF,
        now: NOW,
      });
    }
  });

  test('a member reads only their own row before kick-off', async () => {
    const visible = await store.listPredictions({
      groupId,
      fixtureIds: [FIXTURE],
      viewerId: OWNER,
      now: NOW,
    });
    assert.equal(visible.length, 1);
    assert.equal(visible[0]?.userId, OWNER);
    assert.equal(visible[0]?.home, 2);
  });

  test('each member sees a different single row, so neither can infer the other', async () => {
    const [mine] = await store.listPredictions({
      groupId,
      fixtureIds: [FIXTURE],
      viewerId: FRIEND,
      now: NOW,
    });
    assert.equal(mine?.userId, FRIEND);
    assert.equal(mine?.away, 4);
  });

  test('every row becomes readable the moment the fixture kicks off', async () => {
    const atKickOff = BEFORE_KICKOFF;
    const visible = await store.listPredictions({
      groupId,
      fixtureIds: [FIXTURE],
      viewerId: OWNER,
      now: atKickOff,
    });
    assert.equal(visible.length, 2);
    assert.deepEqual(
      visible.map((row) => row.userId).sort(),
      [FRIEND, OWNER].sort(),
    );
  });

  test('the rule is per fixture, not per group: a later kick-off stays hidden', async () => {
    const later = 5678;
    const laterKickoff = new Date('2026-05-02T14:00:00Z');
    await store.putPrediction({
      groupId,
      fixtureId: later,
      userId: FRIEND,
      home: 1,
      away: 1,
      kickoffAt: laterKickoff,
      now: NOW,
    });

    const visible = await store.listPredictions({
      groupId,
      fixtureIds: [FIXTURE, later],
      viewerId: OWNER,
      // Past the first fixture's kick-off, well before the second's.
      now: BEFORE_KICKOFF,
    });
    assert.equal(visible.filter((row) => row.fixtureId === FIXTURE).length, 2);
    assert.equal(visible.filter((row) => row.fixtureId === later).length, 0);
  });
});

describe('settlement', () => {
  beforeEach(async () => {
    await store.putPrediction({
      groupId,
      fixtureId: FIXTURE,
      userId: OWNER,
      home: 2,
      away: 1,
      kickoffAt: BEFORE_KICKOFF,
      now: NOW,
    });
    await store.putPrediction({
      groupId,
      fixtureId: FIXTURE,
      userId: FRIEND,
      home: 3,
      away: 0,
      kickoffAt: BEFORE_KICKOFF,
      now: NOW,
    });
  });

  test('the settlement queue holds the fixture only inside the sweep window', async () => {
    const afterTheMatch = new Date(BEFORE_KICKOFF.getTime() + 3 * 3600_000);
    const due = await store.fixturesAwaitingSettlement(
      new Date(afterTheMatch.getTime() - 7 * 86_400_000),
      afterTheMatch,
      10,
    );
    assert.deepEqual(due, [FIXTURE]);

    // Too soon: the grace period puts the upper bound before kick-off.
    const tooSoon = await store.fixturesAwaitingSettlement(
      new Date(NOW.getTime() - 7 * 86_400_000),
      NOW,
      10,
    );
    assert.deepEqual(tooSoon, []);
  });

  test('scores every row against the final result and leaves the queue', async () => {
    const settled = await store.settleFixture(FIXTURE, { home: 2, away: 1 });
    assert.equal(settled, 2);

    const board = await store.leaderboard(groupId);
    const owner = board.find((row) => row.userId === OWNER);
    const friend = board.find((row) => row.userId === FRIEND);
    // The constants rather than literals: these are house rules, and a test that pins the
    // number instead of the rule fails every time somebody tunes the game.
    // Owner called 2-1 on a 2-1: exact. Friend called 3-0: right winner, wrong margin.
    assert.equal(owner?.points, EXACT_SCORE_POINTS);
    assert.equal(owner?.exactCount, 1);
    assert.equal(owner?.correctOutcomeCount, 1);
    assert.equal(friend?.points, CORRECT_OUTCOME_POINTS);
    assert.equal(friend?.exactCount, 0);
    assert.equal(friend?.correctOutcomeCount, 1);
    assert.equal(friend?.settledCount, 1);

    const afterTheMatch = new Date(BEFORE_KICKOFF.getTime() + 3 * 3600_000);
    assert.deepEqual(
      await store.fixturesAwaitingSettlement(
        new Date(afterTheMatch.getTime() - 7 * 86_400_000),
        afterTheMatch,
        10,
      ),
      [],
    );
  });

  test('settling twice does not score a fixture twice', async () => {
    await store.settleFixture(FIXTURE, { home: 2, away: 1 });
    const again = await store.settleFixture(FIXTURE, { home: 9, away: 9 });
    assert.equal(again, 0);
    const board = await store.leaderboard(groupId);
    assert.equal(board.find((row) => row.userId === OWNER)?.points, EXACT_SCORE_POINTS);
  });

  test('a rescheduled fixture re-opens for edits and leaves the queue', async () => {
    const newKickoff = new Date('2026-06-01T14:00:00Z');
    assert.equal(await store.rescheduleFixture(FIXTURE, newKickoff), 2);

    const afterTheOriginalDate = new Date(BEFORE_KICKOFF.getTime() + 3 * 3600_000);
    assert.deepEqual(
      await store.fixturesAwaitingSettlement(
        new Date(afterTheOriginalDate.getTime() - 7 * 86_400_000),
        afterTheOriginalDate,
        10,
      ),
      [],
    );

    const edited = await store.putPrediction({
      groupId,
      fixtureId: FIXTURE,
      userId: OWNER,
      home: 1,
      away: 1,
      kickoffAt: newKickoff,
      now: afterTheOriginalDate,
    });
    assert.equal(edited?.home, 1);
  });
});
