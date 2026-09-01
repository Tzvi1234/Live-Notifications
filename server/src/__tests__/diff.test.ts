import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { diffMatch, SYNTHETIC_MINUTES } from '../poller/diff.js';
import { eventKey } from '../provider/mapper.js';
import type { MatchEventJson, MatchJson, TrackedMatchState } from '../types.js';

const HOME = 42;
const AWAY = 77;

function match(overrides: Partial<MatchJson> = {}): MatchJson {
  return {
    id: 1001,
    leagueId: 39,
    leagueName: 'Premier League',
    kickoffAt: 1_756_000_000,
    phase: 'SECOND_HALF',
    elapsed: 67,
    home: { id: HOME, name: 'Arsenal', shortName: 'ARS' },
    away: { id: AWAY, name: 'Chelsea', shortName: 'CHE' },
    score: { home: 2, away: 1 },
    ...overrides,
  };
}

function state(overrides: Partial<TrackedMatchState> = {}): TrackedMatchState {
  return {
    matchId: 1001,
    phase: 'SECOND_HALF',
    score: { home: 2, away: 1 },
    elapsed: 60,
    lastSequence: 3,
    sentEventIds: new Set<string>(),
    lineupsSent: false,
    ...overrides,
  };
}

function goal(minute: number, team: number, player: string): MatchEventJson {
  return {
    id: eventKey(1001, 'GOAL', minute, team, player),
    type: 'GOAL',
    side: team === HOME ? 'HOME' : 'AWAY',
    teamId: team,
    minute,
    player,
    scoreAfter: { home: 1, away: 0 },
  };
}

describe('diffMatch', () => {
  test('an event already sent is never reported as new again', () => {
    const scored = goal(23, HOME, 'Saka');
    const diff = diffMatch(
      state({ sentEventIds: new Set([scored.id]) }),
      match(),
      [scored],
    );
    assert.equal(diff.newEvents.length, 0);
  });

  test('a genuinely new event is reported once', () => {
    const scored = goal(23, HOME, 'Saka');
    const diff = diffMatch(state(), match(), [scored]);
    assert.deepEqual(diff.newEvents.map((e) => e.id), [scored.id]);
  });

  test('an incident listed twice in one feed is only reported once', () => {
    // The provider does duplicate an incident across feeds; both copies derive the same
    // id, and only one may reach the push path.
    const scored = goal(23, HOME, 'Saka');
    const diff = diffMatch(state(), match(), [scored, { ...scored }]);
    assert.equal(diff.newEvents.length, 1);
  });

  test('the first sighting of a match reports no transitions', () => {
    // Otherwise a match first seen at 70 minutes would invent a kick-off.
    const diff = diffMatch(undefined, match(), [goal(23, HOME, 'Saka')]);
    assert.equal(diff.phaseChanged, false);
    assert.equal(diff.scoreChanged, false);
    assert.deepEqual(diff.syntheticEvents, []);
  });

  test('kick-off is synthesised from the phase transition', () => {
    const diff = diffMatch(
      state({ phase: 'SCHEDULED', score: { home: 0, away: 0 }, elapsed: undefined }),
      match({ phase: 'FIRST_HALF', elapsed: 1, score: { home: 0, away: 0 } }),
      [],
    );
    assert.equal(diff.phaseChanged, true);
    const kickOff = diff.syntheticEvents.find((e) => e.type === 'KICK_OFF');
    assert.ok(kickOff, 'expected a synthetic KICK_OFF');
    assert.equal(kickOff?.minute, SYNTHETIC_MINUTES.KICK_OFF);
  });

  test('full time is synthesised when the match finishes', () => {
    const diff = diffMatch(
      state({ phase: 'SECOND_HALF' }),
      match({ phase: 'FINISHED', elapsed: 90 }),
      [],
    );
    const fullTime = diff.syntheticEvents.find((e) => e.type === 'FULL_TIME');
    assert.ok(fullTime, 'expected a synthetic FULL_TIME');
    assert.equal(fullTime?.minute, SYNTHETIC_MINUTES.FULL_TIME);
  });

  test('synthetic ids are stable, so two pollers cannot both push a kick-off', () => {
    const args = [
      state({ phase: 'SCHEDULED', score: { home: 0, away: 0 } }),
      match({ phase: 'FIRST_HALF', elapsed: 1, score: { home: 0, away: 0 } }),
      [],
    ] as const;
    const a = diffMatch(...args).syntheticEvents.map((e) => e.id);
    const b = diffMatch(...args).syntheticEvents.map((e) => e.id);
    assert.deepEqual(a, b);
  });

  test('a goal disallowed on review becomes a VAR correction', () => {
    const scored = goal(23, HOME, 'Saka');
    const diff = diffMatch(
      state({ score: { home: 1, away: 0 }, sentEventIds: new Set([scored.id]) }),
      // The goal is gone from the feed AND the scoreline has dropped.
      match({ score: { home: 0, away: 0 } }),
      [goal(55, AWAY, 'Someone')],
    );
    const reversal = diff.syntheticEvents.find((e) => e.type === 'VAR');
    assert.ok(reversal, 'expected a VAR retraction');
    assert.equal(reversal?.minute, 23);
    assert.equal(reversal?.player, 'Saka');
  });

  test('a scoreline that drops with nothing missing is not called a retraction', () => {
    // A dip with the event still listed is a raced fetch, not a reversal.
    const scored = goal(23, HOME, 'Saka');
    const diff = diffMatch(
      state({ score: { home: 1, away: 0 }, sentEventIds: new Set([scored.id]) }),
      match({ score: { home: 0, away: 0 } }),
      [scored],
    );
    assert.equal(diff.syntheticEvents.filter((e) => e.type === 'VAR').length, 0);
  });

  test('an empty feed is a thin fetch, not a match wiped clean', () => {
    const scored = goal(23, HOME, 'Saka');
    const diff = diffMatch(
      state({ score: { home: 1, away: 0 }, sentEventIds: new Set([scored.id]) }),
      match({ score: { home: 0, away: 0 } }),
      [],
    );
    assert.equal(diff.syntheticEvents.filter((e) => e.type === 'VAR').length, 0);
  });

  test('a score change is reported', () => {
    const diff = diffMatch(state({ score: { home: 1, away: 1 } }), match(), []);
    assert.equal(diff.scoreChanged, true);
  });
});
