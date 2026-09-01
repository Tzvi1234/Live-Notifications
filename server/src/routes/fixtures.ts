/**
 * Fixture lists and the match detail composite.
 *
 * Quota is the design constraint. `/fixtures?date=` returns every fixture in the world for
 * that day in one cacheable request, so a day query is answered with one call and filtered
 * here; a *range* is what costs money, because the provider only accepts `from`/`to` next
 * to a team or a league. The branches below pick whichever plan is cheapest for the filters
 * that were actually sent.
 */

import express, { type Request, type Response, type Router } from 'express';

import type { ApiDeps } from './deps.js';
import {
  ProviderError,
  QuotaExhaustedError,
  type ApiFixture,
  type ApiFootballClient,
  type FixtureQuery,
} from '../provider/apiFootball.js';
import { currentSeason, toEvents, toLineups, toMatch, toStats } from '../provider/mapper.js';
import type { Logger } from '../logger.js';
import type { MatchDetailJson, MatchJson, MatchListJson } from '../types.js';
import {
  addDays,
  badRequest,
  notFound,
  parseDate,
  parseIdList,
  requirePositiveInt,
  todayIso,
} from './validation.js';

/**
 * Ceilings on the *requests* a range costs, not on the ids in it: a window straddling a
 * season rollover needs one call per season per id, so counting teams would let a single
 * refresh quietly spend double. Ids that no longer fit are dropped from the end of the list.
 */
/**
 * How long one match's detail is held.
 *
 * Ten seconds against a twenty-second client poll: short enough that a goal is never more
 * than a tick late, long enough that a hundred devices watching the same match cost one
 * upstream call rather than a hundred.
 */
const LIVE_DETAIL_TTL_SECONDS = 10;

const MAX_RANGE_REQUESTS_BY_TEAM = 10;

/** Leagues carry far more fixtures per request, so fewer calls cover the same range. */
const MAX_RANGE_REQUESTS_BY_LEAGUE = 5;

/**
 * A range with neither teams nor leagues has to be walked one day at a time, one request
 * per day. Eight days is a fortnight's worth of "this week" screens and still one tick's
 * worth of budget; past that the caller has to say what it actually wants.
 */
const MAX_RANGE_DAYS = 8;

export function createFixturesRouter(deps: ApiDeps): Router {
  const router = express.Router();
  const logger = deps.logger.child({ component: 'routes.fixtures' });

  router.get('/fixtures', async (req: Request, res: Response) => {
    const date = parseDate(req.query.date, 'date');
    const from = parseDate(req.query.from, 'from');
    const to = parseDate(req.query.to, 'to');
    const teamIds = parseIdList(req.query.teams, 'teams');
    const leagueIds = parseIdList(req.query.leagues, 'leagues');

    if ((from === undefined) !== (to === undefined)) {
      throw badRequest('Query parameters "from" and "to" must be provided together.');
    }
    if (from !== undefined && to !== undefined && to < from) {
      throw badRequest('Query parameter "to" must not be earlier than "from".');
    }

    const raw =
      from === undefined || to === undefined || date !== undefined
        ? // A single day is always one request, whatever the filters: they are applied below.
          await deps.provider.fixtures({ date: date ?? todayIso() })
        : await fetchRange(deps.provider, from, to, teamIds, leagueIds);

    const body: MatchListJson = { matches: presentMatches(raw, teamIds, leagueIds) };
    res.json(body);
  });

  router.get('/fixtures/live', async (req: Request, res: Response) => {
    const teamIds = parseIdList(req.query.teams, 'teams');

    // `live=all` is one request for every in-play match on earth. Narrowing it server-side
    // would save nothing and would cost a second request the moment a team plays outside
    // the narrowed set, so the filter is applied here.
    const raw = await deps.provider.liveFixtures();

    const body: MatchListJson = { matches: presentMatches(raw, teamIds, []) };
    res.json(body);
  });

  router.get('/matches/:id', async (req: Request, res: Response) => {
    const matchId = requirePositiveInt(req.params.id, 'id');

    // `fixtureById`, not `fixtures({ id })`. They hit the same upstream path, but the by-id
    // method is the cacheable one, and this is the single most-repeated request in the app:
    // an open match screen re-reads it every twenty seconds, per device. Through
    // `fixtures({ id })` every one of those was a fresh upstream call with no cache, no
    // in-flight coalescing and no stale fallback - so the one call the whole view depends
    // on was also the only one with no safety net under it. The TTL is short because the
    // thing it holds is a live scoreline; short is still the difference between one call
    // and one call per device.
    const fixture = await deps.provider.fixtureById(matchId, LIVE_DETAIL_TTL_SECONDS);
    if (fixture === undefined) {
      throw notFound(`No fixture with id ${matchId}.`);
    }

    const match = toMatch(fixture);
    const homeTeamId = match.home.id;

    // `/fixtures?id=` already carries events, lineups and statistics INLINE, so in the
    // normal case this whole view costs the one request above. The dedicated endpoints are
    // only reached when a section is absent from that response — an older edge cache, or a
    // plan whose coverage omits it — and even then each is asked for only when it can
    // exist: a fixture that has not kicked off has no events and no statistics (its lineups
    // are published about an hour before), and a postponed one never will.
    const abandoned = match.phase === 'OFF';
    const started = !abandoned && match.phase !== 'SCHEDULED';

    const [rawEvents, rawLineups, rawStats] = await Promise.all([
      fixture.events ??
        (started
          ? optionalSection(logger, matchId, 'events', () => deps.provider.events(matchId))
          : []),
      fixture.lineups ??
        (abandoned
          ? []
          : optionalSection(logger, matchId, 'lineups', () => deps.provider.lineups(matchId))),
      fixture.statistics ??
        (started
          ? optionalSection(logger, matchId, 'statistics', () => deps.provider.statistics(matchId))
          : []),
    ]);

    // Read, never bumped: `nextSequence` belongs to the poller, and a client refresh must
    // not make the payload it just fetched look newer than the push that follows it.
    //
    // And never fatal. This decorates the response with a sequence number the client uses
    // to order pushes; the match itself came from the provider and is complete without it.
    // When the database went missing this one optional read was turning a perfectly good
    // fixture into a 500.
    const state = await optionalState(logger, matchId, () => deps.store.getMatchState(matchId));

    const detail: MatchDetailJson = {
      match,
      events: toEvents(matchId, homeTeamId, rawEvents),
      ...toLineups(homeTeamId, rawLineups),
      ...toStats(homeTeamId, rawStats),
      sequence: state?.lastSequence ?? 0,
    };
    res.json(detail);
  });

  return router;
}

/**
 * A detail section that failed upstream is dropped rather than failing the whole view: a
 * missing statistics feed should not hide the score. A blown budget still propagates —
 * that one is not a per-section problem and the client has to be told to back off.
 */
/**
 * A store read that decorates a response rather than being it.
 *
 * The sequence number lets a client order this payload against the pushes that follow it;
 * the fixture came from the provider and is complete without one. When the database
 * disappeared, this single optional read was turning every match screen into a 500.
 */
async function optionalState<T>(
  logger: Logger,
  matchId: number,
  read: () => Promise<T | undefined>,
): Promise<T | undefined> {
  try {
    return await read();
  } catch (error) {
    logger.warn('match state unavailable', { matchId, error });
    return undefined;
  }
}

async function optionalSection<T>(
  logger: Logger,
  matchId: number,
  section: string,
  fetchSection: () => Promise<T[]>,
): Promise<T[]> {
  try {
    return await fetchSection();
  } catch (error) {
    if (error instanceof QuotaExhaustedError || !(error instanceof ProviderError)) throw error;
    logger.warn('match detail section unavailable', { matchId, section, error });
    return [];
  }
}

async function fetchRange(
  provider: ApiFootballClient,
  from: string,
  to: string,
  teamIds: number[],
  leagueIds: number[],
): Promise<ApiFixture[]> {
  const seasons = seasonsInRange(from, to);

  if (teamIds.length > 0) {
    return flatten(
      fanOut(teamIds, seasons, MAX_RANGE_REQUESTS_BY_TEAM, (team, season) => ({
        from,
        to,
        season,
        team,
      })).map((query) => provider.fixtures(query)),
    );
  }

  if (leagueIds.length > 0) {
    return flatten(
      fanOut(leagueIds, seasons, MAX_RANGE_REQUESTS_BY_LEAGUE, (league, season) => ({
        from,
        to,
        season,
        league,
      })).map((query) => provider.fixtures(query)),
    );
  }

  return flatten(enumerateDays(from, to).map((day) => provider.fixtures({ date: day })));
}

/**
 * The season labels a [from, to] window touches. `from`/`to` are only accepted alongside a
 * season, and a label turns over on 1 July — so the app's default fortnight-ahead window
 * spans two of them for a fortnight each summer, and asking for only the earlier one loses
 * every fixture of the season about to start.
 */
function seasonsInRange(from: string, to: string): number[] {
  const first = currentSeason(new Date(`${from}T00:00:00Z`));
  const last = currentSeason(new Date(`${to}T00:00:00Z`));
  const seasons: number[] = [];
  for (let season = first; season <= last; season += 1) seasons.push(season);
  return seasons;
}

/**
 * id x season, stopped at `maxRequests` so a straddling range cannot quietly double what it
 * spends. An id is either queried for every season the range touches or left out entirely:
 * half a team's window is indistinguishable from a team with no fixtures, and the client
 * would cache the gap.
 */
function fanOut(
  ids: number[],
  seasons: number[],
  maxRequests: number,
  build: (id: number, season: number) => FixtureQuery,
): FixtureQuery[] {
  if (seasons.length > maxRequests) {
    throw badRequest(
      `A range spanning ${seasons.length} seasons is too wide to answer; ` +
        'request one season at a time.',
    );
  }

  const queries: FixtureQuery[] = [];
  for (const id of ids) {
    if (queries.length + seasons.length > maxRequests) break;
    for (const season of seasons) queries.push(build(id, season));
  }
  return queries;
}

async function flatten(requests: Array<Promise<ApiFixture[]>>): Promise<ApiFixture[]> {
  const batches = await Promise.all(requests);
  return batches.flat();
}

function enumerateDays(from: string, to: string): string[] {
  const days: string[] = [];
  for (let cursor = from; cursor <= to; cursor = addDays(cursor, 1)) {
    if (days.length === MAX_RANGE_DAYS) {
      throw badRequest(
        `A range longer than ${MAX_RANGE_DAYS} days must be narrowed with "teams" or "leagues"; ` +
          'without them the provider charges one request per day.',
      );
    }
    days.push(cursor);
  }
  return days;
}

/**
 * Provider fixtures -> the client's list shape: mapped, de-duplicated (per-team range
 * queries return a derby twice), filtered, and ordered by kick-off so the app can render
 * the array as it arrives.
 *
 * Exported because every list endpoint owes the client the same guarantees; a second copy
 * of it elsewhere would be a second place for the ordering or the de-duplication to drift.
 */
export function presentMatches(raw: ApiFixture[], teamIds: number[], leagueIds: number[]): MatchJson[] {
  const teams = new Set(teamIds);
  const leagues = new Set(leagueIds);

  const byId = new Map<number, MatchJson>();
  for (const fixture of raw) {
    const match = toMatch(fixture);
    if (match.id <= 0) continue;
    if (teams.size > 0 && !teams.has(match.home.id) && !teams.has(match.away.id)) continue;
    if (leagues.size > 0 && !leagues.has(match.leagueId)) continue;
    byId.set(match.id, match);
  }

  return [...byId.values()].sort((a, b) => a.kickoffAt - b.kickoffAt || a.id - b.id);
}
