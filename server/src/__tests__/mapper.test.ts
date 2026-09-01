import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  currentSeason,
  eventKey,
  toEvents,
  toMatch,
  toPhase,
} from '../provider/mapper.js';
import type { ApiEvent, ApiFixture } from '../provider/apiFootball.js';

const HOME = 42;
const AWAY = 77;

describe('toPhase', () => {
  test('maps every provider status code the app cares about', () => {
    assert.equal(toPhase('NS'), 'SCHEDULED');
    assert.equal(toPhase('1H'), 'FIRST_HALF');
    assert.equal(toPhase('HT'), 'HALF_TIME');
    assert.equal(toPhase('2H'), 'SECOND_HALF');
    assert.equal(toPhase('ET'), 'EXTRA_TIME');
    assert.equal(toPhase('P'), 'PENALTIES');
    assert.equal(toPhase('FT'), 'FINISHED');
    assert.equal(toPhase('AET'), 'FINISHED');
    assert.equal(toPhase('PEN'), 'FINISHED');
    assert.equal(toPhase('PST'), 'OFF');
    assert.equal(toPhase('CANC'), 'OFF');
  });

  test('never throws on an unknown or absent code', () => {
    assert.equal(toPhase('WHAT'), 'UNKNOWN');
    assert.equal(toPhase(undefined), 'UNKNOWN');
    assert.equal(toPhase(null), 'UNKNOWN');
  });
});

describe('currentSeason', () => {
  test('labels a season by the calendar year it started in', () => {
    // A season runs August to May, so anything before July belongs to the previous
    // label. Getting this wrong silently returns an empty fixture list.
    assert.equal(currentSeason(new Date('2026-08-15T00:00:00Z')), 2026);
    assert.equal(currentSeason(new Date('2026-07-01T00:00:00Z')), 2026);
    assert.equal(currentSeason(new Date('2026-06-30T00:00:00Z')), 2025);
    assert.equal(currentSeason(new Date('2026-03-15T00:00:00Z')), 2025);
  });
});

describe('eventKey', () => {
  test('is stable for the same incident', () => {
    assert.equal(
      eventKey(99, 'GOAL', 67, HOME, 'Saka'),
      eventKey(99, 'GOAL', 67, HOME, 'Saka'),
    );
  });

  test('uses -1 placeholders so a missing field cannot collide with a real one', () => {
    assert.equal(eventKey(1, 'VAR', undefined, undefined, undefined), '1:VAR:-1:-1:');
  });

  test('matches the format the Android client derives independently', () => {
    assert.equal(eventKey(1234, 'GOAL', 67, 42, 'Saka'), '1234:GOAL:67:42:Saka');
  });
});

function fixture(overrides: Partial<ApiFixture> = {}): ApiFixture {
  return {
    fixture: {
      id: 1001,
      timestamp: 1_756_000_000,
      status: { short: '2H', elapsed: 67, extra: null },
      venue: { name: 'Emirates Stadium' },
      referee: 'M. Oliver',
    },
    league: {
      id: 39,
      name: 'Premier League',
      logo: 'https://media.api-sports.io/football/leagues/39.png',
      season: 2026,
      round: 'Regular Season - 4',
    },
    teams: {
      home: { id: HOME, name: 'Arsenal', logo: null },
      away: { id: AWAY, name: 'Chelsea', logo: null },
    },
    goals: { home: 2, away: 1 },
    ...overrides,
  } as ApiFixture;
}

describe('toMatch', () => {
  test('emits kickoffAt in SECONDS, which is what the client parses', () => {
    // Milliseconds here would put every fixture 55,000 years in the future.
    assert.equal(toMatch(fixture()).kickoffAt, 1_756_000_000);
  });

  test('carries the provider clock rather than deriving it', () => {
    const match = toMatch(fixture());
    assert.equal(match.elapsed, 67);
    assert.equal(match.phase, 'SECOND_HALF');
  });

  test('a goalless fixture still reports a scoreline of 0-0, not undefined', () => {
    const match = toMatch(fixture({ goals: { home: 0, away: 0 } } as Partial<ApiFixture>));
    assert.deepEqual(match.score, { home: 0, away: 0 });
  });
});

function goal(team: number, minute: number, player: string, detail = 'Normal Goal'): ApiEvent {
  return {
    time: { elapsed: minute, extra: null },
    team: { id: team, name: `Team ${team}` },
    player: { id: 1, name: player },
    assist: { id: null, name: null },
    type: 'Goal',
    detail,
    comments: null,
  } as ApiEvent;
}

describe('toEvents', () => {
  test('accumulates the running scoreline across the match', () => {
    const events = toEvents(1, HOME, [
      goal(HOME, 10, 'A'),
      goal(AWAY, 30, 'B'),
      goal(HOME, 70, 'C'),
    ]);
    assert.deepEqual(events[0]?.scoreAfter, { home: 1, away: 0 });
    assert.deepEqual(events[1]?.scoreAfter, { home: 1, away: 1 });
    assert.deepEqual(events[2]?.scoreAfter, { home: 2, away: 1 });
  });

  test('credits an own goal to the other side', () => {
    const [event] = toEvents(1, HOME, [goal(HOME, 25, 'Unlucky', 'Own Goal')]);
    assert.equal(event?.type, 'OWN_GOAL');
    assert.equal(event?.side, 'HOME');
    assert.deepEqual(event?.scoreAfter, { home: 0, away: 1 });
  });

  test('a missed penalty does not move the score', () => {
    const [event] = toEvents(1, HOME, [goal(HOME, 55, 'X', 'Missed Penalty')]);
    assert.equal(event?.type, 'PENALTY_MISSED');
    assert.deepEqual(event?.scoreAfter, { home: 0, away: 0 });
  });

  test('separates a second yellow from a straight red', () => {
    const card = (detail: string): ApiEvent => ({
      time: { elapsed: 40, extra: null },
      team: { id: HOME, name: 'Arsenal' },
      player: { id: 2, name: 'P' },
      assist: { id: null, name: null },
      type: 'Card',
      detail,
      comments: null,
    } as ApiEvent);
    const events = toEvents(1, HOME, [
      card('Yellow Card'),
      card('Second Yellow card'),
      card('Red Card'),
    ]);
    assert.equal(events[0]?.type, 'YELLOW_CARD');
    assert.equal(events[1]?.type, 'SECOND_YELLOW');
    assert.equal(events[2]?.type, 'RED_CARD');
  });

  test('mapping the same payload twice yields identical ids', () => {
    const payload = [goal(HOME, 10, 'A'), goal(AWAY, 30, 'B')];
    assert.deepEqual(
      toEvents(1, HOME, payload).map((e) => e.id),
      toEvents(1, HOME, payload).map((e) => e.id),
    );
  });
});
