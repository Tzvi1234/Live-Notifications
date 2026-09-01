/**
 * Liveness endpoint. Render polls it continuously, so it touches nothing that can block:
 * no database round-trip, no provider call, no quota spent.
 */

import express, { type Request, type Response, type Router } from 'express';

import type { ApiDeps } from './deps.js';
import type { ProviderHealth, ProviderQuota } from '../provider/apiFootball.js';
import { publicFaultReason } from '../provider/apiFootball.js';
import type { HealthJson, ProviderFaultJson, ProviderQuotaJson } from '../types.js';

export function createHealthRouter(deps: ApiDeps): Router {
  const router = express.Router();

  router.get('/health', (_req: Request, res: Response) => {
    const health = deps.provider.getHealth();
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
      // Liveness and usefulness are different questions. `ok` answers the first, for
      // Render's own health check; these two answer the second, so a deployment whose
      // provider key has been revoked stops looking well while every screen in the app
      // fails. Read from memory - still no database round-trip and no quota spent.
      dataOk: health.reachable,
      providerFault: toFaultJson(health),
    };
    res.json(body);
  });

  return router;
}

function toFaultJson(health: ProviderHealth): ProviderFaultJson | undefined {
  const fault = health.lastFault;
  if (fault === undefined) return undefined;
  return {
    kind: fault.kind,
    reason: publicFaultReason(fault.kind),
    status: fault.status,
    at: fault.at.toISOString(),
  };
}

function toQuotaJson(quota: ProviderQuota): ProviderQuotaJson {
  return {
    dailyLimit: quota.dailyLimit,
    dailyRemaining: quota.dailyRemaining,
    minuteLimit: quota.minuteLimit,
    minuteRemaining: quota.minuteRemaining,
  };
}
