/**
 * The polling engine.
 *
 * One `/fixtures?live=all` call per tick returns every in-play match in the world, which is
 * the whole reason the cadence can be this tight. Quota arithmetic, at the 30s default:
 *
 *   live poll        86400 / 30            = 2880 requests/day
 *   pre-match sweep  (1-2) * 288           =  288-576/day  (one `/fixtures?date=` per UTC
 *                                             day the lead window touches, every 5 minutes)
 *   per match        1 `/fixtures/events` per tick per *subscribed* live match, plus one
 *                    `/fixtures/lineups` per match, plus one `/fixtures?id=` when a match
 *                    drops off the live list and its final state has to be fetched.
 *
 * The per-match term is the one that can run away, so a match nobody is subscribed to is
 * never fetched at all: with three subscribed matches live for two hours a day that term is
 * ~720 requests, and the whole day lands comfortably under DAILY_REQUEST_BUDGET (7500).
 * When no subscribed match is in play the cadence drops to POLL_IDLE_INTERVAL_SECONDS,
 * which costs 288 live polls a day instead of 2880.
 *
 * Two invariants hold this together:
 *  - only the leader-lock holder polls, because Render overlaps instances across a deploy;
 *  - nothing is ever pushed unless `store.markEventSent` returned true for its id. That one
 *    call is the idempotency gate — it makes restarts, overlapping deploys and provider
 *    re-reports all collapse to exactly one notification per incident.
 */

import { config } from '../config.js';
import { createLogger, type Logger } from '../logger.js';
import {
  QuotaExhaustedError,
  type ApiFootballClient,
  type ApiFixture,
} from '../provider/apiFootball.js';
import { toEvents, toLineups, toMatch } from '../provider/mapper.js';
import { sendToTokens, type SendResult } from '../push/fcm.js';
import { eventPayload, tickPayload, type PushMessage } from '../push/payload.js';
import type { PushTarget, Store } from '../store/index.js';
import {
  eventId,
  isTerminalPhase,
  type MatchEventJson,
  type MatchEventType,
  type MatchJson,
  type SubscriptionPreferences,
  type TrackedMatchState,
} from '../types.js';
import { diffMatch, isStaleEvent } from './diff.js';

const DAY_MS = 86_400_000;
const MINUTE_MS = 60_000;

/** One or two `/fixtures?date=` calls; five minutes keeps that under 600 requests a day. */
const PREMATCH_SWEEP_INTERVAL_MS = 5 * MINUTE_MS;

/** Lineups are published on no fixed schedule, so a candidate is re-checked until it has them. */
const LINEUP_RETRY_MS = 5 * MINUTE_MS;

/** How long a lineup-attempt marker is kept for a fixture that never went live. */
const LINEUP_ATTEMPT_RETENTION_MS = 6 * 60 * MINUTE_MS;

/** Lag beyond which an incident is recorded silently instead of notified. See `isStaleEvent`. */
const STALE_EVENT_MINUTES = 10;

/** A fixture that has left the live list is chased this many ticks for its final status. */
const MAX_RESOLVE_ATTEMPTS = 3;

/** A follower re-checks for leadership at least this often, so a deploy takeover is quick. */
const LEADER_RETRY_MS = 60_000;

const MIN_LEADER_LEASE_MS = 60_000;

/**
 * `sentEventIds` is derived by the store from the sent_events table; `putMatchState` ignores
 * whatever is passed. Sharing one frozen empty set makes that explicit at each call site.
 */
const NO_EVENT_IDS: Set<string> = new Set<string>();

/**
 * "Lineups ready" has no type of its own in the client's enum, so it rides on OTHER with a
 * marker in the player slot of the id. That keeps the id deterministic and unique per match.
 */
const LINEUPS_MARKER = 'lineups';

export type PushSender = (tokens: string[], message: PushMessage) => Promise<SendResult>;

export interface LivePollerSettings {
  readonly pollEnabled: boolean;
  readonly pollIntervalSeconds: number;
  readonly pollIdleIntervalSeconds: number;
  readonly preMatchLeadMinutes: number;
}

export interface LivePollerOptions {
  readonly client: ApiFootballClient;
  readonly store: Store;
  readonly logger?: Logger | undefined;
  readonly settings?: Partial<LivePollerSettings> | undefined;
  /** Injectable so a test can assert on pushes without firebase-admin or a network. */
  readonly send?: PushSender | undefined;
  readonly now?: (() => number) | undefined;
}

export interface TickSummary {
  liveMatches: number;
  /** Live matches at least one device subscribes to; the only ones that cost requests. */
  trackedMatches: number;
  newEvents: number;
  durablePushes: number;
  tickPushes: number;
  prunedTokens: number;
  errors: number;
  /** True when the daily budget is spent and the tick did no provider work at all. */
  quotaBlocked: boolean;
  durationMs: number;
}

export interface PollerStatus {
  running: boolean;
  leader: boolean;
  trackedMatches: number;
  quotaBlocked: boolean;
  lastTickAt?: number | undefined;
  lastTickDurationMs?: number | undefined;
  lastError?: string | undefined;
}

interface TrackedFixture {
  match: MatchJson;
  /** Consecutive ticks the fixture has been absent from `live=all`. */
  missedTicks: number;
}

interface TickContext {
  summary: TickSummary;
  /** Collected across the whole tick, so the store is hit once rather than per send. */
  invalidTokens: Set<string>;
}

/**
 * Which preference flag gates a given incident. VAR rides with goals because every VAR event
 * we mint is a scoreline correction, and OTHER rides with lineups because the only OTHER we
 * ever push is "lineups ready".
 */
export function wantsEvent(preferences: SubscriptionPreferences, type: MatchEventType): boolean {
  switch (type) {
    case 'GOAL':
    case 'OWN_GOAL':
    case 'PENALTY_GOAL':
    case 'PENALTY_MISSED':
    case 'VAR':
      return preferences.goals;
    case 'YELLOW_CARD':
    case 'SECOND_YELLOW':
    case 'RED_CARD':
      return preferences.cards;
    case 'SUBSTITUTION':
      return preferences.substitutions;
    case 'KICK_OFF':
    case 'HALF_TIME':
    case 'FULL_TIME':
      return preferences.kickoffAndFullTime;
    case 'OTHER':
      return preferences.lineups;
    default:
      return false;
  }
}

/** `YYYY-MM-DD` in UTC — the only timezone `/fixtures?date=` is unambiguous in. */
export function utcDate(epochMs: number): string {
  return new Date(epochMs).toISOString().slice(0, 10);
}

/** The one or two UTC days a [start, end] window touches. */
export function utcDatesInWindow(startMs: number, endMs: number): string[] {
  const first = utcDate(startMs);
  const last = utcDate(endMs);
  return first === last ? [first] : [first, last];
}

function lineupsEvent(matchId: number, formations: string): MatchEventJson {
  return {
    id: eventId(matchId, 'OTHER', undefined, undefined, LINEUPS_MARKER),
    type: 'OTHER',
    side: 'NEUTRAL',
    detail: 'Lineups are in',
    comment: formations.length > 0 ? formations : undefined,
  };
}

export class LivePoller {
  readonly #client: ApiFootballClient;
  readonly #store: Store;
  readonly #logger: Logger;
  readonly #settings: LivePollerSettings;
  readonly #send: PushSender;
  readonly #now: () => number;
  readonly #leaseMs: number;

  /** Live matches with at least one subscriber, carried between ticks. */
  readonly #tracked = new Map<number, TrackedFixture>();
  /** Last `/fixtures/lineups` attempt per match, so an unpublished sheet is not re-fetched every tick. */
  readonly #lineupAttempts = new Map<number, number>();

  #running = false;
  #leader = false;
  #timer: NodeJS.Timeout | undefined;
  #tickInFlight: Promise<TickSummary> | undefined;
  #lastSweepAt = 0;
  /** UTC day index the budget was exhausted on; cleared by the rollover, like the client's own counter. */
  #quotaBlockedDay: number | undefined;
  #lastTickAt: number | undefined;
  #lastTickDurationMs: number | undefined;
  #lastError: string | undefined;

  constructor(options: LivePollerOptions) {
    this.#client = options.client;
    this.#store = options.store;
    this.#logger = options.logger ?? createLogger({ component: 'poller' });
    this.#settings = {
      pollEnabled: options.settings?.pollEnabled ?? config.pollEnabled,
      pollIntervalSeconds: options.settings?.pollIntervalSeconds ?? config.pollIntervalSeconds,
      pollIdleIntervalSeconds:
        options.settings?.pollIdleIntervalSeconds ?? config.pollIdleIntervalSeconds,
      preMatchLeadMinutes: options.settings?.preMatchLeadMinutes ?? config.preMatchLeadMinutes,
    };
    this.#send = options.send ?? sendToTokens;
    this.#now = options.now ?? Date.now;
    // The lease is the window in which a process may still believe it leads after its lock
    // connection died, so it has to outlast a slow tick but stay short enough that a crashed
    // leader is replaced within a poll or two.
    this.#leaseMs = Math.max(MIN_LEADER_LEASE_MS, this.#settings.pollIntervalSeconds * 3000);
  }

  start(): void {
    if (!this.#settings.pollEnabled) {
      this.#logger.info('poller disabled (POLL_ENABLED=false); no fixtures will be fetched');
      return;
    }
    if (this.#running) return;
    this.#running = true;
    this.#logger.info('poller starting', {
      pollIntervalSeconds: this.#settings.pollIntervalSeconds,
      pollIdleIntervalSeconds: this.#settings.pollIdleIntervalSeconds,
      preMatchLeadMinutes: this.#settings.preMatchLeadMinutes,
      leaseMs: this.#leaseMs,
    });
    this.#schedule(0);
  }

  /** Stops the loop, waits for a tick already in flight, and hands leadership back. */
  async stop(): Promise<void> {
    if (!this.#running && this.#timer === undefined) return;
    this.#running = false;
    if (this.#timer !== undefined) {
      clearTimeout(this.#timer);
      this.#timer = undefined;
    }
    if (this.#tickInFlight !== undefined) {
      await this.#tickInFlight.catch(() => undefined);
    }
    if (this.#leader) {
      this.#leader = false;
      // Released explicitly rather than left to the lease: a rolling deploy that waits for
      // the lock to expire is a deploy with no notifications for a minute.
      await this.#store.releaseLeaderLock().catch((error: unknown) => {
        this.#logger.warn('releasing the leader lock failed', { error });
      });
    }
    this.#logger.info('poller stopped');
  }

  getStatus(): PollerStatus {
    return {
      running: this.#running,
      leader: this.#leader,
      trackedMatches: this.#tracked.size,
      // Read without the rollover check `#quotaBlocked` performs: reporting status must not
      // clear the flag, that belongs to the tick.
      quotaBlocked: this.#quotaBlockedDay === Math.floor(this.#now() / DAY_MS),
      lastTickAt: this.#lastTickAt,
      lastTickDurationMs: this.#lastTickDurationMs,
      lastError: this.#lastError,
    };
  }

  /**
   * One full tick, independent of the loop and of leadership — the loop calls it, and so can
   * an admin endpoint or a test. Never throws: a tick that dies takes the next one with it.
   */
  async pollOnce(): Promise<TickSummary> {
    const started = this.#now();
    const summary: TickSummary = {
      liveMatches: 0,
      trackedMatches: 0,
      newEvents: 0,
      durablePushes: 0,
      tickPushes: 0,
      prunedTokens: 0,
      errors: 0,
      quotaBlocked: false,
      durationMs: 0,
    };
    const context: TickContext = { summary, invalidTokens: new Set<string>() };

    if (this.#quotaBlocked()) {
      summary.quotaBlocked = true;
      summary.durationMs = this.#now() - started;
      this.#logger.debug('tick skipped: daily request budget exhausted');
      return summary;
    }

    try {
      await this.#pollLive(context);
      await this.#resolveDeparted(context);
      await this.#preMatchSweep(context);
      this.#lastError = undefined;
    } catch (error) {
      if (error instanceof QuotaExhaustedError) {
        this.#blockForToday(error);
        summary.quotaBlocked = true;
      } else {
        summary.errors += 1;
        this.#lastError = error instanceof Error ? error.message : String(error);
        this.#logger.error('poll tick aborted', { error });
      }
    }

    await this.#pruneTokens(context);

    summary.trackedMatches = this.#tracked.size;
    summary.durationMs = this.#now() - started;
    this.#lastTickAt = started;
    this.#lastTickDurationMs = summary.durationMs;

    const line = {
      liveMatches: summary.liveMatches,
      trackedMatches: summary.trackedMatches,
      newEvents: summary.newEvents,
      durablePushes: summary.durablePushes,
      tickPushes: summary.tickPushes,
      prunedTokens: summary.prunedTokens,
      errors: summary.errors,
      durationMs: summary.durationMs,
    };
    // A quiet tick every 30 seconds is noise at info level; anything that moved is not.
    if (summary.durablePushes > 0 || summary.errors > 0) this.#logger.info('poll tick', line);
    else this.#logger.debug('poll tick', line);

    return summary;
  }

  /* ---------------------------------------------------------------------- */
  /* Loop                                                                    */
  /* ---------------------------------------------------------------------- */

  #schedule(delayMs: number): void {
    if (!this.#running) return;
    this.#timer = setTimeout(() => {
      this.#timer = undefined;
      void this.#loop();
    }, delayMs);
    // Unref'd: the HTTP listener is what keeps the process alive, and a forgotten poller
    // must never be the reason a test run or a shutdown hangs.
    this.#timer.unref();
  }

  async #loop(): Promise<void> {
    if (!this.#running) return;
    let delayMs = this.#idleMs();

    try {
      const leader = await this.#store.acquireLeaderLock(this.#leaseMs);
      if (!leader) {
        if (this.#leader) {
          this.#leader = false;
          this.#logger.warn('leader lock lost; another instance is polling');
        } else {
          this.#logger.debug('not the leader; skipping tick');
        }
        // Followers do nothing but re-check, so this is cheap however often it runs.
        delayMs = Math.min(LEADER_RETRY_MS, this.#idleMs());
      } else {
        if (!this.#leader) {
          this.#leader = true;
          this.#logger.info('leader lock held; this instance is polling');
        }
        const tick = this.pollOnce();
        this.#tickInFlight = tick;
        const summary = await tick;
        // Adaptive cadence: the fast interval only while something we can push about is on
        // the pitch. A world full of live football nobody subscribed to stays on the idle one.
        delayMs =
          summary.trackedMatches > 0 && !summary.quotaBlocked ? this.#liveMs() : this.#idleMs();
      }
    } catch (error) {
      this.#lastError = error instanceof Error ? error.message : String(error);
      this.#logger.error('poll loop iteration failed', { error });
    } finally {
      this.#tickInFlight = undefined;
      // Self-scheduling rather than setInterval: a tick that runs long delays the next one
      // instead of overlapping it, which would double every push it produced.
      this.#schedule(delayMs);
    }
  }

  #liveMs(): number {
    return this.#settings.pollIntervalSeconds * 1000;
  }

  #idleMs(): number {
    return this.#settings.pollIdleIntervalSeconds * 1000;
  }

  /* ---------------------------------------------------------------------- */
  /* Quota                                                                   */
  /* ---------------------------------------------------------------------- */

  #quotaBlocked(): boolean {
    if (this.#quotaBlockedDay === undefined) return false;
    if (Math.floor(this.#now() / DAY_MS) === this.#quotaBlockedDay) return true;
    // Same rollover the client's own counter uses (00:00 UTC), so the two never disagree
    // about whether there is budget left.
    this.#quotaBlockedDay = undefined;
    this.#logger.info('daily request budget reset; resuming normal polling');
    return false;
  }

  #blockForToday(error: QuotaExhaustedError): void {
    const day = Math.floor(this.#now() / DAY_MS);
    if (this.#quotaBlockedDay === day) return;
    this.#quotaBlockedDay = day;
    this.#lastError = error.message;
    this.#logger.error(
      'daily request budget exhausted; backing off to the idle cadence until it resets',
      { used: error.used, budget: error.budget, resetsAt: error.resetsAt.toISOString() },
    );
  }

  /* ---------------------------------------------------------------------- */
  /* Live poll                                                               */
  /* ---------------------------------------------------------------------- */

  async #pollLive(context: TickContext): Promise<void> {
    const fixtures = await this.#client.liveFixtures();
    context.summary.liveMatches = fixtures.length;

    const liveIds = new Set<number>();
    for (const raw of fixtures) {
      const match: MatchJson = toMatch(raw);
      if (match.id <= 0) continue;
      liveIds.add(match.id);

      try {
        const targets = await this.#store.tokensForMatch(match);
        if (targets.length === 0) {
          // Nobody is listening: no events call, no state row, no cost.
          this.#tracked.delete(match.id);
          continue;
        }
        this.#tracked.set(match.id, { match, missedTicks: 0 });
        await this.#processMatch(match, targets, context);
      } catch (error) {
        // One match's failure must never cost the other forty their notifications; a spent
        // budget is the exception, because every later call in the tick would fail too.
        if (error instanceof QuotaExhaustedError) throw error;
        context.summary.errors += 1;
        this.#logger.error('match tick failed', { matchId: match.id, error });
      }
    }

    for (const [matchId, tracked] of this.#tracked) {
      if (!liveIds.has(matchId)) tracked.missedTicks += 1;
    }
  }

  /**
   * `live=all` drops a fixture the moment it is over, so full-time would otherwise never be
   * observed. Each departed fixture is fetched by id once to read its final status, then
   * forgotten. A fixture that has merely flickered out of the live feed is retried a couple
   * of times before being dropped, so a provider blip does not cost a full-time push.
   */
  async #resolveDeparted(context: TickContext): Promise<void> {
    const departed = [...this.#tracked.values()].filter((tracked) => tracked.missedTicks > 0);

    for (const tracked of departed) {
      const matchId = tracked.match.id;
      try {
        const [raw] = await this.#client.fixtures({ id: matchId });
        const match: MatchJson | undefined = raw === undefined ? undefined : toMatch(raw);
        if (match === undefined) {
          if (tracked.missedTicks >= MAX_RESOLVE_ATTEMPTS) this.#tracked.delete(matchId);
          continue;
        }

        const targets = await this.#store.tokensForMatch(match);
        if (targets.length > 0) await this.#processMatch(match, targets, context);

        if (isTerminalPhase(match.phase) || tracked.missedTicks >= MAX_RESOLVE_ATTEMPTS) {
          this.#tracked.delete(matchId);
          this.#lineupAttempts.delete(matchId);
        } else {
          tracked.match = match;
        }
      } catch (error) {
        if (error instanceof QuotaExhaustedError) throw error;
        context.summary.errors += 1;
        this.#logger.error('resolving a departed fixture failed', { matchId, error });
        if (tracked.missedTicks >= MAX_RESOLVE_ATTEMPTS) this.#tracked.delete(matchId);
      }
    }
  }

  /* ---------------------------------------------------------------------- */
  /* Per-match work                                                          */
  /* ---------------------------------------------------------------------- */

  async #processMatch(
    match: MatchJson,
    targets: PushTarget[],
    context: TickContext,
  ): Promise<void> {
    const previous = await this.#store.getMatchState(match.id);
    const events: MatchEventJson[] = toEvents(match.id, match.home.id, await this.#client.events(match.id));
    const diff = diffMatch(previous, match, events);
    context.summary.newEvents += diff.newEvents.length;

    // Tokens already reached by a durable push this tick. The durable payload carries the
    // whole scoreline and clock, so sending those devices a tick as well is pure duplication.
    const notified = new Set<string>();
    let sequence = previous?.lastSequence ?? 0;

    if (previous === undefined) {
      // First sighting: whatever is already in the feed is history. Recording it (instead of
      // pushing it) is what stops a restart mid-match from replaying an hour of goals.
      await this.#seed(match, diff.newEvents);
    } else {
      for (const event of [...diff.newEvents, ...diff.syntheticEvents]) {
        const delivered = await this.#deliverEvent(match, event, targets, context, notified);
        if (delivered !== undefined) sequence = delivered;
      }
    }

    const lineupsSent = await this.#maybeDeliverLineups(match, targets, previous, context, notified);

    const clockChanged = previous?.elapsed !== match.elapsed;
    if (diff.phaseChanged || diff.scoreChanged || clockChanged || previous === undefined) {
      const recipients = targets.filter((target) => !notified.has(target.token));
      if (recipients.length > 0) {
        sequence = await this.#store.nextSequence(match.id);
        const result = await this.#send(
          recipients.map((target) => target.token),
          tickPayload(match, sequence, this.#now()),
        );
        this.#collect(result, context);
        context.summary.tickPushes += 1;
      }
    }

    await this.#store.putMatchState({
      matchId: match.id,
      phase: match.phase,
      score: match.score,
      elapsed: match.elapsed,
      lastSequence: sequence,
      sentEventIds: NO_EVENT_IDS,
      lineupsSent: (previous?.lineupsSent ?? false) || lineupsSent,
    });
  }

  /** Claims every id without notifying, so none of them can ever fire later. */
  async #seed(match: MatchJson, events: MatchEventJson[]): Promise<void> {
    let seeded = 0;
    for (const event of events) {
      if (await this.#store.markEventSent(event.id, match.id)) seeded += 1;
    }
    if (seeded > 0) {
      this.#logger.info('match seen for the first time; existing events recorded, not pushed', {
        matchId: match.id,
        seeded,
        phase: match.phase,
        elapsed: match.elapsed,
      });
    }
  }

  /**
   * Returns the sequence it allocated, or undefined when nothing was pushed.
   *
   * `markEventSent` is the gate and it is called before anything is sent: it returns true
   * exactly once per id across every process sharing the database, so a restart, an
   * overlapping deploy or a provider re-report cannot produce a second notification. The
   * claim is kept even when nobody's preferences match, which is deliberate — the incident
   * has been dealt with, and re-examining it on every later tick would only risk pushing it
   * once someone's preferences change.
   */
  async #deliverEvent(
    match: MatchJson,
    event: MatchEventJson,
    targets: PushTarget[],
    context: TickContext,
    notified: Set<string>,
  ): Promise<number | undefined> {
    if (isStaleEvent(event, match, STALE_EVENT_MINUTES)) {
      await this.#store.markEventSent(event.id, match.id);
      this.#logger.debug('event too far behind the clock to notify; recorded only', {
        matchId: match.id,
        eventId: event.id,
        minute: event.minute,
        elapsed: match.elapsed,
      });
      return undefined;
    }

    if (!(await this.#store.markEventSent(event.id, match.id))) return undefined;

    const recipients = targets.filter((target) => wantsEvent(target.preferences, event.type));
    if (recipients.length === 0) return undefined;

    const sequence = await this.#store.nextSequence(match.id);
    const tokens = recipients.map((target) => target.token);
    const result = await this.#send(tokens, eventPayload(match, event, sequence, this.#now()));
    this.#collect(result, context);
    for (const token of tokens) notified.add(token);
    context.summary.durablePushes += 1;
    return sequence;
  }

  /* ---------------------------------------------------------------------- */
  /* Lineups                                                                 */
  /* ---------------------------------------------------------------------- */

  #mayFetchLineups(matchId: number): boolean {
    const last = this.#lineupAttempts.get(matchId);
    return last === undefined || this.#now() - last >= LINEUP_RETRY_MS;
  }

  /**
   * At most one lineups push per match, ever: `lineupsSent` latches in the store and is the
   * durable half of that guarantee, the attempt map only stops an unpublished sheet from
   * being re-fetched on every tick.
   */
  async #maybeDeliverLineups(
    match: MatchJson,
    targets: PushTarget[],
    previous: TrackedMatchState | undefined,
    context: TickContext,
    notified: Set<string>,
  ): Promise<boolean> {
    if (previous?.lineupsSent === true) return false;

    const recipients = targets.filter((target) => target.preferences.lineups);
    if (recipients.length === 0) return false;
    if (!this.#mayFetchLineups(match.id)) return false;
    this.#lineupAttempts.set(match.id, this.#now());

    const raw = await this.#client.lineups(match.id);
    // Empty until the teams are announced, which is not an error and not a state change.
    if (raw.length === 0) return false;

    const { homeLineup, awayLineup } = toLineups(match.home.id, raw);
    const formations = [homeLineup?.formation, awayLineup?.formation].filter(
      (formation): formation is string => typeof formation === 'string' && formation.length > 0,
    );
    const event = lineupsEvent(match.id, formations.join(' v '));

    if (!(await this.#store.markEventSent(event.id, match.id))) {
      // Another instance pushed it; latch the flag so this one stops fetching lineups too.
      return true;
    }

    const sequence = await this.#store.nextSequence(match.id);
    const tokens = recipients.map((target) => target.token);
    const result = await this.#send(tokens, eventPayload(match, event, sequence, this.#now()));
    this.#collect(result, context);
    for (const token of tokens) notified.add(token);
    context.summary.durablePushes += 1;
    this.#logger.info('lineups pushed', {
      matchId: match.id,
      formations: formations.join(' v '),
      recipients: tokens.length,
    });
    return true;
  }

  /* ---------------------------------------------------------------------- */
  /* Pre-match sweep                                                         */
  /* ---------------------------------------------------------------------- */

  /**
   * Matches kicking off within PREMATCH_LEAD_MINUTES: fetch the sheet as soon as it exists,
   * push it once, and — just as importantly — write the SCHEDULED state row.
   *
   * That row is what makes kick-off detectable at all: `live=all` only ever shows a match
   * that is already in play, so without a prior state to compare against the first live tick
   * is a first sighting and no KICK_OFF is ever derived from it.
   */
  async #preMatchSweep(context: TickContext): Promise<void> {
    const leadMinutes = this.#settings.preMatchLeadMinutes;
    if (leadMinutes <= 0) return;

    const now = this.#now();
    if (now - this.#lastSweepAt < PREMATCH_SWEEP_INTERVAL_MS) return;
    this.#lastSweepAt = now;

    // A fixture that was swept but never went live (postponed, or its last subscriber left)
    // would keep its attempt marker for the lifetime of the process; drop the old ones here.
    for (const [matchId, attemptedAt] of this.#lineupAttempts) {
      if (now - attemptedAt > LINEUP_ATTEMPT_RETENTION_MS) this.#lineupAttempts.delete(matchId);
    }

    const windowEndMs = now + leadMinutes * MINUTE_MS;
    const fixtures: ApiFixture[] = [];
    for (const date of utcDatesInWindow(now, windowEndMs)) {
      fixtures.push(...(await this.#client.fixtures({ date })));
    }

    let candidates = 0;
    for (const raw of fixtures) {
      const match: MatchJson = toMatch(raw);
      if (match.id <= 0 || match.phase !== 'SCHEDULED') continue;
      const kickoffMs = match.kickoffAt * 1000;
      if (kickoffMs < now || kickoffMs > windowEndMs) continue;

      try {
        const targets = await this.#store.tokensForMatch(match);
        if (targets.length === 0) continue;
        candidates += 1;

        const previous = await this.#store.getMatchState(match.id);
        // Each device chose its own lead time; the sweep window is only the outer bound.
        const due = targets.filter(
          (target) => kickoffMs - now <= target.preferences.preMatchLeadMinutes * MINUTE_MS,
        );
        const lineupsSent = await this.#maybeDeliverLineups(
          match,
          due,
          previous,
          context,
          new Set<string>(),
        );

        await this.#store.putMatchState({
          matchId: match.id,
          phase: match.phase,
          score: match.score,
          elapsed: match.elapsed,
          lastSequence: previous?.lastSequence ?? 0,
          sentEventIds: NO_EVENT_IDS,
          lineupsSent: (previous?.lineupsSent ?? false) || lineupsSent,
        });
      } catch (error) {
        if (error instanceof QuotaExhaustedError) throw error;
        context.summary.errors += 1;
        this.#logger.error('pre-match sweep failed for a fixture', { matchId: match.id, error });
      }
    }

    this.#logger.debug('pre-match sweep', {
      fixtures: fixtures.length,
      candidates,
      leadMinutes,
    });
  }

  /* ---------------------------------------------------------------------- */
  /* Token hygiene                                                           */
  /* ---------------------------------------------------------------------- */

  #collect(result: SendResult, context: TickContext): void {
    for (const token of result.invalidTokens) context.invalidTokens.add(token);
  }

  /** Tokens FCM said it will never accept again: deleting them is the only way the table shrinks. */
  async #pruneTokens(context: TickContext): Promise<void> {
    if (context.invalidTokens.size === 0) return;
    try {
      const removed = await this.#store.removeTokens([...context.invalidTokens]);
      context.summary.prunedTokens = removed;
      this.#logger.info('pruned unregistered device tokens', {
        reported: context.invalidTokens.size,
        removed,
      });
    } catch (error) {
      context.summary.errors += 1;
      this.#logger.error('pruning device tokens failed', { error });
    }
  }
}

export function createLivePoller(options: LivePollerOptions): LivePoller {
  return new LivePoller(options);
}
