/**
 * What a device wants to be woken for. One PUT replaces the whole subscription, because
 * the app holds the authoritative copy: a partial update would leave the two sides guessing
 * about which of them last touched a list.
 */

import express, { type Request, type Response, type Router } from 'express';

import type { ApiDeps } from './deps.js';
import { normalizePreferences } from '../store/index.js';
import type { SubscriptionRecord } from '../types.js';
import {
  badRequest,
  notFound,
  requireDeviceToken,
  requireIdArray,
  requireObjectBody,
} from './validation.js';

export function createSubscriptionsRouter(deps: ApiDeps): Router {
  const router = express.Router();
  const logger = deps.logger.child({ component: 'routes.subscriptions' });

  router.put('/subscriptions', async (req: Request, res: Response) => {
    const body = requireObjectBody(req.body);
    const token = requireDeviceToken(body.token);

    if (
      body.preferences !== undefined &&
      body.preferences !== null &&
      (typeof body.preferences !== 'object' || Array.isArray(body.preferences))
    ) {
      throw badRequest('"preferences" must be a JSON object.');
    }

    const subscription: SubscriptionRecord = {
      token,
      teamIds: requireIdArray(body.teamIds, 'teamIds'),
      leagueIds: requireIdArray(body.leagueIds, 'leagueIds'),
      // Fixture ids are Long on the client; every provider id so far fits a double exactly,
      // and `requireIdArray` rejects anything that would not.
      matchIds: requireIdArray(body.matchIds, 'matchIds'),
      // Missing or junk keys fall back to the shipped defaults rather than rejecting the row,
      // so an older app build never loses its whole subscription over one added preference.
      preferences: normalizePreferences(body.preferences),
    };

    // The store registers a placeholder device if the subscription arrives before the
    // registration call; `touchDevice` then keeps it clear of the retention sweep.
    await deps.store.putSubscription(subscription);
    await deps.store.touchDevice(token);

    logger.info('subscription updated', {
      teams: subscription.teamIds.length,
      leagues: subscription.leagueIds.length,
      matches: subscription.matchIds.length,
      preferences: subscription.preferences,
    });

    res.status(204).end();
  });

  /**
   * Read-back for support and for the deployment guide's end-to-end check. Addressed by the
   * registration token, exactly like DELETE /v1/devices/:token: holding the token is what
   * proves you are the device, and the record is echoed without it.
   */
  router.get('/subscriptions/:token', async (req: Request, res: Response) => {
    const token = requireDeviceToken(req.params.token, 'token');
    const record = await deps.store.getSubscription(token);
    if (record === undefined) {
      throw notFound('No subscription for that token.');
    }

    res.json({
      teamIds: record.teamIds,
      leagueIds: record.leagueIds,
      matchIds: record.matchIds,
      preferences: record.preferences,
    });
  });

  return router;
}
