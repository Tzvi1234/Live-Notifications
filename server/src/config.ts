/**
 * Environment parsing. Every knob the server reads lives here, is validated once at startup
 * and exposed frozen; the only other `process.env` read in the process is logger.ts's own
 * LOG_LEVEL bootstrap, which has to work before this module has parsed anything.
 */

import { isLogLevel, LOG_LEVELS, logger, type LogLevel } from './logger.js';

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
  /** An explicit ceiling, or undefined to follow whatever the plan allows. */
  readonly dailyRequestBudget?: number | undefined;
  readonly featuredLeagueIds: readonly number[];

  readonly adminToken?: string | undefined;
  readonly cacheTtlSeconds: number;

  readonly clerkSecretKey?: string | undefined;
  readonly clerkPublishableKey?: string | undefined;
  readonly clerkJwtKey?: string | undefined;
  /**
   * Whether the authenticated surface (/v1/me, /v1/groups/*) can answer at all. The secret
   * key alone decides it: CLERK_JWT_KEY only makes verification networkless, and the
   * publishable key is for the client. All three are optional so an instance configured for
   * nothing but live notifications still boots; `requireUser` answers 503 when this is false.
   */
  readonly hasClerk: boolean;
}

/**
 * The competitions the app offers, in the order it offers them.
 *
 * This is the same list the app's direct-API path carries in
 * `ApiFootballDataSource.FEATURED_LEAGUE_IDS`, and the two must stay in step: they had
 * drifted, and the result was that choosing the matchUP server - the recommended path -
 * silently got you fourteen competitions where using your own key got you thirty. The
 * English and Israeli pyramids run all the way down to their domestic cups because those
 * are the ones actually followed here.
 *
 * An id the provider does not recognise is skipped rather than fatal (see `pickFeatured`),
 * so the list can be edited without a redeploy being a gamble.
 */
const DEFAULT_FEATURED_LEAGUE_IDS = [
  // England, top to bottom, plus the domestic cups.
  39, // Premier League
  40, // Championship
  41, // League One
  42, // League Two
  45, // FA Cup
  48, // League Cup (Carabao)
  528, // Community Shield
  // Israel.
  383, // Ligat ha'Al
  382, // Liga Leumit
  384, // State Cup
  385, // Toto Cup Ligat Al
  // Spain, league and cup.
  140, // La Liga
  143, // Copa del Rey
  141, // Segunda Division
  // The rest of the big five.
  135, // Serie A
  78, // Bundesliga
  61, // Ligue 1
  // Leagues people follow a player into.
  88, // Eredivisie
  94, // Primeira Liga
  203, // Super Lig
  253, // Major League Soccer
  71, // Brasileirao Serie A
  128, // Liga Profesional (Argentina)
  // Europe and international.
  2, // UEFA Champions League
  3, // UEFA Europa League
  848, // UEFA Europa Conference League
  531, // UEFA Super Cup
  5, // UEFA Nations League
  1, // World Cup
  4, // Euro Championship
  15, // FIFA Club World Cup
].join(',');

/** How many competitions the code ships, for the boot warning that says an override shrank it. */
export const DEFAULT_FEATURED_LEAGUE_COUNT = DEFAULT_FEATURED_LEAGUE_IDS.split(',').length;

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

/** Like [readInt] but with no default: absent means "not set", not "use this number". */
function optionalInt(env: Env, name: string, min: number, max: number): number | undefined {
  const value = raw(env, name);
  if (value === undefined) return undefined;
  const parsed = Number(value);
  if (!Number.isInteger(parsed)) {
    throw configError(`${name} must be a whole number, got "${value}".`);
  }
  if (parsed < min || parsed > max) {
    throw configError(`${name} must be between ${min} and ${max}, got ${parsed}.`);
  }
  return parsed;
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
  if (!isLogLevel(value)) {
    throw configError(`LOG_LEVEL must be one of ${LOG_LEVELS.join(', ')}, got "${value}".`);
  }
  return value;
}

/**
 * A base URL is only ever exercised on the first provider call, so a missing scheme would
 * otherwise surface as a `fetch` TypeError once per poll forever instead of at boot. The
 * trailing slash goes because callers join paths as `${base}/fixtures`.
 */
function readBaseUrl(env: Env, name: string, fallback: string): string {
  const value = raw(env, name) ?? fallback;
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw configError(`${name} must be an absolute URL, got "${value}".`);
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw configError(`${name} must be an http(s) URL, got "${value}".`);
  }
  return value.replace(/\/+$/, '');
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
  // match, which is why an explicit budget sits well under the plan limit rather than at it.
  // Undefined when unset, deliberately: the provider reports its own daily allowance in
  // every response header, and following it is right in both directions. A constant here
  // meant an upgraded plan changed nothing - the account would allow 75,000 a day while
  // this counter kept refusing the 7,501st. Set it only to spend LESS than the plan allows.
  //
  // The key ends in _OVERRIDE, and that suffix is the fix for a trap this deployment fell
  // into twice: an environment variable set once in a dashboard outlives every commit, and
  // `env ?? default` means the dashboard silently wins for ever. The old DAILY_REQUEST_BUDGET
  // is still set on the live service, pinning a 75,000-a-day plan at 7,500. Reading a new
  // name is the one change that makes the code win without anybody having to find and
  // delete the stale value. The old names are reported at boot, and ignored.
  const dailyRequestBudget = optionalInt(env, 'DAILY_REQUEST_BUDGET_OVERRIDE', 100, 10_000_000);

  const clerkSecretKey = raw(env, 'CLERK_SECRET_KEY');
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
    apiFootballBaseUrl: readBaseUrl(
      env,
      'API_FOOTBALL_BASE_URL',
      'https://v3.football.api-sports.io',
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
    // _OVERRIDE for the reason given at dailyRequestBudget: the un-suffixed name is still
    // set on the live service, frozen at the fourteen competitions of a much older build,
    // and it was silently winning over every league added in code since.
    featuredLeagueIds: readIdList(env, 'FEATURED_LEAGUE_IDS_OVERRIDE', DEFAULT_FEATURED_LEAGUE_IDS),

    adminToken: raw(env, 'ADMIN_TOKEN'),
    cacheTtlSeconds: readInt(env, 'CACHE_TTL_SECONDS', 60, 0, 86_400),

    clerkSecretKey,
    clerkPublishableKey: raw(env, 'CLERK_PUBLISHABLE_KEY'),
    clerkJwtKey: readClerkJwtKey(env),
    hasClerk: clerkSecretKey !== undefined,
  });
}

/**
 * Clerk prints the JWKS public key as a PEM block. Copied out of the dashboard through a
 * shell or a Render form it usually arrives with its newlines escaped as the two characters
 * `\n`, which the verifier reads as part of the base64 body and rejects with nothing more
 * useful than "invalid key". Unescaping here is the difference between a working deploy and
 * a paste that looks right.
 */
function readClerkJwtKey(env: Env): string | undefined {
  return raw(env, 'CLERK_JWT_KEY')?.replace(/\\n/g, '\n');
}

/**
 * Evaluated while the module graph is still loading, which is before index.ts's
 * `main().catch` exists — without this line a bad environment leaves nothing but a raw
 * stack on stderr, in a deploy whose every other line is JSON.
 */
function loadConfigOrFail(): KickoffConfig {
  try {
    return loadConfig();
  } catch (error) {
    logger.error('configuration invalid; refusing to start', { error });
    throw error;
  }
}

export const config: KickoffConfig = loadConfigOrFail();
