/**
 * Liveness endpoint. Render polls it continuously, so it touches nothing that can block:
 * no database round-trip, no provider call, no quota spent.
 */

import express, { type Request, type Response, type Router } from 'express';

import type { ApiDeps } from './deps.js';
import type { ProviderQuota } from '../provider/apiFootball.js';
import type { HealthJson, ProviderQuotaJson } from '../types.js';

export function createHealthRouter(deps: ApiDeps): Router {
  const router = express.Router();

  router.get('/health', (_req: Request, res: Response) => {
    const body: HealthJson = {
      ok: true,
      version: deps.config.version,
      provider: deps.config.providerName,
      // Configured intent, not "is a tick running": the app uses it to decide whether it has
      // to fall back to its own provider polling, and a mid-tick sample would flap.
      pollingEnabled: deps.config.pollEnabled,
      // The rate-limit headers of the last provider response, kept in memory by the client.
      // Reading them costs nothing — this endpoint still touches no database and spends no
      // quota — and it lets the app back off its own direct calls before the key is cut off.
      // The four counters only: the inferred minute-window boundary is an operator's
      // diagnostic and lives on /v1/admin/status, not in the client contract.
      quota: toQuotaJson(deps.provider.getQuota()),
      // Read per request, not captured at boot: the store fails over while running.
      store: deps.store.kind,
    };
    res.json(body);
  });

  return router;
}

function toQuotaJson(quota: ProviderQuota): ProviderQuotaJson {
  return {
    dailyLimit: quota.dailyLimit,
    dailyRemaining: quota.dailyRemaining,
    minuteLimit: quota.minuteLimit,
    minuteRemaining: quota.minuteRemaining,
  };
}
