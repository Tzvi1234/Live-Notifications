/**
 * FCM HTTP v1 transport, via firebase-admin (it mints and refreshes the OAuth2 bearer for
 * the `firebase.messaging` scope, so nothing here touches tokens or JWTs).
 *
 * Fan-out is per token rather than per topic: topics are optimised for throughput, not
 * latency, and only token sends return `messaging/registration-token-not-registered`,
 * which is the only way dead installs ever get pruned from the database.
 *
 * Credentials are optional. With none configured the module degrades to a no-op sender,
 * says so once at startup, and the REST API keeps serving.
 */

import { readFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';

import { cert, getApps, initializeApp, type App, type ServiceAccount } from 'firebase-admin/app';
import {
  getMessaging,
  type AndroidConfig,
  type Messaging,
  type MulticastMessage,
} from 'firebase-admin/messaging';

import { config } from '../config.js';
import { logger } from '../logger.js';
import type { PushMessage } from './payload.js';

/** `sendEachForMulticast` rejects anything larger; the caller's list is chunked to it. */
export const FCM_MULTICAST_LIMIT = 500;

/** One short pause, not a backoff ladder: a live goal is stale long before a second retry. */
const RETRY_BACKOFF_MS = 400;

/** Named so an app initialised elsewhere in the process cannot collide with this one. */
const APP_NAME = 'kickoff-push';

const NOT_REGISTERED = 'messaging/registration-token-not-registered';
const INVALID_ARGUMENT = 'messaging/invalid-argument';

const RETRYABLE_CODES: ReadonlySet<string> = new Set([
  'messaging/internal-error',
  'messaging/server-unavailable',
  'messaging/unavailable',
  'messaging/unknown-error',
]);

export interface SendResult {
  successCount: number;
  failureCount: number;
  /** Tokens FCM will never accept again; the caller must delete them. */
  invalidTokens: string[];
}

interface PushState {
  readonly enabled: boolean;
  readonly messaging?: Messaging | undefined;
}

let state: PushState | undefined;

function emptyResult(): SendResult {
  return { successCount: 0, failureCount: 0, invalidTokens: [] };
}

interface CredentialSource {
  readonly json: Record<string, unknown>;
  readonly origin: string;
}

/**
 * The Render Secret File is preferred: the JSON never passes through an environment
 * variable, so it cannot be truncated by a dashboard field or leak into a process listing.
 */
function readCredential(): CredentialSource | undefined {
  const path = config.googleApplicationCredentials;
  if (path !== undefined) {
    return { json: JSON.parse(readFileSync(path, 'utf8')) as Record<string, unknown>, origin: path };
  }
  const encoded = config.firebaseServiceAccountB64;
  if (encoded !== undefined) {
    const decoded = Buffer.from(encoded, 'base64').toString('utf8');
    return {
      json: JSON.parse(decoded) as Record<string, unknown>,
      origin: 'FIREBASE_SERVICE_ACCOUNT_B64',
    };
  }
  return undefined;
}

function toServiceAccount(json: Record<string, unknown>): ServiceAccount {
  const projectId = typeof json.project_id === 'string' ? json.project_id : undefined;
  const clientEmail = typeof json.client_email === 'string' ? json.client_email : undefined;
  const privateKey = typeof json.private_key === 'string' ? json.private_key : undefined;

  if (!projectId || !clientEmail || !privateKey) {
    throw new Error(
      'service account JSON is missing project_id, client_email or private_key ' +
        '(a truncated secret file looks exactly like this)',
    );
  }

  return {
    projectId: config.firebaseProjectId ?? projectId,
    clientEmail,
    // A key that has been through an env-var round trip carries literal backslash-n;
    // PEM parsing fails on those with an error that names neither the file nor the cause.
    privateKey: privateKey.includes('\\n') ? privateKey.replace(/\\n/g, '\n') : privateKey,
  };
}

function existingApp(): App | undefined {
  return getApps().find((app) => app.name === APP_NAME);
}

/**
 * Idempotent, and the single place that decides whether push is on. Safe to call from the
 * server bootstrap (so the "disabled" line lands at startup) and from every send.
 */
export function initPush(): boolean {
  if (state !== undefined) return state.enabled;

  let credential: CredentialSource | undefined;
  try {
    credential = readCredential();
  } catch (error) {
    state = { enabled: false };
    logger.error('push disabled: service account could not be read', { error });
    return false;
  }

  if (credential === undefined) {
    state = { enabled: false };
    logger.info(
      'push disabled: set GOOGLE_APPLICATION_CREDENTIALS (Render Secret File path) or ' +
        'FIREBASE_SERVICE_ACCOUNT_B64 to enable notifications; REST endpoints are unaffected',
    );
    return false;
  }

  try {
    const account = toServiceAccount(credential.json);
    const app =
      existingApp() ?? initializeApp({ credential: cert(account), projectId: account.projectId }, APP_NAME);
    state = { enabled: true, messaging: getMessaging(app) };
    logger.info('push enabled', { projectId: account.projectId, source: credential.origin });
    return true;
  } catch (error) {
    state = { enabled: false };
    logger.error('push disabled: firebase-admin failed to initialise', {
      error,
      source: credential.origin,
    });
    return false;
  }
}

export function isPushEnabled(): boolean {
  return initPush();
}

/**
 * Test seam: forgets the enabled/disabled decision so the next call re-derives it. The
 * firebase app itself is left in place and reused, so this is not a way to swap credentials.
 */
export function resetPushForTests(): void {
  state = undefined;
}

export function chunkTokens(tokens: string[], size: number = FCM_MULTICAST_LIMIT): string[][] {
  // Clamped, not trusted: a step of 0 loops forever and a step above 500 builds a batch FCM
  // rejects outright — both would be a caller's arithmetic taking the poll tick down with it.
  const step = Math.min(Math.max(Math.trunc(size), 1), FCM_MULTICAST_LIMIT);
  const chunks: string[][] = [];
  for (let i = 0; i < tokens.length; i += step) {
    chunks.push(tokens.slice(i, i + step));
  }
  return chunks;
}

/**
 * `MulticastMessage.tokens` carries a deprecation marker in favour of Firebase Installation
 * IDs. It stays: POST /v1/devices registers FCM registration tokens, so tokens are what the
 * device table holds, and the token path is the one that reports a dead install.
 */
function toMulticast(tokens: string[], envelope: PushMessage): MulticastMessage {
  const android: AndroidConfig = {
    priority: 'high',
    // MILLISECONDS here. The REST API takes "600s"; the Node SDK takes a number and
    // converts it, so passing 600 would ask for 0.6 seconds of TTL.
    ttl: envelope.ttlMillis,
  };
  if (envelope.collapseKey !== undefined) {
    android.collapseKey = envelope.collapseKey;
  }
  // No `notification` block on purpose: the client builds its own, and a notification here
  // would both duplicate it and hand delivery to the OS while the app is backgrounded.
  return { tokens, data: envelope.data, android };
}

function errorCode(error: unknown): string {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' ? code : '';
}

function isRetryable(error: unknown): boolean {
  const code = errorCode(error);
  if (RETRYABLE_CODES.has(code)) return true;
  const message = error instanceof Error ? error.message : String(error ?? '');
  return message.includes('UNAVAILABLE') || message.includes('INTERNAL');
}

interface ChunkOutcome {
  successCount: number;
  /** Tokens that did not succeed, for any reason. */
  failureCount: number;
  invalidTokens: string[];
  retryTokens: string[];
}

async function dispatch(
  messaging: Messaging,
  tokens: string[],
  envelope: PushMessage,
): Promise<ChunkOutcome> {
  let batch;
  try {
    batch = await messaging.sendEachForMulticast(toMulticast(tokens, envelope));
  } catch (error) {
    // A throw here is transport- or credential-level: it applies to the whole chunk.
    const retryable = isRetryable(error);
    logger.error('fcm multicast call failed', {
      error,
      matchId: envelope.matchId,
      kind: envelope.kind,
      tokens: tokens.length,
      retryable,
    });
    return {
      successCount: 0,
      failureCount: tokens.length,
      invalidTokens: [],
      retryTokens: retryable ? [...tokens] : [],
    };
  }

  const invalidTokens: string[] = [];
  const retryTokens: string[] = [];
  let invalidArgumentCount = 0;

  batch.responses.forEach((response, index) => {
    if (response.success) return;
    const token = tokens[index];
    if (token === undefined) return;
    const code = errorCode(response.error);
    if (code === NOT_REGISTERED) {
      invalidTokens.push(token);
    } else if (code === INVALID_ARGUMENT) {
      invalidArgumentCount += 1;
      invalidTokens.push(token);
    } else if (isRetryable(response.error)) {
      retryTokens.push(token);
    } else {
      logger.warn('fcm delivery failed', {
        matchId: envelope.matchId,
        kind: envelope.kind,
        code: code || 'unknown',
        error: response.error,
      });
    }
  });

  // `invalid-argument` blames the token *or* the message. When two or more independent
  // tokens fail with it and nothing succeeded, the message is the common factor — pruning
  // on that signal would wipe the device table over one malformed payload.
  if (
    tokens.length >= 2 &&
    invalidArgumentCount === tokens.length &&
    batch.successCount === 0
  ) {
    logger.error('fcm rejected every token with invalid-argument; treating it as a payload fault', {
      matchId: envelope.matchId,
      kind: envelope.kind,
      dataKeys: Object.keys(envelope.data).length,
    });
    invalidTokens.length = 0;
  }

  return {
    successCount: batch.successCount,
    failureCount: batch.failureCount,
    invalidTokens,
    retryTokens,
  };
}

async function sendChunk(
  messaging: Messaging,
  tokens: string[],
  envelope: PushMessage,
): Promise<SendResult> {
  const first = await dispatch(messaging, tokens, envelope);
  const result: SendResult = {
    successCount: first.successCount,
    failureCount: first.failureCount,
    invalidTokens: first.invalidTokens,
  };

  if (first.retryTokens.length === 0) return result;

  await delay(RETRY_BACKOFF_MS);
  const second = await dispatch(messaging, first.retryTokens, envelope);
  result.successCount += second.successCount;
  // The retried tokens were already counted as failures; replace that share with the
  // second attempt's verdict rather than adding to it.
  result.failureCount = result.failureCount - first.retryTokens.length + second.failureCount;
  result.invalidTokens.push(...second.invalidTokens);
  return result;
}

/**
 * Sends one message to many devices. Never throws: a push failure must not take down the
 * poll tick that produced it.
 */
export async function sendToTokens(tokens: string[], envelope: PushMessage): Promise<SendResult> {
  // A device registered twice would otherwise be notified twice and charged twice against
  // its 240/min cap.
  const unique = [...new Set(tokens.filter((token) => token.length > 0))];
  if (unique.length === 0) return emptyResult();

  if (!initPush() || state?.messaging === undefined) {
    logger.debug('push disabled; message dropped', {
      matchId: envelope.matchId,
      kind: envelope.kind,
      tokens: unique.length,
    });
    return emptyResult();
  }

  const messaging = state.messaging;
  const total = emptyResult();

  for (const chunk of chunkTokens(unique)) {
    const result = await sendChunk(messaging, chunk, envelope);
    total.successCount += result.successCount;
    total.failureCount += result.failureCount;
    total.invalidTokens.push(...result.invalidTokens);
  }

  // Ticks are one line per match per poll; at a 30s cadence they would bury everything else,
  // so only durable sends and anything that failed are logged at info.
  const write = envelope.kind === 'DURABLE' || total.failureCount > 0 ? logger.info : logger.debug;
  write('push sent', {
    matchId: envelope.matchId,
    kind: envelope.kind,
    eventId: envelope.eventId,
    collapseKey: envelope.collapseKey,
    ttlMillis: envelope.ttlMillis,
    successCount: total.successCount,
    failureCount: total.failureCount,
    invalidTokens: total.invalidTokens.length,
  });

  return total;
}
