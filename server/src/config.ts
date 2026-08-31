/**
 * Environment parsing. Every knob the server reads lives here, is validated once at
 * startup, and is exposed frozen — nothing else in the process touches `process.env`.
 */

import { LOG_LEVELS, type LogLevel } from './logger.js';

export type { LogLevel };

export interface KickoffConfig {
  readonly nodeEnv: string;
  readonly isProduction: boolean;
  readonly port: number;
  readonly logLevel: LogLevel;
  /** Reported by /v1/health so a deploy can be identified from the app. */
  readonly version: string;
  readonly providerName: string;

  readonly apiFootballKey: string;
  readonly apiFootballBaseUrl: string;

  readonly databaseUrl?: string | undefined;
  readonly hasDatabase: boolean;

  readonly googleApplicationCredentials?: string | undefined;
  readonly firebaseServiceAccountB64?: string | undefined;
  readonly firebaseProjectId?: string | undefined;
  readonly hasFirebase: boolean;

  readonly pollEnabled: boolean;
  readonly pollIntervalSeconds: number;
  readonly pollIdleIntervalSeconds: number;
  readonly preMatchLeadMinutes: number;
  readonly dailyRequestBudget: number;
  readonly featuredLeagueIds: readonly number[];

  readonly adminToken?: string | undefined;
  readonly cacheTtlSeconds: number;
}

const DEFAULT_FEATURED_LEAGUE_IDS = '39,140,135,78,61,2,3,88,94,203,383,253,71,128';

type Env = Record<string, string | undefined>;

function raw(env: Env, name: string): string | undefined {
  const value = env[name];
  if (value === undefined) return undefined;
  const trimmed = value.trim();
  // Render writes empty strings for cleared vars; treat those as unset so defaults apply.
  return trimmed.length > 0 ? trimmed : undefined;
}

function configError(message: string): Error {
  return new Error(`[config] ${message}`);
}

function readInt(env: Env, name: string, fallback: number, min: number, max: number): number {
  const value = raw(env, name);
  if (value === undefined) return fallback;
  const parsed = Number(value);
  if (!Number.isInteger(parsed)) {
    throw configError(`${name} must be a whole number, got "${value}".`);
  }
  if (parsed < min || parsed > max) {
    throw configError(`${name} must be between ${min} and ${max}, got ${parsed}.`);
  }
  return parsed;
}

function readBool(env: Env, name: string, fallback: boolean): boolean {
  const value = raw(env, name)?.toLowerCase();
  if (value === undefined) return fallback;
  if (['1', 'true', 'yes', 'on'].includes(value)) return true;
  if (['0', 'false', 'no', 'off'].includes(value)) return false;
  throw configError(`${name} must be a boolean ("true"/"false"), got "${value}".`);
}

function readIdList(env: Env, name: string, fallback: string): readonly number[] {
  const value = raw(env, name) ?? fallback;
  const ids = value
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
    .map((part) => {
      const id = Number(part);
      if (!Number.isInteger(id) || id <= 0) {
        throw configError(`${name} must be a comma-separated list of positive ids, got "${part}".`);
      }
      return id;
    });
  return Object.freeze([...new Set(ids)]);
}

function readLogLevel(env: Env): LogLevel {
  const value = raw(env, 'LOG_LEVEL')?.toLowerCase();
  if (value === undefined) return 'info';
  if (!LOG_LEVELS.includes(value as LogLevel)) {
    throw configError(`LOG_LEVEL must be one of ${LOG_LEVELS.join(', ')}, got "${value}".`);
  }
  return value as LogLevel;
}

function stripTrailingSlash(url: string): string {
  return url.endsWith('/') ? url.slice(0, -1) : url;
}

/**
 * Exported so tests can build a config from a literal env without touching the process.
 * Throws only for API_FOOTBALL_KEY and for malformed values; every optional integration
 * degrades instead (no DATABASE_URL -> in-memory stores, no credentials -> push disabled).
 */
export function loadConfig(env: Env = process.env): KickoffConfig {
  const apiFootballKey = raw(env, 'API_FOOTBALL_KEY');
  if (!apiFootballKey) {
    throw configError(
      'API_FOOTBALL_KEY is required. Create a key at https://dashboard.api-football.com, ' +
        'then set it in the Render service environment (or a local .env) before starting the server.',
    );
  }

  const nodeEnv = raw(env, 'NODE_ENV') ?? 'development';

  const pollIntervalSeconds = readInt(env, 'POLL_INTERVAL_SECONDS', 30, 5, 3600);
  const pollIdleIntervalSeconds = readInt(env, 'POLL_IDLE_INTERVAL_SECONDS', 300, 30, 86_400);
  if (pollIdleIntervalSeconds < pollIntervalSeconds) {
    throw configError(
      `POLL_IDLE_INTERVAL_SECONDS (${pollIdleIntervalSeconds}) must be >= POLL_INTERVAL_SECONDS ` +
        `(${pollIntervalSeconds}); the idle cadence is the slow one.`,
    );
  }

  // Quota arithmetic: the live poll is one `/fixtures?live=all` call per tick, so a day of
  // continuous live polling costs 86400 / POLL_INTERVAL_SECONDS requests (2880 at the 30s
  // default). Detail fetches (events/lineups/statistics) are charged on top, per tracked
  // match, which is why DAILY_REQUEST_BUDGET sits well under the plan limit rather than at it.
  const dailyRequestBudget = readInt(env, 'DAILY_REQUEST_BUDGET', 7500, 100, 10_000_000);

  const databaseUrl = raw(env, 'DATABASE_URL');
  const googleApplicationCredentials = raw(env, 'GOOGLE_APPLICATION_CREDENTIALS');
  const firebaseServiceAccountB64 = raw(env, 'FIREBASE_SERVICE_ACCOUNT_B64');

  return Object.freeze({
    nodeEnv,
    isProduction: nodeEnv === 'production',
    port: readInt(env, 'PORT', 8080, 1, 65_535),
    logLevel: readLogLevel(env),
    version: raw(env, 'npm_package_version') ?? '0.1.0',
    providerName: 'api-football',

    apiFootballKey,
    apiFootballBaseUrl: stripTrailingSlash(
      raw(env, 'API_FOOTBALL_BASE_URL') ?? 'https://v3.football.api-sports.io',
    ),

    databaseUrl,
    hasDatabase: databaseUrl !== undefined,

    googleApplicationCredentials,
    firebaseServiceAccountB64,
    firebaseProjectId: raw(env, 'FIREBASE_PROJECT_ID'),
    // Either credential source is enough; the Admin SDK reads the project id out of the
    // service-account JSON, so FIREBASE_PROJECT_ID is an override, not a requirement.
    hasFirebase:
      googleApplicationCredentials !== undefined || firebaseServiceAccountB64 !== undefined,

    pollEnabled: readBool(env, 'POLL_ENABLED', true),
    pollIntervalSeconds,
    pollIdleIntervalSeconds,
    preMatchLeadMinutes: readInt(env, 'PREMATCH_LEAD_MINUTES', 60, 0, 1440),
    dailyRequestBudget,
    featuredLeagueIds: readIdList(env, 'FEATURED_LEAGUE_IDS', DEFAULT_FEATURED_LEAGUE_IDS),

    adminToken: raw(env, 'ADMIN_TOKEN'),
    cacheTtlSeconds: readInt(env, 'CACHE_TTL_SECONDS', 60, 0, 86_400),
  });
}

export const config: KickoffConfig = loadConfig();
