/**
 * The prediction game's rulebook, in one place.
 *
 * Pure — no clock, no store, no config — because it is the one piece of the game a member
 * will argue about, and it has to be readable and reachable from a test without a database.
 *
 * The shape of the rules:
 *
 *   BASE, for the scoreline. Exactly right is worth five; the right winner with the wrong
 *   score is worth two; and between them sits the right margin — calling a 2-1 when it
 *   finished 3-2 is a better guess than calling 4-0, and the table should say so.
 *
 *   BONUS, for naming a scorer. Optional, additive, and capped, so a member who lists
 *   eleven names cannot brute-force the round.
 *
 *   MULTIPLIER, for what the match is worth. A Champions League final is not a Tuesday in
 *   the group stage, and a competition people follow to its end should get heavier as it
 *   narrows. League matches are always ×1: a league has no rounds that matter more, and
 *   pretending otherwise would make March worth more than September for no reason.
 *
 * The Postgres store settles a whole fixture in one UPDATE rather than looping rows in
 * Node, so it cannot call [scorePrediction]. It is handed these constants as bound
 * parameters instead, which is what keeps the two paths from drifting.
 */

import type { RulebookJson, ScoreJson } from '../types.js';

/** Named the score exactly. */
export const EXACT_SCORE_POINTS = 5;

/**
 * Right winner, right margin, wrong score — 2-1 called on a 3-2.
 *
 * Sits between the two because it is a genuinely better guess than the right winner alone,
 * and a table that cannot tell them apart flattens the thing the game is about.
 */
export const CORRECT_MARGIN_POINTS = 3;

/** Got home-win / draw / away-win right but neither the score nor the margin. */
export const CORRECT_OUTCOME_POINTS = 2;

export const NO_POINTS = 0;

/** Per correct scorer named, before the stage multiplier. */
export const SCORER_POINTS = 2;

/**
 * How many named scorers can earn.
 *
 * Without a cap the optimal play is to list the whole front line, which is not a
 * prediction. Two is enough to reward a real call and few enough that it stays one.
 */
export const MAX_SCORING_SCORERS = 2;

export type Outcome = 'HOME' | 'DRAW' | 'AWAY';

/**
 * How far into a knockout competition a match is.
 *
 * [LEAGUE] covers every round of a league season and every group stage: they are the
 * baseline, and a group game in October is worth what a league game in October is worth.
 */
export type Stage =
  | 'LEAGUE'
  | 'ROUND_OF_32'
  | 'ROUND_OF_16'
  | 'QUARTER_FINAL'
  | 'SEMI_FINAL'
  | 'THIRD_PLACE'
  | 'FINAL';

/** What a correct call is multiplied by, by how far the competition has narrowed. */
export const STAGE_MULTIPLIER: Record<Stage, number> = {
  LEAGUE: 1,
  ROUND_OF_32: 2,
  ROUND_OF_16: 2,
  QUARTER_FINAL: 3,
  THIRD_PLACE: 3,
  SEMI_FINAL: 4,
  FINAL: 6,
};

export interface PredictionScore {
  /** What the member actually banks: (base + scorer bonus) × the stage multiplier. */
  points: number;
  /** Before the multiplier, so a scorecard can show the working. */
  basePoints: number;
  scorerPoints: number;
  multiplier: number;
  stage: Stage;
  exact: boolean;
  /** True whenever the direction was right — an exact score is also the right direction. */
  correctOutcome: boolean;
  /** Right winner AND right margin. An exact score is also the right margin. */
  correctMargin: boolean;
  /** How many named scorers actually scored, after the cap. */
  correctScorers: number;
}

export function outcomeOf(score: ScoreJson): Outcome {
  if (score.home > score.away) return 'HOME';
  if (score.home < score.away) return 'AWAY';
  return 'DRAW';
}

/**
 * Reads the provider's free-text round into a stage.
 *
 * API-Football writes `league.round` as prose — "Regular Season - 12", "Group Stage - 3",
 * "Round of 16", "Quarter-finals", "Semi-finals", "Final", "3rd Place Final" — with the
 * spelling varying by competition, so this matches on substrings rather than on an
 * enumeration nobody publishes. Anything unrecognised is [LEAGUE], which is the safe way
 * to be wrong: a mis-read round pays a normal match's points rather than a final's.
 */
export function stageOfRound(round: string | undefined | null): Stage {
  const text = (round ?? '').toLowerCase();
  if (text.length === 0) return 'LEAGUE';

  // Checked before "final", which every one of them also contains.
  if (text.includes('3rd place') || text.includes('third place')) return 'THIRD_PLACE';
  if (text.includes('semi')) return 'SEMI_FINAL';
  if (text.includes('quarter')) return 'QUARTER_FINAL';
  if (text.includes('16')) return 'ROUND_OF_16';
  if (text.includes('32')) return 'ROUND_OF_32';

  // "Regular Season", "Group Stage" and "League Stage" all say the round number after a
  // dash; a bare "Final" does not. Requiring the word to stand more or less alone keeps
  // "Final Stage - 1" in a league from paying six times.
  if (text.includes('final') && !text.includes('stage')) return 'FINAL';

  return 'LEAGUE';
}

export interface ScorerCall {
  /** The provider's player id, which is what the settled line-up is matched against. */
  playerId: number;
}

export interface ScoringInput {
  predicted: ScoreJson;
  actual: ScoreJson;
  /** The fixture's `league.round`, verbatim from the provider. */
  round?: string | undefined;
  /** Players the member named as scorers, if any. */
  calledScorers?: readonly ScorerCall[] | undefined;
  /** Player ids who actually scored, from the settled events. */
  actualScorerIds?: readonly number[] | undefined;
}

/**
 * The whole rulebook, applied.
 *
 * The base tiers are not added to each other: an exact score is worth five, not five plus
 * three plus two. The scorer bonus IS added, and the multiplier applies to the sum — so
 * naming Haaland in a Champions League final is worth six times what it is worth in
 * September, which is the point.
 */
export function scorePrediction(input: ScoringInput): PredictionScore {
  const { predicted, actual } = input;
  const stage = stageOfRound(input.round);
  const multiplier = STAGE_MULTIPLIER[stage];

  const exact = predicted.home === actual.home && predicted.away === actual.away;
  const correctOutcome = outcomeOf(predicted) === outcomeOf(actual);
  const correctMargin = correctOutcome && predicted.home - predicted.away === actual.home - actual.away;

  const basePoints = exact
    ? EXACT_SCORE_POINTS
    : correctMargin
      ? CORRECT_MARGIN_POINTS
      : correctOutcome
        ? CORRECT_OUTCOME_POINTS
        : NO_POINTS;

  const correctScorers = countCorrectScorers(input.calledScorers, input.actualScorerIds);
  const scorerPoints = correctScorers * SCORER_POINTS;

  return {
    points: (basePoints + scorerPoints) * multiplier,
    basePoints,
    scorerPoints,
    multiplier,
    stage,
    exact,
    correctOutcome,
    correctMargin,
    correctScorers,
  };
}

/**
 * How many of the named scorers scored, capped.
 *
 * Named twice, counted once: a member who lists the same player three times has made one
 * call, not three.
 */
function countCorrectScorers(
  called: readonly ScorerCall[] | undefined,
  actualIds: readonly number[] | undefined,
): number {
  if (called === undefined || called.length === 0) return 0;
  if (actualIds === undefined || actualIds.length === 0) return 0;
  const scored = new Set(actualIds);
  const hit = new Set<number>();
  for (const call of called) {
    if (scored.has(call.playerId)) hit.add(call.playerId);
  }
  return Math.min(hit.size, MAX_SCORING_SCORERS);
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

/**
 * The rulebook as data, for the app to render rather than restate.
 *
 * A member arguing about a score should be able to read the same numbers the server used,
 * and a house rule changed here must not need an app release to show up in the rules sheet.
 */
export function rulebook(): RulebookJson {
  return {
    scoring: [
      { label: 'Exact score', points: EXACT_SCORE_POINTS },
      { label: 'Right winner and right margin', points: CORRECT_MARGIN_POINTS },
      { label: 'Right winner', points: CORRECT_OUTCOME_POINTS },
      { label: 'Each scorer you named, up to two', points: SCORER_POINTS },
    ],
    multipliers: (Object.keys(STAGE_MULTIPLIER) as Stage[]).map((stage) => ({
      stage,
      label: STAGE_LABELS[stage],
      multiplier: STAGE_MULTIPLIER[stage],
    })),
    notes: [
      'The scoreline tiers do not stack: an exact score is worth five, not five plus three.',
      'Scorer points are added to the scoreline points, and the multiplier applies to the total.',
      'Predictions lock at kick-off, and nobody sees anybody else’s until then.',
      'The captain is whoever created the group.',
    ],
  };
}

const STAGE_LABELS: Record<Stage, string> = {
  LEAGUE: 'League or group stage',
  ROUND_OF_32: 'Round of 32',
  ROUND_OF_16: 'Round of 16',
  QUARTER_FINAL: 'Quarter-final',
  SEMI_FINAL: 'Semi-final',
  THIRD_PLACE: 'Third-place play-off',
  FINAL: 'Final',
};

