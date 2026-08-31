/**
 * Liveness endpoint. Render polls it continuously, so it touches nothing that can block:
 * no database round-trip, no provider call, no quota spent.
 */

import express, { type Request, type Response, type Router } from 'express';

import type { ApiDeps } from './deps.js';
import type { HealthJson } from '../types.js';

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
    };
    res.json(body);
  });

  return router;
}
