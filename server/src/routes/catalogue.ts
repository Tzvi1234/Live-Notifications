/**
 * The static-ish catalogue the app browses: leagues and teams.
 *
 * Both endpoints are backed by the provider's cacheable calls, so a screenful of users
 * costs one upstream request per CACHE_TTL_SECONDS rather than one per user.
 */

import express, { type Request, type Response, type Router } from 'express';

import type { ApiDeps } from './deps.js';
import type { TeamQuery } from '../provider/apiFootball.js';
import { currentSeason, toLeagues, toTeam } from '../provider/mapper.js';
import type { LeagueJson, LeagueListJson, TeamJson, TeamListJson } from '../types.js';
import {
  MIN_SEARCH_LENGTH,
  badRequest,
  parseBooleanFlag,
  parsePositiveInt,
  queryValue,
} from './validation.js';

export function createCatalogueRouter(deps: ApiDeps): Router {
  const router = express.Router();

  router.get('/leagues', async (req: Request, res: Response) => {
    const featured = parseBooleanFlag(req.query.featured, 'featured') ?? false;

    // `current=true` keeps the catalogue to this season's editions. The unfiltered call
    // returns every league crossed with every season it has ever run, which is megabytes
    // the app throws away.
    const raw = await deps.provider.leagues(true);
    const leagues = toLeagues(raw);

    const body: LeagueListJson = {
      leagues: featured ? pickFeatured(leagues, deps.config.featuredLeagueIds) : leagues,
    };
    res.json(body);
  });

  router.get('/teams', async (req: Request, res: Response) => {
    const league = parsePositiveInt(req.query.league, 'league');
    const season = parsePositiveInt(req.query.season, 'season');
    const search = queryValue(req.query.q);

    // The app searches as the user types and the provider rejects prefixes shorter than
    // three characters, so a short term is dropped instead of failing the request: with a
    // league alongside it the browse list still comes back, and without one an empty list
    // beats an error toast on every second keystroke.
    const term = search !== undefined && search.length >= MIN_SEARCH_LENGTH ? search : undefined;

    if (term === undefined && league === undefined) {
      if (search !== undefined) {
        const empty: TeamListJson = { teams: [] };
        res.json(empty);
        return;
      }
      throw badRequest('Provide "league" (with an optional "season") or a "q" search term.');
    }

    // Built key by key: `TeamQuery` uses bare optional properties, so an explicit undefined
    // is not assignable to it under exactOptionalPropertyTypes.
    const query: TeamQuery = {};
    if (league !== undefined) {
      query.league = league;
      // The provider returns nothing for a league without a season, so the current one is
      // the only sane default; European seasons are labelled by the year they start in.
      query.season = season ?? currentSeason();
    }
    // A `season` with no `league` beside it is refused by /teams, and the refusal arrives as
    // an HTTP 200 the client turns into a 502. A squad is not season-scoped anyway, so the
    // parameter is dropped and the search runs unqualified rather than failing the request.
    if (term !== undefined) query.search = term;

    const raw = await deps.provider.teams(query);
    const teams: TeamJson[] = raw.map((entry) => toTeam(entry)).filter((team) => team.id > 0);
    const body: TeamListJson = { teams };
    res.json(body);
  });

  return router;
}

/** FEATURED_LEAGUE_IDS is an ordering as well as a filter: it is the app's tab order. */
function pickFeatured(leagues: LeagueJson[], featuredIds: readonly number[]): LeagueJson[] {
  const byId = new Map(leagues.map((league) => [league.id, league]));
  const picked: LeagueJson[] = [];
  for (const id of featuredIds) {
    const league = byId.get(id);
    if (league) picked.push(league);
  }
  return picked;
}
