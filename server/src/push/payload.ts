/**
 * Data-only FCM payload builders.
 *
 * Both builders emit `data` and nothing else: the Android client renders its own
 * notification from these keys (channel, grouping, crest loading and the score UI all
 * live there), and a `notification` block would make the OS post a second, duplicate
 * notification whenever the app is backgrounded.
 *
 * Every value is a string because FCM rejects a `data` map containing anything else,
 * and the key set is fixed — a key with no value is sent empty rather than omitted, so
 * the client never has to branch on presence.
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

/** Bumped only when a key changes meaning; the client refuses payloads it cannot read. */
export const PAYLOAD_VERSION = '1';

/** FCM's hard limit on a data message. Over it, the send fails for every token in the batch. */
export const FCM_DATA_LIMIT_BYTES = 4096;

/** ASCII, so the byte reserve when truncating is exactly 3 and needs no re-measuring. */
const ELLIPSIS = '...';
const ELLIPSIS_BYTES = 3;

/** Below this a truncated string is noise; the field is emptied instead. */
const MIN_KEPT_BYTES = 16;

/**
 * Overflow is shed in this order. `headline` first: it is the only field the client can
 * rebuild from the remaining keys, so losing it costs nothing but polish. Crest URLs and
 * the league name go last — they are display data the client cannot invent.
 */
const SHRINKABLE_KEYS: readonly string[] = [
  // Both are pure polish: the client hides the league mark and falls back to `headline`
  // when the detail is absent, so they are the cheapest bytes in the payload to lose.
  'leagueLogo',
  'detail',
  'headline',
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

function buildData(input: DataInput): Record<string, string> {
  const { match, score } = input;
  return {
    v: PAYLOAD_VERSION,
    type: input.type,
    matchId: String(match.id),
    eventId: input.eventId,
    seq: String(input.sequence),
    phase: match.phase,
    minute: str(input.minute),
    extra: str(input.extra),
    homeId: String(match.home.id),
    homeName: match.home.name,
    homeShort: str(match.home.shortName),
    homeCrest: str(match.home.crestUrl),
    awayId: String(match.away.id),
    awayName: match.away.name,
    awayShort: str(match.away.shortName),
    awayCrest: str(match.away.crestUrl),
    homeScore: String(score.home),
    awayScore: String(score.away),
    leagueId: String(match.leagueId),
    leagueName: match.leagueName,
    leagueLogo: str(match.leagueLogoUrl),
    detail: str(input.detail),
    kickoffAt: String(match.kickoffAt),
    headline: input.headline,
    player: str(input.player),
    assist: str(input.assist),
    side: input.side,
    ts: String(input.nowMs),
  };
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
    // Ticks carry no event id: the client dedupes them by `seq`, not by identity.
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
