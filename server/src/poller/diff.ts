/**
 * What changed between the state we last pushed for a match and the fixture the provider
 * is showing now.
 *
 * Pure: no clock, no network, no config, no store. Everything the poller has to reason
 * about — a new incident, a period boundary, a VAR reversal — is decided here from three
 * literals, so all of it is reachable from a unit test.
 */

import {
  eventId,
  type MatchEventJson,
  type MatchEventType,
  type MatchJson,
  type MatchPhase,
  type ScoreJson,
  type TrackedMatchState,
} from '../types.js';

export interface MatchDiff {
  /** Provider incidents whose deterministic id has never been pushed. */
  newEvents: MatchEventJson[];
  phaseChanged: boolean;
  scoreChanged: boolean;
  /** Kick-off / half-time / full-time / VAR reversals: real events the provider never sends. */
  syntheticEvents: MatchEventJson[];
}

/**
 * Canonical minutes for the events the provider does not emit. They are constants rather
 * than the live clock on purpose: `eventId` folds the minute in, so deriving it from
 * `elapsed` would let two pollers a few seconds apart mint two different ids for one
 * kick-off, and both would pass the store's dedupe gate.
 */
export const SYNTHETIC_MINUTES: Readonly<Record<'KICK_OFF' | 'HALF_TIME' | 'FULL_TIME', number>> =
  Object.freeze({ KICK_OFF: 0, HALF_TIME: 45, FULL_TIME: 90 });

/** Ball in play. HALF_TIME and BREAK_TIME are live but not "running", which matters for kick-off. */
const IN_PLAY_PHASES: ReadonlySet<MatchPhase> = new Set<MatchPhase>([
  'FIRST_HALF',
  'SECOND_HALF',
  'EXTRA_TIME',
  'PENALTIES',
]);

/** Phases a match can be in before it has started. */
const PRE_KICK_OFF_PHASES: ReadonlySet<MatchPhase> = new Set<MatchPhase>([
  'SCHEDULED',
  'UNKNOWN',
  'OFF',
]);

/**
 * The only incidents whose withdrawal can move the scoreline, and therefore the only ones a
 * retraction is inferred from. Restricting the search to them also keeps synthetic ids
 * (KICK_OFF, VAR, the lineups marker) out of it — those are never in the provider's list,
 * so every one of them would otherwise look "vanished" on every single tick.
 */
const SCORING_TYPES: ReadonlySet<MatchEventType> = new Set<MatchEventType>([
  'GOAL',
  'OWN_GOAL',
  'PENALTY_GOAL',
]);

const NO_IDS: ReadonlySet<string> = new Set<string>();

export interface ParsedEventId {
  matchId: number;
  type: string;
  minute?: number | undefined;
  teamId?: number | undefined;
  player?: string | undefined;
}

/**
 * Inverse of `eventId()`. The player name is the last field and may itself contain a colon,
 * so everything past the fourth separator is the name.
 */
export function parseEventId(id: string): ParsedEventId | undefined {
  const parts = id.split(':');
  if (parts.length < 5) return undefined;
  const matchId = Number(parts[0]);
  const type = parts[1] ?? '';
  if (!Number.isSafeInteger(matchId) || matchId <= 0 || type.length === 0) return undefined;
  const minute = Number(parts[2]);
  const teamId = Number(parts[3]);
  const player = parts.slice(4).join(':');
  return {
    matchId,
    type,
    minute: Number.isSafeInteger(minute) && minute >= 0 ? minute : undefined,
    teamId: Number.isSafeInteger(teamId) && teamId > 0 ? teamId : undefined,
    player: player.length > 0 ? player : undefined,
  };
}

function scoreEquals(a: ScoreJson | undefined, b: ScoreJson | undefined): boolean {
  if (a === undefined || b === undefined) return a === b;
  return a.home === b.home && a.away === b.away;
}

function sideFor(match: MatchJson, teamId: number | undefined): MatchEventJson['side'] {
  if (teamId === undefined) return 'NEUTRAL';
  if (teamId === match.home.id) return 'HOME';
  if (teamId === match.away.id) return 'AWAY';
  return 'NEUTRAL';
}

function phaseEventDetail(type: 'KICK_OFF' | 'HALF_TIME' | 'FULL_TIME'): string {
  switch (type) {
    case 'KICK_OFF':
      return 'Kick-off';
    case 'HALF_TIME':
      return 'Half-time';
    case 'FULL_TIME':
      return 'Full-time';
  }
}

function phaseEvent(match: MatchJson, type: 'KICK_OFF' | 'HALF_TIME' | 'FULL_TIME'): MatchEventJson {
  const minute = SYNTHETIC_MINUTES[type];
  return {
    id: eventId(match.id, type, minute, undefined, undefined),
    type,
    side: 'NEUTRAL',
    minute,
    detail: phaseEventDetail(type),
    scoreAfter: match.score ?? { home: 0, away: 0 },
  };
}

/**
 * Period boundaries. The provider reports them only as a status change on the fixture, so
 * they are minted here; a second-half restart is deliberately not a kick-off.
 */
function phaseTransitionEvents(previous: TrackedMatchState, next: MatchJson): MatchEventJson[] {
  if (previous.phase === next.phase) return [];

  const events: MatchEventJson[] = [];
  if (PRE_KICK_OFF_PHASES.has(previous.phase) && IN_PLAY_PHASES.has(next.phase)) {
    events.push(phaseEvent(next, 'KICK_OFF'));
  }
  if (next.phase === 'HALF_TIME') {
    events.push(phaseEvent(next, 'HALF_TIME'));
  }
  if (next.phase === 'FINISHED') {
    events.push(phaseEvent(next, 'FULL_TIME'));
  }
  return events;
}

/**
 * VAR retraction.
 *
 * The provider never says it has withdrawn an incident: the event simply stops appearing in
 * `/fixtures/events` and the scoreline drops. Ignoring that is not a harmless omission —
 * the phone keeps a goal on the card that the rest of the world has stopped counting, and
 * no later event ever takes it off, so the client diverges from the provider for the rest
 * of the match. So a disappearance is turned into a VAR event describing the correction.
 *
 * Both halves must hold before anything is emitted: an event list that comes back short on
 * its own is a provider hiccup (they do drop and restore incidents), and a scoreline that
 * dips without any event vanishing is a fetch that raced a re-index. Requiring the two
 * together is what keeps a bogus "goal disallowed" alert off the lock screen.
 *
 * The id is minted from the *retracted* incident's minute, team and player, which makes it
 * deterministic across pollers and restarts. It also collides with — and therefore dedupes
 * against — the provider's own VAR event for the same reversal whenever that event is
 * timestamped at the minute of the goal. When the provider instead timestamps it at the
 * minute the review concluded, the two ids differ and the client sees two corrections.
 */
function retractionEvents(
  previous: TrackedMatchState,
  next: MatchJson,
  providerEventIds: ReadonlySet<string>,
): MatchEventJson[] {
  // An empty feed is a thin fetch, not a match in which everything was overturned.
  if (providerEventIds.size === 0) return [];

  const before = previous.score;
  const after = next.score;
  if (before === undefined || after === undefined) return [];
  if (after.home >= before.home && after.away >= before.away) return [];

  const vanished: ParsedEventId[] = [];
  for (const id of previous.sentEventIds) {
    if (providerEventIds.has(id)) continue;
    const parsed = parseEventId(id);
    if (!parsed || !SCORING_TYPES.has(parsed.type as MatchEventType)) continue;
    vanished.push(parsed);
  }
  // Stable order so two pollers emit the same sequence for the same reversal.
  vanished.sort((a, b) => (a.minute ?? -1) - (b.minute ?? -1));

  return vanished.map((incident) => ({
    id: eventId(next.id, 'VAR', incident.minute, incident.teamId, incident.player),
    type: 'VAR' as const,
    side: sideFor(next, incident.teamId),
    teamId: incident.teamId,
    minute: incident.minute,
    player: incident.player,
    detail: 'Goal disallowed after review',
    comment: `Score corrected to ${after.home}-${after.away}`,
    scoreAfter: after,
  }));
}

/**
 * Types whose `minute` is a label rather than a timestamp, so the lag arithmetic below says
 * nothing about them. FULL_TIME always carries the canonical 90 while the clock of a fixture
 * that went to extra time reads 120, and a VAR retraction carries the minute of the goal it
 * reverses rather than the minute the reversal was spotted. Measuring either against the
 * clock silences exactly the two events a subscriber most needs.
 */
const CLOCK_INDEPENDENT_TYPES: ReadonlySet<MatchEventType> = new Set<MatchEventType>([
  'FULL_TIME',
  'VAR',
]);

/**
 * An incident this far behind the live clock is history rather than news — the poller marks
 * it as sent so it can never fire later, but does not notify anyone about it. That is what
 * stops a restart, a re-subscribe, or a poller that was quota-blocked for twenty minutes
 * from emptying a whole half of football onto someone's lock screen at once.
 */
export function isStaleEvent(
  event: MatchEventJson,
  match: MatchJson,
  maxLagMinutes: number,
): boolean {
  if (CLOCK_INDEPENDENT_TYPES.has(event.type)) return false;
  if (match.elapsed === undefined || event.minute === undefined) return false;
  return match.elapsed - event.minute > maxLagMinutes;
}

/**
 * `previous === undefined` means this process has no record of the match at all. Every
 * event then reads as new, which is correct for the caller to *record* but not to notify
 * on: the poller seeds the dedupe set instead of pushing. Nothing is reported as changed
 * either — a first sighting is not a transition, and calling it one would invent a
 * kick-off for a match already at 70 minutes.
 */
export function diffMatch(
  previous: TrackedMatchState | undefined,
  next: MatchJson,
  events: readonly MatchEventJson[],
): MatchDiff {
  const sent = previous?.sentEventIds ?? NO_IDS;
  const providerEventIds = new Set<string>();
  const newEvents: MatchEventJson[] = [];

  for (const event of events) {
    // The provider does re-list one incident twice (a goal credited to two feeds, say);
    // the derived id is identical, so the second copy is dropped here rather than racing
    // its twin through the store's gate.
    if (providerEventIds.has(event.id)) continue;
    providerEventIds.add(event.id);
    if (!sent.has(event.id)) newEvents.push(event);
  }

  if (previous === undefined) {
    return { newEvents, phaseChanged: false, scoreChanged: false, syntheticEvents: [] };
  }

  return {
    newEvents,
    phaseChanged: previous.phase !== next.phase,
    scoreChanged: !scoreEquals(previous.score, next.score),
    syntheticEvents: [
      ...phaseTransitionEvents(previous, next),
      ...retractionEvents(previous, next, providerEventIds),
    ],
  };
}
