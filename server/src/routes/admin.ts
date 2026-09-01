/**
 * Operator endpoints: what the quota looks like, what the poller thinks it is doing, and a
 * way to force a tick without waiting for the schedule.
 *
 * Guarded by ADMIN_TOKEN as a bearer token. With no ADMIN_TOKEN configured the routes do
 * not exist at all — an unguarded manual trigger is a way for anyone to burn the day's
 * provider budget in a loop.
 */

import { createHash, timingSafeEqual } from 'node:crypto';

import express, { type NextFunction, type Request, type Response, type Router } from 'express';

import type { ApiDeps } from './deps.js';
import type { ProviderHealth, ProviderQuota } from '../provider/apiFootball.js';
import { isPushEnabled } from '../push/fcm.js';
import { notFound, serviceUnavailable, unauthorized } from './validation.js';

const BEARER = /^Bearer\s+(.+)$/i;

export function createAdminRouter(deps: ApiDeps): Router {
  const router = express.Router();
  const logger = deps.logger.child({ component: 'routes.admin' });

  router.use('/admin', (req: Request, _res: Response, next: NextFunction) => {
    const expected = deps.config.adminToken;
    if (expected === undefined) {
      // 404, not 403: an unconfigured admin surface should look like it was never deployed,
      // which means the same body the app's catch-all writes — path only, no query string.
      const path = req.originalUrl.split('?', 1)[0] ?? req.path;
      next(notFound(`No route for ${req.method} ${path}.`));
      return;
    }

    const presented = BEARER.exec(req.get('authorization') ?? '')?.[1]?.trim();
    if (presented === undefined || !tokenMatches(presented, expected)) {
      logger.warn('admin request rejected', { path: req.path, ip: req.ip });
      next(unauthorized('A valid "Authorization: Bearer <ADMIN_TOKEN>" header is required.'));
      return;
    }

    next();
  });

  router.get('/admin/status', (_req: Request, res: Response) => {
    const budget = deps.provider.getBudgetState();
    res.json({
      ok: true,
      version: deps.config.version,
      nodeEnv: deps.config.nodeEnv,
      uptimeSeconds: Math.round(process.uptime()),
      store: deps.store.kind,
      pushEnabled: isPushEnabled(),
      auth: {
        // Named after the env vars, so an operator reading this knows which one to set.
        clerk: deps.config.hasClerk ? 'configured' : 'unset (CLERK_SECRET_KEY)',
        networklessVerification: deps.config.clerkJwtKey !== undefined,
      },
      provider: {
        name: deps.config.providerName,
        // Two different ceilings: `quota` is what the provider last reported in its rate-limit
        // headers — daily AND per-minute, plus when the minute window is expected to roll
        // over — and `budget` is this process's own local counter, deliberately set lower.
        quota: describeQuota(deps.provider.getQuota()),
        budget: { ...budget, resetsAt: budget.resetsAt.toISOString() },
        // The provider's own words about the last failure. They name the account's state -
        // "Token is invalid", "Your account is not subscribed" - which is why they live
        // behind the admin token and only the fault's KIND reaches /v1/health.
        health: describeHealth(deps.provider.getHealth()),
      },
      poller: deps.poller?.getStatus() ?? { enabled: false, reason: 'POLL_ENABLED is false' },
    });
  });

  router.post('/admin/poll', async (_req: Request, res: Response) => {
    if (deps.poller === undefined) {
      throw serviceUnavailable('Polling is disabled on this instance (POLL_ENABLED=false).');
    }

    logger.info('manual poll triggered');
    const result = await deps.poller.pollOnce();
    res.json({ ok: true, result: result ?? null });
  });

  return router;
}

/** Epoch millis are unreadable in an operator's terminal; everything else passes through. */
function describeHealth(health: ProviderHealth): Record<string, unknown> {
  return {
    reachable: health.reachable,
    lastSuccessAt: health.lastSuccessAt?.toISOString(),
    lastFault:
      health.lastFault === undefined
        ? undefined
        : {
            kind: health.lastFault.kind,
            status: health.lastFault.status,
            at: health.lastFault.at.toISOString(),
            detail: health.lastFault.detail,
          },
  };
}

function describeQuota(quota: ProviderQuota): Record<string, unknown> {
  const { minuteWindowResetsAt, ...rest } = quota;
  return {
    ...rest,
    minuteWindowResetsAt:
      minuteWindowResetsAt === undefined ? null : new Date(minuteWindowResetsAt).toISOString(),
  };
}

/**
 * Hashed before comparing so the constant-time check never has to reject on length first,
 * which would leak the token's size to anyone willing to time the 401s.
 */
function tokenMatches(presented: string, expected: string): boolean {
  const a = createHash('sha256').update(presented).digest();
  const b = createHash('sha256').update(expected).digest();
  return timingSafeEqual(a, b);
}
