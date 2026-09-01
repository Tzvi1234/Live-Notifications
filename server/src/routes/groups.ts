/**
 * The prediction game: groups, their fixture list, predictions, the leaderboard and the chat.
 *
 * Two rules make this more than CRUD, and neither is enforced here:
 *
 *   * a prediction cannot be written once its fixture has kicked off;
 *   * a prediction cannot be READ by anyone but its author until then.
 *
 * Both live in the store's SQL (see `putPrediction` and `listPredictions`), because a rule
 * that only a handler enforces is one route away from being bypassed — and the second one
 * in particular is the whole game: a member who can see what everyone else predicted before
 * kick-off has no reason to play. What is enforced here is membership, which every route
 * below runs before it touches anything.
 */

import express, { type NextFunction, type Request, type Response, type Router } from 'express';

import { currentUser, type ClerkAuth } from '../auth/clerk.js';
import type { ApiDeps } from './deps.js';
import {
  denseRanks,
  rulebook,
  CORRECT_OUTCOME_POINTS,
  EXACT_SCORE_POINTS,
} from '../game/scoring.js';
import { newInviteCode, normalizeInviteCode, INVITE_CODE_LENGTH } from '../game/invite.js';
import { currentSeason, toMatch } from '../provider/mapper.js';
import type { ApiFixture } from '../provider/apiFootball.js';
import type {
  ChatJson,
  ChatMessageJson,
  GroupDetailJson,
  GroupFixtureJson,
  GroupFixtureListJson,
  GroupJson,
  GroupListJson,
  GroupMemberRecord,
  GroupMessageRecord,
  GroupRecord,
  LeaderboardEntryJson,
  LeaderboardJson,
  MatchJson,
  PredictionJson,
  PredictionRecord,
} from '../types.js';
import { presentMatches } from './fixtures.js';
import {
  addDays,
  badRequest,
  conflict,
  forbidden,
  notFound,
  requireBodyString,
  requireGoalCount,
  requireIdArray,
  requireObjectBody,
  requirePositiveInt,
  todayIso,
  tooManyRequests,
} from './validation.js';

const MAX_GROUP_NAME = 60;

/**
 * Ceilings on a group's selections. Teams are capped low because the fixture list costs one
 * provider request per team per season, so an unbounded list would let one group's refresh
 * spend a day's quota; leagues cost nothing to store but are a picker, not a database.
 */
const MAX_GROUP_TEAMS = 20;
const MAX_GROUP_LEAGUES = 15;

/** The provider's own goal ceiling is nowhere near this; the column is a SMALLINT. */
const MAX_PREDICTED_GOALS = 99;

/** How far either side of today the group's fixture list reaches. */
const RECENT_WINDOW_DAYS = 7;
const UPCOMING_WINDOW_DAYS = 14;

/**
 * Hard ceiling on the provider requests one fixture-list call may cost, counted in requests
 * rather than teams: a window straddling the 1 July season rollover needs one call per team
 * per season label. Teams past the ceiling are dropped from the end of the list rather than
 * half-queried, for the same reason `fanOut` in fixtures.ts does it.
 */
const MAX_FIXTURE_REQUESTS = 24;

/**
 * A group's fixture window moves when a match is rescheduled, which is a matter of days —
 * far slower than the live scoreboard CACHE_TTL_SECONDS is tuned for. Ten minutes means a
 * group of ten refreshing together costs one round of requests, not ten.
 */
const GROUP_FIXTURES_CACHE_TTL_SECONDS = 600;

/**
 * The fixture behind a prediction is fetched to read its kick-off. Cached briefly so a
 * member editing their score five times does not cost five requests; the cache cannot move
 * the lock, because the boundary is `kickoffAt` against the *current* clock and the store
 * re-checks it against the database's own `now()` anyway.
 */
const PREDICTION_FIXTURE_CACHE_TTL_SECONDS = 60;

const CHAT_WINDOW_MS = 86_400_000;
const MAX_CHAT_MESSAGE_LENGTH = 500;

/**
 * Per user, per group. A group chat during a match is bursty — a goal produces a flurry —
 * so the limit is generous per minute rather than a one-message-per-N-seconds throttle that
 * would feel broken at exactly the moment people want to use it.
 */
const CHAT_RATE_LIMIT_MESSAGES = 15;
const CHAT_RATE_LIMIT_WINDOW_MS = 60_000;

/** Invite codes are ~39 bits; a few attempts is already far more than a collision needs. */
const INVITE_CODE_ATTEMPTS = 5;

export function createGroupsRouter(deps: ApiDeps, auth: ClerkAuth): Router {
  const router = express.Router();
  const logger = deps.logger.child({ component: 'routes.groups' });

  /**
   * Membership, resolved once per request. A non-member is answered 404 rather than 403:
   * a 403 would confirm that a group with that id exists, which is enough to enumerate
   * them, and there is nothing a non-member is allowed to learn about a group anyway.
   */
  const requireMember = async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    const groupId = requirePositiveInt(req.params.id, 'id');
    const user = currentUser(res);
    const [group, isMember] = await Promise.all([
      deps.store.getGroup(groupId),
      deps.store.isGroupMember(groupId, user.clerkUserId),
    ]);
    if (group === undefined || !isMember) {
      next(notFound(`No group ${groupId} that you are a member of.`));
      return;
    }
    res.locals.group = group;
    next();
  };

  const requireOwner = (req: Request, res: Response, next: NextFunction): void => {
    const group = currentGroup(res);
    if (group.ownerId !== currentUser(res).clerkUserId) {
      next(forbidden('Only the group owner can change or delete the group.'));
      return;
    }
    next();
  };

  router.post('/groups', auth.requireUser, async (req: Request, res: Response) => {
    const user = currentUser(res);
    const body = requireObjectBody(req.body);
    const name = requireBodyString(body.name, 'name', MAX_GROUP_NAME);
    const leagueIds = selection(body.leagueIds, 'leagueIds', MAX_GROUP_LEAGUES);
    const teamIds = selection(body.teamIds, 'teamIds', MAX_GROUP_TEAMS);
    if (teamIds.length === 0) {
      throw badRequest('"teamIds" must name at least one team; the group has no fixtures without one.');
    }

    const group = await createGroupWithCode(deps, { name, ownerId: user.clerkUserId, leagueIds, teamIds });
    logger.info('group created', {
      groupId: group.id,
      ownerId: group.ownerId,
      leagues: leagueIds.length,
      teams: teamIds.length,
    });
    res.status(201).json(toGroupJson(group, user.clerkUserId));
  });

  router.get('/groups', auth.requireUser, async (_req: Request, res: Response) => {
    const user = currentUser(res);
    const groups = await deps.store.listGroupsForUser(user.clerkUserId);
    const body: GroupListJson = {
      groups: groups.map((group) => toGroupJson(group, user.clerkUserId)),
    };
    res.json(body);
  });

  /**
   * Joining is by code alone, so this is not under `/groups/:id`: a friend who has the code
   * does not know the id, and giving them a way to probe ids would defeat the 404 above.
   */
  router.post('/groups/join', auth.requireUser, async (req: Request, res: Response) => {
    const user = currentUser(res);
    const body = requireObjectBody(req.body);
    const code = normalizeInviteCode(
      requireBodyString(body.code, 'code', INVITE_CODE_LENGTH * 4),
    );

    const group = await deps.store.getGroupByInviteCode(code);
    if (group === undefined) {
      throw notFound('That invite code does not match any group.');
    }

    // False means "already a member", which is a retry rather than an error: the response
    // is the group either way, so a dropped response can simply be re-sent.
    const joined = await deps.store.addGroupMember(group.id, user.clerkUserId);
    if (joined) logger.info('group joined', { groupId: group.id, userId: user.clerkUserId });

    const refreshed = (await deps.store.getGroup(group.id)) ?? group;
    res.json(toGroupJson(refreshed, user.clerkUserId));
  });

  router.get('/groups/:id', auth.requireUser, requireMember, async (_req: Request, res: Response) => {
    const group = currentGroup(res);
    const members = await deps.store.listGroupMembers(group.id);
    const body: GroupDetailJson = {
      ...toGroupJson(group, currentUser(res).clerkUserId),
      members: members.map(toGroupMemberJson),
    };
    res.json(body);
  });

  router.patch(
    '/groups/:id',
    auth.requireUser,
    requireMember,
    requireOwner,
    async (req: Request, res: Response) => {
      const group = currentGroup(res);
      const body = requireObjectBody(req.body);

      // Built key by key so an absent field is left alone; a present list REPLACES the old
      // one wholesale, exactly as PUT /v1/subscriptions does, because the app holds the
      // authoritative copy and a merge would leave both sides guessing.
      const patch: Parameters<typeof deps.store.updateGroup>[1] = {};
      if (body.name !== undefined) patch.name = requireBodyString(body.name, 'name', MAX_GROUP_NAME);
      if (body.leagueIds !== undefined) {
        patch.leagueIds = selection(body.leagueIds, 'leagueIds', MAX_GROUP_LEAGUES);
      }
      if (body.teamIds !== undefined) {
        const teamIds = selection(body.teamIds, 'teamIds', MAX_GROUP_TEAMS);
        if (teamIds.length === 0) {
          throw badRequest('"teamIds" must name at least one team.');
        }
        patch.teamIds = teamIds;
      }
      if (Object.keys(patch).length === 0) {
        throw badRequest('Send at least one of "name", "leagueIds" or "teamIds".');
      }

      const updated = await deps.store.updateGroup(group.id, patch);
      if (updated === undefined) {
        throw notFound(`No group ${group.id}.`);
      }
      logger.info('group updated', { groupId: group.id, changed: Object.keys(patch) });
      res.json(toGroupJson(updated, currentUser(res).clerkUserId));
    },
  );

  router.delete(
    '/groups/:id',
    auth.requireUser,
    requireMember,
    requireOwner,
    async (_req: Request, res: Response) => {
      const group = currentGroup(res);
      // Members, selections, predictions and messages all go with it (ON DELETE CASCADE).
      await deps.store.deleteGroup(group.id);
      logger.info('group deleted', { groupId: group.id });
      res.status(204).end();
    },
  );

  router.delete(
    '/groups/:id/members/me',
    auth.requireUser,
    requireMember,
    async (_req: Request, res: Response) => {
      const group = currentGroup(res);
      const user = currentUser(res);
      if (group.ownerId === user.clerkUserId) {
        // Nothing here can pick a new owner, and a group whose owner has left can never be
        // edited or deleted again. Deleting it is the owner's own decision to make.
        throw conflict('The owner cannot leave the group; delete it instead.');
      }
      // The predictions stay: they are what the leaderboard was, and removing them would
      // silently rewrite everyone else's history of the season.
      await deps.store.removeGroupMember(group.id, user.clerkUserId);
      logger.info('group left', { groupId: group.id, userId: user.clerkUserId });
      res.status(204).end();
    },
  );

  /**
   * Every fixture involving any chosen team in a window around today — a match where only
   * one of the two sides is chosen counts, which is why the query is per team rather than
   * per league.
   *
   * Each fixture carries the caller's own prediction always, and everybody's once it has
   * kicked off. The store decides which of those two it is; this route only renders it.
   */
  router.get(
    '/groups/:id/fixtures',
    auth.requireUser,
    requireMember,
    async (_req: Request, res: Response) => {
      const group = currentGroup(res);
      const user = currentUser(res);
      const now = new Date();

      const matches = await fetchGroupFixtures(deps, group, now);
      const predictions = await deps.store.listPredictions({
        groupId: group.id,
        fixtureIds: matches.map((match) => match.id),
        viewerId: user.clerkUserId,
        now,
      });

      const byFixture = new Map<number, PredictionRecord[]>();
      for (const prediction of predictions) {
        const bucket = byFixture.get(prediction.fixtureId);
        if (bucket) bucket.push(prediction);
        else byFixture.set(prediction.fixtureId, [prediction]);
      }

      const nowSeconds = Math.floor(now.getTime() / 1000);
      const fixtures: GroupFixtureJson[] = matches.map((match) => {
        const rows = byFixture.get(match.id) ?? [];
        const locked = match.kickoffAt <= nowSeconds;
        return {
          match,
          locked,
          myPrediction: rows
            .filter((row) => row.userId === user.clerkUserId)
            .map(toPredictionJson)[0],
          // Before kick-off the store returned nothing but the caller's own row, so this is
          // already empty; filtering it out here as well keeps that visible at the edge.
          predictions: locked ? rows.map(toPredictionJson) : [],
        };
      });

      const body: GroupFixtureListJson = { fixtures };
      res.json(body);
    },
  );

  router.put(
    '/groups/:id/predictions/:fixtureId',
    auth.requireUser,
    requireMember,
    async (req: Request, res: Response) => {
      const group = currentGroup(res);
      const user = currentUser(res);
      const fixtureId = requirePositiveInt(req.params.fixtureId, 'fixtureId');
      const body = requireObjectBody(req.body);
      const home = requireGoalCount(body.home, 'home', MAX_PREDICTED_GOALS);
      const away = requireGoalCount(body.away, 'away', MAX_PREDICTED_GOALS);

      const fixture = await deps.provider.fixtureById(
        fixtureId,
        PREDICTION_FIXTURE_CACHE_TTL_SECONDS,
      );
      if (fixture === undefined) {
        throw notFound(`No fixture with id ${fixtureId}.`);
      }
      const match = toMatch(fixture);

      // The group's fixture list is defined by its teams, so a fixture involving neither is
      // not part of this group and has no prediction slot to write to.
      const teams = new Set(group.teamIds);
      if (!teams.has(match.home.id) && !teams.has(match.away.id)) {
        throw notFound(`Fixture ${fixtureId} does not involve any of this group's teams.`);
      }

      const kickoffAt = new Date(match.kickoffAt * 1000);
      const now = new Date();
      const record = await deps.store.putPrediction({
        groupId: group.id,
        fixtureId,
        userId: user.clerkUserId,
        home,
        away,
        kickoffAt,
        now,
      });
      if (record === undefined) {
        // The store refused it, which for this call means one thing: the fixture is under
        // way. Reported as 409 rather than 403 because it is the state of the world that
        // changed, not the caller's rights — the same request was legal a minute ago.
        throw conflict(
          `Predictions for fixture ${fixtureId} closed at kick-off ` +
            `(${kickoffAt.toISOString()}).`,
        );
      }

      res.json(toPredictionJson({ ...record, displayName: user.displayName, avatarUrl: user.avatarUrl }));
    },
  );

  router.get(
    '/groups/:id/leaderboard',
    auth.requireUser,
    requireMember,
    async (_req: Request, res: Response) => {
      const group = currentGroup(res);
      const rows = await deps.store.leaderboard(group.id);
      const ranks = denseRanks(rows);

      const entries: LeaderboardEntryJson[] = rows.map((row, index) => ({
        userId: row.userId,
        displayName: row.displayName,
        avatarUrl: row.avatarUrl,
        points: row.points,
        exactCount: row.exactCount,
        correctOutcomeCount: row.correctOutcomeCount,
        settledCount: row.settledCount,
        rank: ranks[index] ?? index + 1,
      }));

      // The whole rulebook rides along rather than two numbers, so the app can show the
      // rules sheet - including the stage multipliers - without a second copy of them
      // compiled into the APK, and a house rule changed on the server needs no release.
      const body: LeaderboardJson = {
        groupId: group.id,
        exactPoints: EXACT_SCORE_POINTS,
        outcomePoints: CORRECT_OUTCOME_POINTS,
        // The captain is whoever created the group; the app marks them on the table.
        captainUserId: group.ownerId,
        rules: rulebook(),
        entries,
      };
      res.json(body);
    },
  );

  router.get(
    '/groups/:id/chat',
    auth.requireUser,
    requireMember,
    async (_req: Request, res: Response) => {
      const group = currentGroup(res);
      // 24 hours and no pagination on purpose: this is match-day talk, not a message
      // archive, and an unbounded history would be an unbounded response.
      const since = new Date(Date.now() - CHAT_WINDOW_MS);
      const messages = await deps.store.listGroupMessages(group.id, since);
      const body: ChatJson = {
        messages: messages.map(toChatMessageJson),
        since: Math.floor(since.getTime() / 1000),
      };
      res.json(body);
    },
  );

  router.post(
    '/groups/:id/chat',
    auth.requireUser,
    requireMember,
    async (req: Request, res: Response) => {
      const group = currentGroup(res);
      const user = currentUser(res);
      const body = requireObjectBody(req.body);
      const text = requireBodyString(body.text, 'text', MAX_CHAT_MESSAGE_LENGTH);

      const windowStart = new Date(Date.now() - CHAT_RATE_LIMIT_WINDOW_MS);
      const recent = await deps.store.countRecentGroupMessages(
        group.id,
        user.clerkUserId,
        windowStart,
      );
      if (recent >= CHAT_RATE_LIMIT_MESSAGES) {
        throw tooManyRequests(
          `At most ${CHAT_RATE_LIMIT_MESSAGES} messages per minute; wait a moment.`,
        );
      }

      const message = await deps.store.postGroupMessage({
        groupId: group.id,
        userId: user.clerkUserId,
        text,
      });
      // The text is never logged: it is what one member wrote to another.
      logger.debug('chat message posted', { groupId: group.id, userId: user.clerkUserId });
      res.status(201).json(toChatMessageJson(message));
    },
  );

  return router;
}

/* -------------------------------------------------------------------------- */
/* Group fixtures                                                              */
/* -------------------------------------------------------------------------- */

/**
 * One `/fixtures?team=&season=&from=&to=` per chosen team per season the window touches.
 *
 * Per team rather than per league because a chosen team's cup ties and European nights are
 * part of the group's list too, and a league query would miss every one of them. The window
 * straddles two season labels for a fortnight each summer (a label turns over on 1 July),
 * and asking for only one of them would lose every fixture of the season about to start.
 */
async function fetchGroupFixtures(
  deps: ApiDeps,
  group: GroupRecord,
  now: Date,
): Promise<MatchJson[]> {
  if (group.teamIds.length === 0) return [];

  const today = todayIso(now);
  const from = addDays(today, -RECENT_WINDOW_DAYS);
  const to = addDays(today, UPCOMING_WINDOW_DAYS);
  const seasons = seasonsInRange(from, to);

  const queries: Array<{ team: number; season: number }> = [];
  for (const team of group.teamIds) {
    if (queries.length + seasons.length > MAX_FIXTURE_REQUESTS) break;
    for (const season of seasons) queries.push({ team, season });
  }

  const batches = await Promise.all(
    queries.map((query) =>
      deps.provider.fixtures(
        { team: query.team, season: query.season, from, to },
        { cacheTtlSeconds: GROUP_FIXTURES_CACHE_TTL_SECONDS },
      ),
    ),
  );

  // A match between two chosen teams comes back from both of their queries; `presentMatches`
  // de-duplicates by fixture id and orders by kick-off.
  const raw: ApiFixture[] = batches.flat();
  return presentMatches(raw, [], []);
}

function seasonsInRange(from: string, to: string): number[] {
  const first = currentSeason(new Date(`${from}T00:00:00Z`));
  const last = currentSeason(new Date(`${to}T00:00:00Z`));
  const seasons: number[] = [];
  for (let season = first; season <= last; season += 1) seasons.push(season);
  return seasons;
}

/* -------------------------------------------------------------------------- */
/* Helpers                                                                     */
/* -------------------------------------------------------------------------- */

/** The group `requireMember` resolved. Throws for the same reason `currentUser` does. */
function currentGroup(res: Response): GroupRecord {
  const group = res.locals.group as GroupRecord | undefined;
  if (group === undefined) {
    throw new Error('[routes.groups] currentGroup() called on a route without requireMember.');
  }
  return group;
}

/**
 * Rejected rather than silently truncated, unlike the store's `normalizeIds`: this is a
 * list the user assembled by hand in a picker, and quietly dropping half of it would show
 * up much later as "my team's matches are missing".
 */
function selection(raw: unknown, field: string, max: number): number[] {
  const ids = requireIdArray(raw, field);
  const unique = [...new Set(ids.filter((id) => id > 0))];
  if (unique.length > max) {
    throw badRequest(`"${field}" must name at most ${max} ids; got ${unique.length}.`);
  }
  return unique;
}

/**
 * A code collides only by accident, and the UNIQUE index is what detects it — checking for
 * a free code first and then inserting would leave a window between the two in which a
 * second group takes it. So the insert is simply retried on a duplicate-key failure.
 */
async function createGroupWithCode(
  deps: ApiDeps,
  input: { name: string; ownerId: string; leagueIds: number[]; teamIds: number[] },
): Promise<GroupRecord> {
  let lastError: unknown;
  for (let attempt = 0; attempt < INVITE_CODE_ATTEMPTS; attempt += 1) {
    try {
      return await deps.store.createGroup({ ...input, inviteCode: newInviteCode() });
    } catch (error) {
      if (!isUniqueViolation(error)) throw error;
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error('[routes.groups] invite code exhausted');
}

/** Postgres SQLSTATE 23505, the only failure a fresh invite code can plausibly produce. */
function isUniqueViolation(error: unknown): boolean {
  return (
    typeof error === 'object' && error !== null && (error as { code?: unknown }).code === '23505'
  );
}

function toGroupJson(group: GroupRecord, viewerId: string): GroupJson {
  return {
    id: group.id,
    name: group.name,
    ownerId: group.ownerId,
    inviteCode: group.inviteCode,
    leagueIds: group.leagueIds,
    teamIds: group.teamIds,
    memberCount: group.memberCount,
    isOwner: group.ownerId === viewerId,
    createdAt: Math.floor(group.createdAt / 1000),
  };
}

function toGroupMemberJson(member: GroupMemberRecord): GroupDetailJson['members'][number] {
  return {
    userId: member.userId,
    displayName: member.displayName,
    avatarUrl: member.avatarUrl,
    joinedAt: Math.floor(member.joinedAt / 1000),
    isOwner: member.isOwner,
  };
}

function toPredictionJson(record: PredictionRecord): PredictionJson {
  return {
    userId: record.userId,
    displayName: record.displayName,
    avatarUrl: record.avatarUrl,
    home: record.home,
    away: record.away,
    points: record.points,
    exact: record.exact,
    correctOutcome: record.correctOutcome,
    updatedAt: Math.floor(record.updatedAt / 1000),
  };
}

function toChatMessageJson(message: GroupMessageRecord): ChatMessageJson {
  return {
    id: message.id,
    userId: message.userId,
    displayName: message.displayName,
    avatarUrl: message.avatarUrl,
    text: message.text,
    createdAt: Math.floor(message.createdAt / 1000),
  };
}
