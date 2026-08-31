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

/** One provider request per team; the Android client caps its own fan-out identically. */
const MAX_TEAM_QUERIES = 10;

/** Leagues carry far more fixtures per request, so fewer calls cover the same range. */
const MAX_LEAGUE_QUERIES = 5;

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

    const [fixture] = await deps.provider.fixtures({ id: matchId });
    if (fixture === undefined) {
      throw notFound(`No fixture with id ${matchId}.`);
    }

    const match = toMatch(fixture);
    const homeTeamId = match.home.id;

    // Three more requests per detail view, so each is asked for only when it can exist:
    // a fixture that has not kicked off has no events and no statistics (its lineups are
    // published about an hour before), and a postponed one never will.
    const abandoned = match.phase === 'OFF';
    const started = !abandoned && match.phase !== 'SCHEDULED';

    const [rawEvents, rawLineups, rawStats] = await Promise.all([
      started ? optionalSection(logger, matchId, 'events', () => deps.provider.events(matchId)) : [],
      abandoned
        ? []
        : optionalSection(logger, matchId, 'lineups', () => deps.provider.lineups(matchId)),
      started
        ? optionalSection(logger, matchId, 'statistics', () => deps.provider.statistics(matchId))
        : [],
    ]);

    // Read, never bumped: `nextSequence` belongs to the poller, and a client refresh must
    // not make the payload it just fetched look newer than the push that follows it.
    const state = await deps.store.getMatchState(matchId);

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
  // `from`/`to` need a season alongside them; the range spans days, not years, so the
  // season the range *starts* in is the right label for all of it.
  const season = currentSeason(new Date(`${from}T00:00:00Z`));

  if (teamIds.length > 0) {
    return flatten(
      teamIds
        .slice(0, MAX_TEAM_QUERIES)
        .map((team) => provider.fixtures(rangeQuery({ team, season }, from, to))),
    );
  }

  if (leagueIds.length > 0) {
    return flatten(
      leagueIds
        .slice(0, MAX_LEAGUE_QUERIES)
        .map((league) => provider.fixtures(rangeQuery({ league, season }, from, to))),
    );
  }

  return flatten(enumerateDays(from, to).map((day) => provider.fixtures({ date: day })));
}

function rangeQuery(base: { team?: number; league?: number; season: number }, from: string, to: string): FixtureQuery {
  const query: FixtureQuery = { from, to, season: base.season };
  if (base.team !== undefined) query.team = base.team;
  if (base.league !== undefined) query.league = base.league;
  return query;
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
 */
function presentMatches(raw: ApiFixture[], teamIds: number[], leagueIds: number[]): MatchJson[] {
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
