/**
 * Data-only FCM payload builders.
 *
 * Both builders emit `data` and nothing else: the Android client renders its own
 * notification from these keys (channel, grouping, crest loading and the score UI all
 * live there), and a `notification` block would make the OS post a second, duplicate
 * notification whenever the app is backgrounded.
 *
 * Every value is a string because FCM rejects a `data` map containing anything else, and an
 * optional with nothing to say is left out rather than sent as "". The client's fallbacks are
 * all null checks — `playerName ?: teamName`, `homeShort ?: name.take(3)`, `detail ?: "check"`
 * — and an empty string satisfies every one of them, so sending "" renders a blank line where
 * omitting the key renders the fallback.
 */

import { logger } from '../logger.js';
import {
  DURABLE_PUSH_TTL_MS,
  TICK_PUSH_TTL_MS,
  tickCollapseKey,
  type MatchEventJson,
  type MatchJson,
  type MatchSide,
  type PushEnvelope,
  type ScoreJson,
} from '../types.js';

/** Bumped only when a key changes meaning. Nothing reads it yet; it is here so a client that
 * has to tell an old payload from a new one can, without inferring it from the key set. */
export const PAYLOAD_VERSION = '1';

/** FCM's hard limit on a data message. Over it, the send fails for every token in the batch. */
export const FCM_DATA_LIMIT_BYTES = 4096;

/** ASCII, so the byte reserve when truncating is exactly 3 and needs no re-measuring. */
const ELLIPSIS = '...';
const ELLIPSIS_BYTES = 3;

/** Below this a truncated string is noise; the field is emptied instead. */
const MIN_KEPT_BYTES = 16;

/**
 * Overflow is shed in this order: decoration first, then the fields the client can rebuild,
 * then the ones it cannot invent. `headline` outranks `detail` because the client composes an
 * equivalent line from the remaining keys, whereas a VAR or lineups card is *only* `detail`.
 */
const SHRINKABLE_KEYS: readonly string[] = [
  'leagueLogo',
  'venue',
  'round',
  'headline',
  'detail',
  'assist',
  'player',
  'homeCrest',
  'awayCrest',
  'leagueName',
];

/**
 * The envelope minus its recipients: builders describe the message, the caller decides who
 * gets it. A full `PushEnvelope` is assignable to this, so it is also what `sendToTokens`
 * accepts.
 */
export type PushMessage = Omit<PushEnvelope, 'tokens'>;

/**
 * Serialized size in bytes. JSON punctuation is counted even though FCM measures the data
 * map itself — over-counting by a few bytes per key buys headroom rather than costing it.
 */
export function payloadSize(data: Record<string, string>): number {
  return Buffer.byteLength(JSON.stringify(data), 'utf8');
}

function str(value: string | number | null | undefined): string {
  return value === undefined || value === null ? '' : String(value);
}

/** "45+2" / "67" / "" — the client re-appends the apostrophe in its own locale. */
function clock(minute?: number | undefined, extra?: number | undefined): string {
  if (minute === undefined || minute === null) return '';
  return extra ? `${minute}+${extra}` : `${minute}`;
}

function minuteSuffix(minute?: number | undefined, extra?: number | undefined): string {
  const value = clock(minute, extra);
  return value === '' ? '' : ` ${value}'`;
}

function scoreline(match: MatchJson, score: ScoreJson): string {
  return `${match.home.name} ${score.home}-${score.away} ${match.away.name}`;
}

/**
 * The scoreline the *event* left behind, not the one the fixture shows now: a goal pushed
 * after a later VAR correction must still read as it did when it was scored.
 */
function scoreFor(match: MatchJson, event?: MatchEventJson): ScoreJson {
  return event?.scoreAfter ?? match.score ?? { home: 0, away: 0 };
}

function teamNameFor(match: MatchJson, event: MatchEventJson): string {
  if (event.teamName) return event.teamName;
  if (event.teamId === match.home.id) return match.home.name;
  if (event.teamId === match.away.id) return match.away.name;
  return '';
}

/** Human-readable one-liner; the client uses it verbatim only when it has no better string. */
export function eventHeadline(match: MatchJson, event: MatchEventJson): string {
  const score = scoreFor(match, event);
  const line = scoreline(match, score);
  const at = minuteSuffix(event.minute, event.extra);
  const who = event.player ?? '';
  const team = teamNameFor(match, event);

  switch (event.type) {
    case 'GOAL':
      return `Goal! ${line}${who ? ` - ${who}${at}` : at}`;
    case 'PENALTY_GOAL':
      return `Penalty scored! ${line}${who ? ` - ${who}${at}` : at}`;
    case 'OWN_GOAL':
      return `Own goal! ${line}${who ? ` - ${who}${at}` : at}`;
    case 'PENALTY_MISSED':
      return `Penalty missed${who ? ` - ${who}` : ''}${team ? ` (${team})` : ''}${at}`;
    case 'YELLOW_CARD':
      return `Yellow card${who ? ` - ${who}` : ''}${team ? ` (${team})` : ''}${at}`;
    case 'SECOND_YELLOW':
      return `Second yellow${who ? ` - ${who}` : ''}${team ? ` (${team})` : ''}${at}`;
    case 'RED_CARD':
      return `Red card${who ? ` - ${who}` : ''}${team ? ` (${team})` : ''}${at}`;
    case 'SUBSTITUTION':
      // Provider semantics: player comes on, assist goes off.
      return `Substitution${team ? ` (${team})` : ''}: ${who || 'unknown'} on${
        event.assist ? `, ${event.assist} off` : ''
      }${at}`;
    case 'VAR':
      return `VAR${event.detail ? `: ${event.detail}` : ''}${at} - ${line}`;
    case 'KICK_OFF':
      return `Kick-off: ${match.home.name} v ${match.away.name}`;
    case 'HALF_TIME':
      return `Half-time: ${line}`;
    case 'FULL_TIME':
      return `Full-time: ${line}`;
    case 'OTHER':
    default:
      return event.detail ? `${event.detail} - ${line}` : line;
  }
}

export function tickHeadline(match: MatchJson): string {
  const line = scoreline(match, scoreFor(match));
  switch (match.phase) {
    case 'HALF_TIME':
      return `HT ${line}`;
    case 'FINISHED':
      return `FT ${line}`;
    case 'PENALTIES':
      return `Penalties: ${line}`;
    case 'BREAK_TIME':
      return `Break: ${line}`;
    default: {
      const at = clock(match.elapsed, match.extra);
      return at === '' ? line : `${line} ${at}'`;
    }
  }
}

interface DataInput {
  readonly match: MatchJson;
  readonly type: string;
  readonly eventId: string;
  readonly sequence: number;
  readonly side: MatchSide;
  readonly minute?: number | undefined;
  readonly extra?: number | undefined;
  readonly player?: string | undefined;
  readonly assist?: string | undefined;
  readonly score: ScoreJson;
  readonly headline: string;
  readonly detail?: string | undefined;
  readonly nowMs: number;
}

/** Always sent: without these the client discards the message or renders an empty card. */
function requiredData(input: DataInput): Record<string, string> {
  const { match, score } = input;
  return {
    v: PAYLOAD_VERSION,
    type: input.type,
    matchId: String(match.id),
    seq: String(input.sequence),
    phase: match.phase,
    side: input.side,
    homeId: String(match.home.id),
    homeName: match.home.name,
    awayId: String(match.away.id),
    awayName: match.away.name,
    homeScore: String(score.home),
    awayScore: String(score.away),
    leagueId: String(match.leagueId),
    leagueName: match.leagueName,
    /** SECONDS since epoch: the client reads it with Instant.ofEpochSecond. */
    kickoffAt: String(match.kickoffAt),
    ts: String(input.nowMs),
  };
}

function buildData(input: DataInput): Record<string, string> {
  const { match } = input;
  const data = requiredData(input);

  // Omitted when empty, never sent as "" — see the note at the top of the file. A numeric 0
  // still counts as a value: minute 0 is kick-off, not a missing clock.
  const optional: Record<string, string | number | null | undefined> = {
    eventId: input.eventId,
    minute: input.minute,
    extra: input.extra,
    homeShort: match.home.shortName,
    homeCrest: match.home.crestUrl,
    awayShort: match.away.shortName,
    awayCrest: match.away.crestUrl,
    leagueLogo: match.leagueLogoUrl,
    round: match.round,
    venue: match.venue,
    player: input.player,
    assist: input.assist,
    detail: input.detail,
    headline: input.headline,
  };

  for (const [key, value] of Object.entries(optional)) {
    const text = str(value);
    if (text !== '') data[key] = text;
  }
  return data;
}

/** UTF-8 safe: iterates code points, so a truncated name never ends in half a surrogate. */
function truncateToBytes(text: string, maxBytes: number): string {
  if (Buffer.byteLength(text, 'utf8') <= maxBytes) return text;
  const budget = maxBytes - ELLIPSIS_BYTES;
  let kept = '';
  let used = 0;
  for (const point of text) {
    const size = Buffer.byteLength(point, 'utf8');
    if (used + size > budget) break;
    kept += point;
    used += size;
  }
  return `${kept.trimEnd()}${ELLIPSIS}`;
}

/**
 * Keeps the message under FCM's 4096-byte data limit. An oversized payload is not a partial
 * failure — FCM rejects the whole batch — so shedding text is always better than sending it.
 */
function enforceSizeLimit(data: Record<string, string>, context: Record<string, unknown>): Record<string, string> {
  const initialSize = payloadSize(data);
  if (initialSize <= FCM_DATA_LIMIT_BYTES) return data;

  for (const key of SHRINKABLE_KEYS) {
    const current = data[key];
    if (current === undefined || current === '') continue;
    const over = payloadSize(data) - FCM_DATA_LIMIT_BYTES;
    if (over <= 0) break;
    const keep = Buffer.byteLength(current, 'utf8') - over;
    data[key] = keep >= MIN_KEPT_BYTES ? truncateToBytes(current, keep) : '';
  }

  const finalSize = payloadSize(data);
  if (finalSize > FCM_DATA_LIMIT_BYTES) {
    // Nothing left to shed: the fixed fields alone blew the budget, which means the provider
    // handed us something absurd (a team name of several hundred bytes, say).
    logger.error('push payload exceeds FCM limit after truncation', {
      ...context,
      initialSize,
      finalSize,
      limit: FCM_DATA_LIMIT_BYTES,
    });
  } else {
    logger.warn('push payload truncated to fit FCM limit', {
      ...context,
      initialSize,
      finalSize,
      limit: FCM_DATA_LIMIT_BYTES,
    });
  }
  return data;
}

/**
 * Durable: something happened and must survive a sleeping radio. Non-collapsible so a goal
 * is never swallowed by the tick that follows it, and 10 minutes of TTL so a phone that
 * wakes late still gets it while it is still news.
 */
export function eventPayload(
  match: MatchJson,
  event: MatchEventJson,
  sequence: number,
  nowMs: number = Date.now(),
): PushMessage {
  const data = buildData({
    match,
    type: event.type,
    eventId: event.id,
    sequence,
    side: event.side,
    minute: event.minute,
    extra: event.extra,
    player: event.player,
    assist: event.assist,
    score: scoreFor(match, event),
    headline: eventHeadline(match, event),
    detail: event.detail,
    nowMs,
  });

  return {
    kind: 'DURABLE',
    // Explicitly unset: a collapse key here would let the next tick replace the goal.
    collapseKey: undefined,
    ttlMillis: DURABLE_PUSH_TTL_MS,
    data: enforceSizeLimit(data, { matchId: match.id, eventId: event.id, type: event.type }),
    matchId: match.id,
    eventId: event.id,
  };
}

/**
 * Score/clock refresh. `ttl: 0` means deliver now or drop — correct semantics for a value
 * that is worthless once a newer one exists, and the only exemption from FCM's collapsible
 * throttle (20-message burst per app per device, refilling 1 per 3 minutes), which a 30s
 * cadence would otherwise drain inside ten minutes.
 */
export function tickPayload(
  match: MatchJson,
  sequence: number,
  nowMs: number = Date.now(),
): PushMessage {
  const data = buildData({
    match,
    type: 'TICK',
    // No event id: a tick is not an incident, and the client drops the key's whole branch
    // because "TICK" is not one of its event types. Ordering is settled by `seq` instead.
    eventId: '',
    sequence,
    side: 'NEUTRAL',
    minute: match.elapsed,
    extra: match.extra,
    score: scoreFor(match),
    headline: tickHeadline(match),
    nowMs,
  });

  return {
    kind: 'TICK',
    // One key per match, scoped to ticks: FCM keeps only 4 distinct collapse keys per device,
    // and a bare matchId would let a tick collapse a goal.
    collapseKey: tickCollapseKey(match.id),
    ttlMillis: TICK_PUSH_TTL_MS,
    data: enforceSizeLimit(data, { matchId: match.id, type: 'TICK' }),
    matchId: match.id,
  };
}
