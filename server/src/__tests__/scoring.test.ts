import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  CORRECT_OUTCOME_POINTS,
  EXACT_SCORE_POINTS,
  NO_POINTS,
  denseRanks,
  outcomeOf,
  scorePrediction,
} from '../game/scoring.js';

describe('outcomeOf', () => {
  test('reads a scoreline as home win, draw or away win', () => {
    assert.equal(outcomeOf({ home: 2, away: 1 }), 'HOME');
    assert.equal(outcomeOf({ home: 0, away: 0 }), 'DRAW');
    assert.equal(outcomeOf({ home: 1, away: 3 }), 'AWAY');
  });
});

describe('scorePrediction', () => {
  test('an exact score is worth 3, and is not 3 + 1', () => {
    const score = scorePrediction({ home: 2, away: 1 }, { home: 2, away: 1 });
    assert.deepEqual(score, { points: EXACT_SCORE_POINTS, exact: true, correctOutcome: true });
    assert.equal(score.points, 3);
  });

  test('the right result with the wrong score is worth 1', () => {
    const score = scorePrediction({ home: 3, away: 0 }, { home: 1, away: 0 });
    assert.deepEqual(score, {
      points: CORRECT_OUTCOME_POINTS,
      exact: false,
      correctOutcome: true,
    });
  });

  test('a predicted draw against any drawn score is the right result', () => {
    assert.equal(scorePrediction({ home: 1, away: 1 }, { home: 3, away: 3 }).points, 1);
    // ...and an exact draw is still the exact score, not merely the right result.
    assert.equal(scorePrediction({ home: 1, away: 1 }, { home: 1, away: 1 }).points, 3);
  });

  test('the wrong result scores nothing', () => {
    const score = scorePrediction({ home: 2, away: 0 }, { home: 0, away: 2 });
    assert.deepEqual(score, { points: NO_POINTS, exact: false, correctOutcome: false });
  });

  test('a draw predicted against a win, and the reverse, both score nothing', () => {
    assert.equal(scorePrediction({ home: 1, away: 1 }, { home: 2, away: 1 }).points, 0);
    assert.equal(scorePrediction({ home: 2, away: 1 }, { home: 1, away: 1 }).points, 0);
  });

  test('is symmetric in nothing: swapping the sides is a different prediction', () => {
    assert.equal(scorePrediction({ home: 2, away: 1 }, { home: 1, away: 2 }).points, 0);
  });
});

describe('denseRanks', () => {
  test('equal points share a rank and the next entry takes the next one', () => {
    assert.deepEqual(
      denseRanks([{ points: 9 }, { points: 4 }, { points: 4 }, { points: 1 }]),
      [1, 2, 2, 3],
    );
  });

  test('an empty board has no ranks', () => {
    assert.deepEqual(denseRanks([]), []);
  });

  test('a board on which nobody has scored is all rank 1', () => {
    assert.deepEqual(denseRanks([{ points: 0 }, { points: 0 }]), [1, 1]);
  });
});
