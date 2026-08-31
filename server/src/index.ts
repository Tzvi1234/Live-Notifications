/**
 * Process entry point: build the dependencies, serve the API, run the poller, and put both
 * down cleanly when Render replaces the instance.
 */

import type { Server } from 'node:http';

import type { Express } from 'express';

import { createApp } from './app.js';
import { config, type KickoffConfig } from './config.js';
import { logger, setLogLevel } from './logger.js';
import { createLivePoller } from './poller/livePoller.js';
import { ApiFootballClient } from './provider/apiFootball.js';
import { initPush } from './push/fcm.js';
import { createStore, type Store } from './store/index.js';
import type { PollerHandle } from './routes/deps.js';

/**
 * Render sends SIGTERM and follows it with SIGKILL 30 seconds later; finishing well inside
 * that leaves room for the log lines describing what was closed.
 */
const SHUTDOWN_GRACE_MS = 20_000;

/**
 * Render's proxy keeps connections alive for 60s. A server that gives up first closes a
 * socket the proxy is still holding, and the client sees a 502 for a request that was
 * never made; both values sit above the proxy's, headers last.
 */
const KEEP_ALIVE_TIMEOUT_MS = 65_000;
const HEADERS_TIMEOUT_MS = 66_000;

/** What this module needs from the poller on top of the two calls the admin route uses. */
interface RunningPoller extends PollerHandle {
  start(): void | Promise<void>;
  stop(): void | Promise<void>;
}

async function main(): Promise<void> {
  setLogLevel(config.logLevel);
  logger.info('kickoff server starting', describeConfig(config));

  const store = await createStore({ databaseUrl: config.databaseUrl }, logger);

  const provider = new ApiFootballClient({
    apiKey: config.apiFootballKey,
    baseUrl: config.apiFootballBaseUrl,
    budget: config.dailyRequestBudget,
    cacheTtlSeconds: config.cacheTtlSeconds,
    logger: logger.child({ component: 'provider' }),
  });

  // Initialised here rather than on the first goal, so a broken service account is a line in
  // the startup log instead of a notification that silently never arrives.
  initPush();

  // Built only when polling is on: with POLL_ENABLED=false there is nothing for /v1/admin/poll
  // to drive either, and the REST surface is meant to keep working without a poller at all.
  const poller: RunningPoller | undefined = config.pollEnabled
    ? createLivePoller({ client: provider, store, logger: logger.child({ component: 'poller' }) })
    : undefined;

  const app = createApp({ config, store, provider, logger, poller });

  // 0.0.0.0, not localhost: Render routes to the container's external interface and a
  // service bound to the loopback fails its health check with nothing in the log.
  const server = await listen(app, config.port);
  server.keepAliveTimeout = KEEP_ALIVE_TIMEOUT_MS;
  server.headersTimeout = HEADERS_TIMEOUT_MS;

  if (poller) {
    await poller.start();
  } else {
    logger.warn('polling disabled (POLL_ENABLED=false); no notifications will be sent');
  }

  installProcessHandlers({ server, store, poller });
}

function listen(app: Express, port: number): Promise<Server> {
  return new Promise((resolve, reject) => {
    const server = app.listen(port, '0.0.0.0', () => {
      logger.info('listening', { port, host: '0.0.0.0' });
      resolve(server);
    });
    server.once('error', reject);
  });
}

interface ShutdownContext {
  server: Server;
  store: Store;
  poller: RunningPoller | undefined;
}

function installProcessHandlers(context: ShutdownContext): void {
  let shuttingDown = false;

  const shutdown = (signal: string, exitCode: number): void => {
    if (shuttingDown) {
      logger.warn('shutdown already in progress', { signal });
      return;
    }
    shuttingDown = true;
    void gracefulShutdown(context, signal, exitCode);
  };

  process.on('SIGTERM', () => shutdown('SIGTERM', 0));
  process.on('SIGINT', () => shutdown('SIGINT', 0));

  process.on('unhandledRejection', (reason) => {
    // Not fatal: a rejected push or a provider hiccup must not take the API down with it.
    logger.error('unhandled promise rejection', { error: reason });
  });

  process.on('uncaughtException', (error) => {
    logger.error('uncaught exception; shutting down', { error });
    shutdown('uncaughtException', 1);
  });
}

async function gracefulShutdown(
  context: ShutdownContext,
  signal: string,
  exitCode: number,
): Promise<void> {
  logger.info('shutdown started', { signal });

  const watchdog = setTimeout(() => {
    logger.error('shutdown timed out; exiting hard', { graceMs: SHUTDOWN_GRACE_MS });
    process.exit(exitCode === 0 ? 1 : exitCode);
  }, SHUTDOWN_GRACE_MS);
  watchdog.unref();

  try {
    // Stop producing before closing anything the production depends on.
    await context.poller?.stop();

    // Handing the lock back now lets the replacement instance start polling immediately
    // instead of waiting out the lock's TTL with nobody watching the live matches.
    await context.store.releaseLeaderLock();

    await closeServer(context.server);
    await context.store.close();
    logger.info('shutdown complete', { signal });
  } catch (error) {
    logger.error('shutdown failed', { signal, error });
    clearTimeout(watchdog);
    process.exit(exitCode === 0 ? 1 : exitCode);
  }

  clearTimeout(watchdog);
  process.exit(exitCode);
}

function closeServer(server: Server): Promise<void> {
  return new Promise((resolve) => {
    server.close(() => resolve());
    // `close` only stops new connections; without this the idle keep-alive sockets Render's
    // proxy holds open would keep the callback pending until each one times out.
    server.closeIdleConnections();
  });
}

/**
 * The startup line an operator reads to know what this deploy is actually running. Secrets
 * are described, never printed: the API key and the service account are the two things a
 * log drain must not carry, and a database URL embeds its password.
 */
function describeConfig(active: KickoffConfig): Record<string, unknown> {
  return {
    nodeEnv: active.nodeEnv,
    port: active.port,
    logLevel: active.logLevel,
    version: active.version,
    provider: active.providerName,
    apiFootballBaseUrl: active.apiFootballBaseUrl,
    apiFootballKey: describeSecret(active.apiFootballKey),
    database: active.databaseUrl === undefined ? 'in-memory' : describeDatabaseUrl(active.databaseUrl),
    firebase: describeFirebase(active),
    firebaseProjectId: active.firebaseProjectId ?? 'from service account',
    pollEnabled: active.pollEnabled,
    pollIntervalSeconds: active.pollIntervalSeconds,
    pollIdleIntervalSeconds: active.pollIdleIntervalSeconds,
    preMatchLeadMinutes: active.preMatchLeadMinutes,
    dailyRequestBudget: active.dailyRequestBudget,
    cacheTtlSeconds: active.cacheTtlSeconds,
    featuredLeagueIds: [...active.featuredLeagueIds],
    adminToken: active.adminToken === undefined ? 'unset (admin routes disabled)' : 'set',
  };
}

function describeSecret(secret: string): string {
  return `set (${secret.length} chars)`;
}

function describeDatabaseUrl(url: string): string {
  try {
    const parsed = new URL(url);
    return `${parsed.protocol}//${parsed.username ? '***@' : ''}${parsed.host}${parsed.pathname}`;
  } catch {
    return 'set (unparseable)';
  }
}

function describeFirebase(active: KickoffConfig): string {
  if (active.googleApplicationCredentials !== undefined) {
    return `service account file ${active.googleApplicationCredentials}`;
  }
  if (active.firebaseServiceAccountB64 !== undefined) return 'service account from base64 env';
  return 'disabled (no credentials)';
}

main().catch((error: unknown) => {
  logger.error('startup failed', { error });
  process.exit(1);
});
