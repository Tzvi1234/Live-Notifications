/**
 * The prediction game's scoring rule, in one place.
 *
 * Pure — no clock, no store, no config — because it is the one piece of the game a member
 * will argue about, and it has to be readable and reachable from a test without a database.
 *
 * The Postgres store settles a whole fixture in a single UPDATE rather than by looping
 * through rows in Node, so it cannot call `scorePrediction`. It is handed the two constants
 * below as bound parameters instead, which is what keeps the two paths from drifting: there
 * is still exactly one definition of what an exact score is worth.
 */

import type { ScoreJson } from '../types.js';

/** Named the score exactly. */
export const EXACT_SCORE_POINTS = 3;

/** Got home-win / draw / away-win right but not the score. */
export const CORRECT_OUTCOME_POINTS = 1;

export const NO_POINTS = 0;

export type Outcome = 'HOME' | 'DRAW' | 'AWAY';

export interface PredictionScore {
  points: number;
  exact: boolean;
  /** True whenever the direction was right — an exact score is also the right direction. */
  correctOutcome: boolean;
}

export function outcomeOf(score: ScoreJson): Outcome {
  if (score.home > score.away) return 'HOME';
  if (score.home < score.away) return 'AWAY';
  return 'DRAW';
}

/**
 * 3 for the exact score, 1 for the right result, 0 otherwise. The two are not added: an
 * exact score is worth 3, not 4.
 */
export function scorePrediction(predicted: ScoreJson, actual: ScoreJson): PredictionScore {
  const exact = predicted.home === actual.home && predicted.away === actual.away;
  const correctOutcome = outcomeOf(predicted) === outcomeOf(actual);
  if (exact) return { points: EXACT_SCORE_POINTS, exact: true, correctOutcome: true };
  if (correctOutcome) {
    return { points: CORRECT_OUTCOME_POINTS, exact: false, correctOutcome: true };
  }
  return { points: NO_POINTS, exact: false, correctOutcome: false };
}

/**
 * Equal points share a rank and the next entry takes the *next* rank (1, 2, 2, 3): the
 * leaderboard is short and read as a table, where a gap after a tie reads as a bug.
 * `rows` must already be ordered by points descending.
 */
export function denseRanks(rows: ReadonlyArray<{ points: number }>): number[] {
  const ranks: number[] = [];
  let rank = 0;
  let previous: number | undefined;
  for (const row of rows) {
    if (row.points !== previous) {
      rank += 1;
      previous = row.points;
    }
    ranks.push(rank);
  }
  return ranks;
}
