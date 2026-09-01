/**
 * The signed-in user's own profile.
 *
 * Clerk owns the identity; this owns the two fields the app shows next to a prediction and a
 * chat line. They are editable here because a Clerk session token does not have to carry a
 * name or a picture — the default template carries neither — so without a place to set them
 * a whole group would be a leaderboard of opaque user ids.
 */

import express, { type Request, type Response, type Router } from 'express';

import { currentUser, type ClerkAuth } from '../auth/clerk.js';
import type { ApiDeps } from './deps.js';
import type { UserJson, UserRecord } from '../types.js';
import {
  badRequest,
  notFound,
  patchableBodyString,
  requireHttpUrl,
  requireObjectBody,
} from './validation.js';

const MAX_DISPLAY_NAME = 64;
const MAX_AVATAR_URL = 512;

export function createMeRouter(deps: ApiDeps, auth: ClerkAuth): Router {
  const router = express.Router();
  const logger = deps.logger.child({ component: 'routes.me' });

  router.get('/me', auth.requireUser, (_req: Request, res: Response) => {
    // `requireUser` has already upserted and touched the row, so this needs no read of
    // its own; the record it produced is the freshest one there is.
    res.json(toUserJson(currentUser(res)));
  });

  router.patch('/me', auth.requireUser, async (req: Request, res: Response) => {
    const user = currentUser(res);
    const body = requireObjectBody(req.body);

    const displayName = patchableBodyString(body.displayName, 'displayName', MAX_DISPLAY_NAME);
    const avatarUrl = patchableBodyString(body.avatarUrl, 'avatarUrl', MAX_AVATAR_URL);
    if (displayName === undefined && avatarUrl === undefined) {
      throw badRequest('Send at least one of "displayName" or "avatarUrl"; null clears a field.');
    }
    // The value is rendered by every other member's client, so it has to be a URL a phone
    // can actually load rather than a `javascript:` or `data:` string.
    if (typeof avatarUrl === 'string') requireHttpUrl(avatarUrl, 'avatarUrl');

    // Built key by key: an absent key must stay absent, since `UserProfilePatch` reads
    // `undefined` as "leave it" and `null` as "clear it".
    const patch: Parameters<typeof deps.store.updateUserProfile>[1] = {};
    if (displayName !== undefined) patch.displayName = displayName;
    if (avatarUrl !== undefined) patch.avatarUrl = avatarUrl;

    const updated = await deps.store.updateUserProfile(user.clerkUserId, patch);
    if (updated === undefined) {
      // Only reachable if the row was deleted between requireUser's upsert and this write.
      throw notFound('That account no longer exists.');
    }

    logger.info('profile updated', {
      // The user id, never the name or the avatar: both are user-supplied text.
      userId: updated.clerkUserId,
      changed: Object.keys(patch),
    });
    res.json(toUserJson(updated));
  });

  return router;
}

/** Epoch millis internally, epoch SECONDS on the wire — see the note on `UserJson`. */
export function toUserJson(user: UserRecord): UserJson {
  return {
    userId: user.clerkUserId,
    displayName: user.displayName,
    avatarUrl: user.avatarUrl,
    createdAt: Math.floor(user.createdAt / 1000),
    lastSeenAt: Math.floor(user.lastSeenAt / 1000),
  };
}
