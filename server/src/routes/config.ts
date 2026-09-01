/**
 * What the app needs before anyone has signed in.
 *
 * PUBLIC, and deliberately so: the Clerk *publishable* key is a client credential — it names
 * the instance and is embedded in every sign-in widget — so serving it here means the APK
 * does not have to carry it, and the Clerk instance can be swapped without shipping a
 * release. The secret key is not here and must never be added: it is the one that can mint
 * sessions.
 */

import express, { type Request, type Response, type Router } from 'express';

import type { ClerkAuth } from '../auth/clerk.js';
import type { ApiDeps } from './deps.js';
import { isPushEnabled } from '../push/fcm.js';
import type { ClientConfigJson } from '../types.js';

export function createConfigRouter(deps: ApiDeps, auth: ClerkAuth): Router {
  const router = express.Router();

  router.get('/config', (_req: Request, res: Response) => {
    const body: ClientConfigJson = {
      clerkPublishableKey: deps.config.clerkPublishableKey,
      features: {
        auth: auth.enabled,
        // The game needs an account AND a store that outlives a restart: on the in-memory
        // store a deploy would silently wipe every group, and telling the app the feature
        // is on would be a lie it only discovers after a user has invited their friends.
        predictionGame: auth.enabled && deps.store.kind === 'postgres',
        push: isPushEnabled(),
        polling: deps.config.pollEnabled,
      },
    };
    res.json(body);
  });

  return router;
}
