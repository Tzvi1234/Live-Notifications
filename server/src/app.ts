/**
 * The Express application: middleware, the /v1 surface, and the single place that turns a
 * thrown error into a response.
 *
 * Express 5 forwards a rejected promise from a handler to the error middleware on its own,
 * so routes are plain `async` functions that throw — there is no asyncHandler wrapper here
 * and none is needed.
 */

import { randomUUID } from 'node:crypto';

import express, {
  type ErrorRequestHandler,
  type Express,
  type NextFunction,
  type Request,
  type RequestHandler,
  type Response,
} from 'express';

import { createClerkAuth } from './auth/clerk.js';
import type { ApiDeps } from './routes/deps.js';
import { createAdminRouter } from './routes/admin.js';
import { createCatalogueRouter } from './routes/catalogue.js';
import { createConfigRouter } from './routes/config.js';
import { createDevicesRouter } from './routes/devices.js';
import { createFixturesRouter } from './routes/fixtures.js';
import { createGroupsRouter } from './routes/groups.js';
import { createHealthRouter } from './routes/health.js';
import { createMeRouter } from './routes/me.js';
import { createPlayersRouter } from './routes/players.js';
import { createSubscriptionsRouter } from './routes/subscriptions.js';
import { HttpError } from './routes/validation.js';
import { ProviderError, QuotaExhaustedError } from './provider/apiFootball.js';
import type { Logger } from './logger.js';

/**
 * A subscription is the largest body the API accepts: three id lists, each capped at 500
 * ids by the store. 32kb clears that several times over and still refuses anything that
 * could only be an attempt to make the process parse megabytes.
 */
const JSON_BODY_LIMIT = '32kb';

/** Health checks fire every few seconds; at info they would be the only thing in the log. */
const QUIET_PATHS: ReadonlySet<string> = new Set(['/v1/health', '/']);

const SAFE_REQUEST_ID = /^[A-Za-z0-9_.:-]{1,128}$/;

const RETRY_AFTER_MIN_SECONDS = 1;
const RETRY_AFTER_MAX_SECONDS = 86_400;

export function createApp(deps: ApiDeps): Express {
  const app = express();

  app.disable('x-powered-by');
  // Render terminates TLS in front of the service, so without this every req.ip is the
  // proxy's and every logged client address is the same one.
  app.set('trust proxy', true);

  app.use(requestContext(deps.logger));
  app.use(corsForReads());
  app.use(express.json({ limit: JSON_BODY_LIMIT, strict: true }));

  app.get('/', (_req: Request, res: Response) => {
    res.json({ service: 'kickoff', health: '/v1/health' });
  });

  // Built once, here rather than in index.ts, so the routers that need it are handed the
  // same instance and an unconfigured Clerk is one warning at startup instead of one per
  // route. `auth.requireUser` answers 503 when there are no credentials; see auth/clerk.ts.
  const auth = createClerkAuth({ config: deps.config, store: deps.store, logger: deps.logger });

  const v1 = express.Router();
  v1.use(createHealthRouter(deps));
  v1.use(createConfigRouter(deps, auth));
  v1.use(createCatalogueRouter(deps));
  v1.use(createFixturesRouter(deps));
  v1.use(createPlayersRouter(deps));
  v1.use(createDevicesRouter(deps));
  v1.use(createSubscriptionsRouter(deps));
  v1.use(createMeRouter(deps, auth));
  v1.use(createGroupsRouter(deps, auth));
  v1.use(createAdminRouter(deps));
  app.use('/v1', v1);

  app.use((req: Request, res: Response) => {
    res.status(404).json({
      error: `No route for ${req.method} ${req.path}.`,
      requestId: requestIdOf(res),
    });
  });

  app.use(errorHandler(deps.logger));

  return app;
}

/**
 * Stamps every request with an id (honouring an upstream one when it looks sane) and logs
 * one line per response. The id goes back in the header and in every error body, so a user
 * report and a log line can be tied together without guessing at timestamps.
 */
function requestContext(logger: Logger): RequestHandler {
  return (req: Request, res: Response, next: NextFunction) => {
    const inbound = req.get('x-request-id');
    const requestId = inbound && SAFE_REQUEST_ID.test(inbound) ? inbound : randomUUID();
    res.locals.requestId = requestId;
    res.setHeader('x-request-id', requestId);

    const startedAt = process.hrtime.bigint();
    res.on('finish', () => {
      const durationMs = Number(process.hrtime.bigint() - startedAt) / 1e6;
      const fields = {
        requestId,
        method: req.method,
        path: req.originalUrl,
        status: res.statusCode,
        durationMs: Math.round(durationMs * 10) / 10,
      };
      if (res.statusCode >= 500) logger.error('request failed', fields);
      else if (res.statusCode >= 400) logger.warn('request rejected', fields);
      else if (QUIET_PATHS.has(req.path)) logger.debug('request', fields);
      else logger.info('request', fields);
    });

    next();
  };
}

/**
 * Reads are public data and are opened to any origin. The writes are not: they carry an FCM
 * registration token in the body and are only ever called by the app, so the preflight
 * advertises the safe verbs alone and a browser is refused before it can send one.
 */
function corsForReads(): RequestHandler {
  return (req: Request, res: Response, next: NextFunction) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
    // Authorization is allowed through so that a browser can read the authenticated GETs
    // (/v1/me, a group's leaderboard). The *methods* are unchanged: the writes still carry
    // credentials the app alone should be sending, so a browser is refused at the preflight.
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type, X-Request-Id');
    res.setHeader('Access-Control-Max-Age', '86400');
    if (req.method === 'OPTIONS') {
      res.status(204).end();
      return;
    }
    next();
  };
}

interface MappedError {
  status: number;
  /** What the client is told. Never a stack, never an upstream body. */
  message: string;
  retryAfterSeconds?: number | undefined;
}

function errorHandler(logger: Logger): ErrorRequestHandler {
  return (error: unknown, req: Request, res: Response, _next: NextFunction) => {
    const requestId = requestIdOf(res);
    const mapped = mapError(error);

    const fields = {
      requestId,
      method: req.method,
      path: req.originalUrl,
      status: mapped.status,
      error,
    };
    if (mapped.status >= 500) logger.error('request error', fields);
    else logger.warn('request error', fields);

    // A response that has already started streaming cannot be turned into a JSON error;
    // killing the socket is the only signal left that the body is incomplete.
    if (res.headersSent) {
      res.destroy();
      return;
    }

    if (mapped.retryAfterSeconds !== undefined) {
      res.setHeader('Retry-After', String(mapped.retryAfterSeconds));
    }
    res.status(mapped.status).json({ error: mapped.message, requestId });
  };
}

function mapError(error: unknown): MappedError {
  if (error instanceof QuotaExhaustedError) {
    const seconds = clamp(
      Math.ceil((error.resetsAt.getTime() - Date.now()) / 1000),
      RETRY_AFTER_MIN_SECONDS,
      RETRY_AFTER_MAX_SECONDS,
    );
    return {
      status: 429,
      message: 'Upstream request budget exhausted; retry after the daily reset.',
      retryAfterSeconds: seconds,
    };
  }

  if (error instanceof ProviderError) {
    // Deliberately generic: the provider states an invalid key or an expired plan in the
    // body it returns, and echoing that to an anonymous caller tells them the account's
    // state. The full detail is on the log line written above.
    return { status: 502, message: 'The football data provider could not be reached.' };
  }

  if (error instanceof HttpError) {
    return { status: error.status, message: error.message };
  }

  const bodyError = asBodyParserError(error);
  if (bodyError !== undefined) return bodyError;

  return { status: 500, message: 'Internal server error.' };
}

/** express.json failures arrive as plain errors carrying `type` and `status`. */
function asBodyParserError(error: unknown): MappedError | undefined {
  if (typeof error !== 'object' || error === null) return undefined;
  const type = (error as { type?: unknown }).type;
  if (type === 'entity.too.large') {
    return { status: 413, message: `Request body must be smaller than ${JSON_BODY_LIMIT}.` };
  }
  if (typeof type === 'string' && type.startsWith('entity.')) {
    return { status: 400, message: 'Request body is not valid JSON.' };
  }
  return undefined;
}

function requestIdOf(res: Response): string {
  const requestId = res.locals.requestId;
  return typeof requestId === 'string' ? requestId : 'unknown';
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
