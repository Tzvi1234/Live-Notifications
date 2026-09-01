import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import {
  CORRECT_MARGIN_POINTS,
  CORRECT_OUTCOME_POINTS,
  EXACT_SCORE_POINTS,
  MAX_SCORING_SCORERS,
  SCORER_POINTS,
  STAGE_MULTIPLIER,
  denseRanks,
  outcomeOf,
  rulebook,
  scorePrediction,
  stageOfRound,
} from '../game/scoring.js';

/** A league match, so the multiplier is one and the base tiers are readable. */
function league(predicted: [number, number], actual: [number, number]) {
  return scorePrediction({
    predicted: { home: predicted[0], away: predicted[1] },
    actual: { home: actual[0], away: actual[1] },
  });
}

describe('outcomeOf', () => {
  test('names the three results', () => {
    assert.equal(outcomeOf({ home: 2, away: 1 }), 'HOME');
    assert.equal(outcomeOf({ home: 1, away: 2 }), 'AWAY');
    assert.equal(outcomeOf({ home: 1, away: 1 }), 'DRAW');
  });
});

describe('the scoreline tiers', () => {
  test('an exact score is worth the most', () => {
    const score = league([2, 1], [2, 1]);
    assert.equal(score.points, EXACT_SCORE_POINTS);
    assert.ok(score.exact);
    assert.ok(score.correctMargin);
    assert.ok(score.correctOutcome);
  });

  test('the right margin sits between exact and merely right', () => {
    // 2-1 called on a 3-2 is a better guess than 4-0, and the table has to be able to say
    // so - that gap is most of what the game is about.
    const margin = league([2, 1], [3, 2]);
    assert.equal(margin.points, CORRECT_MARGIN_POINTS);
    assert.ok(margin.correctMargin);
    assert.ok(!margin.exact);

    const outcomeOnly = league([4, 0], [3, 2]);
    assert.equal(outcomeOnly.points, CORRECT_OUTCOME_POINTS);
    assert.ok(!outcomeOnly.correctMargin);
    assert.ok(outcomeOnly.correctOutcome);

    assert.ok(CORRECT_OUTCOME_POINTS < CORRECT_MARGIN_POINTS);
    assert.ok(CORRECT_MARGIN_POINTS < EXACT_SCORE_POINTS);
  });

  test('a wrong result is worth nothing at all', () => {
    const score = league([2, 1], [0, 3]);
    assert.equal(score.points, 0);
    assert.ok(!score.correctOutcome);
  });

  test('a draw called at the wrong score is the right margin', () => {
    // Every drawn result has a margin of nought, so calling any draw on any draw is both
    // the right outcome and the right margin. That is correct, not a loophole: a draw is a
    // harder call than a home win and this is the one place the two tiers coincide.
    const score = league([1, 1], [3, 3]);
    assert.equal(score.points, CORRECT_MARGIN_POINTS);
  });

  test('the tiers do not stack', () => {
    // An exact score is worth five, not five plus three plus two.
    assert.equal(league([2, 1], [2, 1]).points, EXACT_SCORE_POINTS);
  });
});

describe('stageOfRound', () => {
  test('reads the provider’s prose', () => {
    assert.equal(stageOfRound('Regular Season - 12'), 'LEAGUE');
    assert.equal(stageOfRound('Group Stage - 3'), 'LEAGUE');
    assert.equal(stageOfRound('Round of 32'), 'ROUND_OF_32');
    assert.equal(stageOfRound('Round of 16'), 'ROUND_OF_16');
    assert.equal(stageOfRound('Quarter-finals'), 'QUARTER_FINAL');
    assert.equal(stageOfRound('Semi-finals'), 'SEMI_FINAL');
    assert.equal(stageOfRound('Final'), 'FINAL');
    assert.equal(stageOfRound('3rd Place Final'), 'THIRD_PLACE');
  });

  test('a third-place play-off is not the final', () => {
    // It contains the word, which is why it is checked first.
    assert.notEqual(stageOfRound('3rd Place Final'), 'FINAL');
  });

  test('an unknown or missing round pays a league match’s rate', () => {
    // The safe way to be wrong: a mis-read round must never inflate a result.
    assert.equal(stageOfRound(undefined), 'LEAGUE');
    assert.equal(stageOfRound(''), 'LEAGUE');
    assert.equal(stageOfRound('Matchday 4'), 'LEAGUE');
    assert.equal(stageOfRound('Final Stage - 1'), 'LEAGUE');
  });
});

describe('the stage multiplier', () => {
  test('a final is worth six league matches', () => {
    const final = scorePrediction({
      predicted: { home: 2, away: 1 },
      actual: { home: 2, away: 1 },
      round: 'Final',
    });
    assert.equal(final.multiplier, 6);
    assert.equal(final.points, EXACT_SCORE_POINTS * 6);
    assert.equal(final.basePoints, EXACT_SCORE_POINTS, 'the working is shown before the multiplier');
  });

  test('the competition gets heavier as it narrows', () => {
    const order = ['LEAGUE', 'ROUND_OF_16', 'QUARTER_FINAL', 'SEMI_FINAL', 'FINAL'] as const;
    for (let i = 1; i < order.length; i += 1) {
      const previous = STAGE_MULTIPLIER[order[i - 1]];
      const current = STAGE_MULTIPLIER[order[i]];
      assert.ok(current >= previous, `${order[i]} must not be worth less than ${order[i - 1]}`);
    }
    assert.ok(STAGE_MULTIPLIER.FINAL > STAGE_MULTIPLIER.SEMI_FINAL);
  });

  test('a league season never multiplies', () => {
    // March must not be worth more than September.
    assert.equal(STAGE_MULTIPLIER.LEAGUE, 1);
    assert.equal(
      scorePrediction({
        predicted: { home: 1, away: 0 },
        actual: { home: 1, away: 0 },
        round: 'Regular Season - 30',
      }).points,
      EXACT_SCORE_POINTS,
    );
  });

  test('nothing multiplied by nothing is still nothing', () => {
    const wrong = scorePrediction({
      predicted: { home: 3, away: 0 },
      actual: { home: 0, away: 2 },
      round: 'Final',
    });
    assert.equal(wrong.points, 0);
  });
});

describe('naming a scorer', () => {
  const base = {
    predicted: { home: 2, away: 1 },
    actual: { home: 2, away: 1 },
  };

  test('a correct call is added before the multiplier', () => {
    const score = scorePrediction({
      ...base,
      round: 'Semi-finals',
      calledScorers: [{ playerId: 100 }],
      actualScorerIds: [100, 200],
    });
    assert.equal(score.correctScorers, 1);
    assert.equal(score.scorerPoints, SCORER_POINTS);
    // (5 + 2) x 4, not 5 x 4 + 2: the bonus is multiplied too, which is what makes naming
    // a scorer in a semi-final worth doing.
    assert.equal(score.points, (EXACT_SCORE_POINTS + SCORER_POINTS) * 4);
  });

  test('a wrong call costs nothing and earns nothing', () => {
    const score = scorePrediction({ ...base, calledScorers: [{ playerId: 999 }], actualScorerIds: [100] });
    assert.equal(score.correctScorers, 0);
    assert.equal(score.points, EXACT_SCORE_POINTS);
  });

  test('listing the whole front line is capped', () => {
    // Without the cap the optimal play is to name eleven players, which is not a prediction.
    const score = scorePrediction({
      ...base,
      calledScorers: [{ playerId: 1 }, { playerId: 2 }, { playerId: 3 }, { playerId: 4 }],
      actualScorerIds: [1, 2, 3, 4],
    });
    assert.equal(score.correctScorers, MAX_SCORING_SCORERS);
  });

  test('naming the same player twice is one call', () => {
    const score = scorePrediction({
      ...base,
      calledScorers: [{ playerId: 7 }, { playerId: 7 }],
      actualScorerIds: [7],
    });
    assert.equal(score.correctScorers, 1);
  });

  test('no call and no goals are both fine', () => {
    assert.equal(scorePrediction(base).scorerPoints, 0);
    assert.equal(scorePrediction({ ...base, calledScorers: [{ playerId: 1 }] }).scorerPoints, 0);
    assert.equal(scorePrediction({ ...base, actualScorerIds: [1] }).scorerPoints, 0);
  });
});

describe('rulebook', () => {
  test('publishes the same numbers the server scores with', () => {
    // The app renders this rather than restating it; if the two drifted, a member reading
    // the rules sheet would be reading fiction.
    const book = rulebook();
    const exact = book.scoring.find((row) => row.label === 'Exact score');
    assert.equal(exact?.points, EXACT_SCORE_POINTS);

    const final = book.multipliers.find((row) => row.stage === 'FINAL');
    assert.equal(final?.multiplier, STAGE_MULTIPLIER.FINAL);
    assert.equal(book.multipliers.length, Object.keys(STAGE_MULTIPLIER).length);
    assert.ok(book.notes.length > 0);
  });
});

describe('denseRanks', () => {
  test('a tie shares a rank and the next takes the next', () => {
    assert.deepEqual(denseRanks([{ points: 9 }, { points: 7 }, { points: 7 }, { points: 3 }]), [
      1, 2, 2, 3,
    ]);
  });

  test('an empty table ranks nothing', () => {
    assert.deepEqual(denseRanks([]), []);
  });

  test('everybody on nought is joint first', () => {
    // The day a group is created, before a ball is kicked. The table still draws.
    assert.deepEqual(denseRanks([{ points: 0 }, { points: 0 }, { points: 0 }]), [1, 1, 1]);
  });
});
