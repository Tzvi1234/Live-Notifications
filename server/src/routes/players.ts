/**
 * Squads, player profiles, per-fixture player stats, pre-match predictions, and a team's own
 * fixture list.
 *
 * These exist because the app could otherwise only reach them by talking to API-Football
 * itself, with the key on the device. Behind the backend the same call is paid for once for
 * every phone, which is why each one here names its own cache TTL rather than inheriting
 * CACHE_TTL_SECONDS: that default is tuned for a live scoreboard, and a squad list that
 * changes at transfer windows has no business being re-fetched every minute.
 */

import express, { type Request, type Response, type Router } from 'express';

import type { ApiDeps } from './deps.js';
import { presentMatches } from './fixtures.js';
import {
  currentSeason,
  toMatchPlayers,
  toMatchPrediction,
  toPlayerProfile,
  toSquad,
} from '../provider/mapper.js';
import type {
  MatchListJson,
  MatchPlayersJson,
  MatchPredictionJson,
  PlayerProfileJson,
  TeamSquadJson,
} from '../types.js';
import {
  badRequest,
  notFound,
  parsePositiveInt,
  queryValue,
  requirePositiveInt,
} from './validation.js';

/** A squad changes on transfer deadline day, not during a match. */
const SQUAD_CACHE_TTL_SECONDS = 6 * 3600;

/** A profile's biography never changes; its season totals move once per match played. */
const PLAYER_CACHE_TTL_SECONDS = 6 * 3600;

/**
 * A pre-match prediction is computed once and then frozen — the provider does not revise it
 * as kick-off approaches, and it is meaningless afterwards. The TTL therefore only bounds
 * how stale a *just published* prediction can be, which is what makes half an hour generous
 * rather than risky.
 */
const PREDICTIONS_CACHE_TTL_SECONDS = 1800;

/** Player ratings settle minutes after the whistle, so this is a short cache, not a long one. */
const MATCH_PLAYERS_CACHE_TTL_SECONDS = 120;

/** A team's fixture list moves when a match is rescheduled, which is a matter of days. */
const TEAM_FIXTURES_CACHE_TTL_SECONDS = 600;

/**
 * Ceilings on `last` and `next`. The provider accepts far more, but the app renders a
 * "recent form / what's coming" strip and a request for 200 fixtures is a mistake or an
 * attempt to make one response expensive to serialise.
 */
const MAX_TEAM_FIXTURES = 30;
const DEFAULT_TEAM_FIXTURES_LAST = 5;
const DEFAULT_TEAM_FIXTURES_NEXT = 5;

export function createPlayersRouter(deps: ApiDeps): Router {
  const router = express.Router();

  /**
   * A team's recent results and next fixtures. `last`/`next` are the provider's own
   * parameters and need no season alongside them, which is what makes this one or two
   * requests instead of the season fan-out `/v1/fixtures?from=&to=&teams=` has to do.
   */
  router.get('/teams/:id/fixtures', async (req: Request, res: Response) => {
    const teamId = requirePositiveInt(req.params.id, 'id');
    const last = parseCount(req.query.last, 'last') ?? DEFAULT_TEAM_FIXTURES_LAST;
    const next = parseCount(req.query.next, 'next') ?? DEFAULT_TEAM_FIXTURES_NEXT;
    if (last === 0 && next === 0) {
      throw badRequest('At least one of "last" or "next" must be greater than zero.');
    }

    const [past, upcoming] = await Promise.all([
      last > 0
        ? deps.provider.fixtures(
            { team: teamId, last },
            { cacheTtlSeconds: TEAM_FIXTURES_CACHE_TTL_SECONDS },
          )
        : [],
      next > 0
        ? deps.provider.fixtures(
            { team: teamId, next },
            { cacheTtlSeconds: TEAM_FIXTURES_CACHE_TTL_SECONDS },
          )
        : [],
    ]);

    // De-duplicated because a match in play is answered by both `last` and `next` on some
    // plans, and ordered by kick-off so the client can render the array as it arrives —
    // the same treatment `presentMatches` gives the fixture lists.
    const body: MatchListJson = { matches: presentMatches([...past, ...upcoming], [], []) };
    res.json(body);
  });

  router.get('/teams/:id/squad', async (req: Request, res: Response) => {
    const teamId = requirePositiveInt(req.params.id, 'id');
    const raw = await deps.provider.squad(teamId, SQUAD_CACHE_TTL_SECONDS);
    const squad = toSquad(raw);
    if (squad === undefined) {
      throw notFound(`No squad for team ${teamId}.`);
    }
    const body: TeamSquadJson = squad;
    res.json(body);
  });

  router.get('/players/:id', async (req: Request, res: Response) => {
    const playerId = requirePositiveInt(req.params.id, 'id');
    // `/players` refuses an id without a season, so one is always sent; `?season=` lets the
    // app ask for a past campaign without the route guessing which one it meant.
    const season = parsePositiveInt(req.query.season, 'season') ?? currentSeason();

    const raw = await deps.provider.player(playerId, season, PLAYER_CACHE_TTL_SECONDS);
    const profile = toPlayerProfile(raw);
    if (profile === undefined) {
      throw notFound(`No player with id ${playerId} in season ${season}.`);
    }
    const body: PlayerProfileJson = profile;
    res.json(body);
  });

  router.get('/matches/:id/players', async (req: Request, res: Response) => {
    const matchId = requirePositiveInt(req.params.id, 'id');

    // One request, not two: `/fixtures?id=` carries the per-player stats inline alongside
    // the fixture, and the fixture is needed anyway to know which side is home.
    const fixture = await deps.provider.fixtureById(matchId, MATCH_PLAYERS_CACHE_TTL_SECONDS);
    if (fixture === undefined) {
      throw notFound(`No fixture with id ${matchId}.`);
    }

    const homeTeamId = fixture.teams?.home?.id ?? 0;
    const inline = fixture.players ?? undefined;
    const raw =
      inline ?? (await deps.provider.fixturePlayers(matchId, MATCH_PLAYERS_CACHE_TTL_SECONDS));

    const body: MatchPlayersJson = toMatchPlayers(matchId, homeTeamId, raw);
    res.json(body);
  });

  router.get('/matches/:id/predictions', async (req: Request, res: Response) => {
    const matchId = requirePositiveInt(req.params.id, 'id');
    const raw = await deps.provider.predictions(matchId, PREDICTIONS_CACHE_TTL_SECONDS);
    const prediction = toMatchPrediction(matchId, raw);
    if (prediction === undefined) {
      // Also what a competition outside the plan's `coverage.predictions` returns, which is
      // why /v1/leagues now reports coverage: the client can tell the two apart.
      throw notFound(`No pre-match prediction for fixture ${matchId}.`);
    }
    const body: MatchPredictionJson = prediction;
    res.json(body);
  });

  return router;
}

/** Unlike `parsePositiveInt`, zero is a value here: it means "do not ask for that half". */
function parseCount(raw: unknown, field: string): number | undefined {
  const text = queryValue(raw);
  if (text === undefined) return undefined;
  const value = Number(text);
  if (!Number.isInteger(value) || value < 0 || value > MAX_TEAM_FIXTURES) {
    throw badRequest(
      `Query parameter "${field}" must be a whole number between 0 and ${MAX_TEAM_FIXTURES}; ` +
        `got "${text}".`,
    );
  }
  return value;
}
