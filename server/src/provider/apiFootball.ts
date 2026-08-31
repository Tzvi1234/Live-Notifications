/**
 * API-Football v3 client.
 *
 * Three provider quirks shape this file:
 *  - auth, plan and quota failures come back as **HTTP 200** with the problem described
 *    inside the envelope's `errors` field, so the status code never proves a call worked;
 *  - `errors` is `[]` when empty but an object like `{"token":"..."}` when not, so it has
 *    to be inspected structurally rather than typed as one or the other;
 *  - the account quota is a hard daily wall, so a local counter refuses the call before it
 *    is issued instead of burning the last requests on something the poller can retry later.
 */

const DEFAULT_BASE_URL = 'https://v3.football.api-sports.io';
const MEDIA_BASE_URL = 'https://media.api-sports.io/football';
const DEFAULT_BUDGET = 7500;
const DEFAULT_CACHE_TTL_SECONDS = 60;
const DEFAULT_TIMEOUT_MS = 10_000;
const DAY_MS = 86_400_000;
/** Warn once the account's daily allowance is down to this fraction of the plan limit. */
const LOW_QUOTA_FRACTION = 0.1;
const MAX_CACHE_ENTRIES = 500;

export type LogFn = (message: string, meta?: Record<string, unknown>) => void;

export interface ProviderLogger {
  debug: LogFn;
  info: LogFn;
  warn: LogFn;
  error: LogFn;
}

const NOOP_LOGGER: ProviderLogger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
};

export type QueryParams = Record<string, string | number | boolean | undefined>;

/** Every API-Football response is wrapped in this envelope. */
export interface ApiEnvelope<T> {
  get?: string;
  parameters?: unknown;
  errors?: unknown;
  results?: number;
  paging?: { current?: number; total?: number };
  response?: T[];
}

export interface ApiPlayerRef {
  id?: number | null;
  name?: string | null;
}

export interface ApiKit {
  primary?: string | null;
  number?: string | null;
  border?: string | null;
}

export interface ApiLineupColors {
  player?: ApiKit | null;
  goalkeeper?: ApiKit | null;
}

export interface ApiTeamRef {
  id?: number | null;
  name?: string | null;
  logo?: string | null;
  winner?: boolean | null;
  colors?: ApiLineupColors | null;
}

export interface ApiVenue {
  id?: number | null;
  name?: string | null;
  city?: string | null;
}

export interface ApiStatus {
  long?: string | null;
  short?: string | null;
  elapsed?: number | null;
  extra?: number | null;
}

export interface ApiFixtureInfo {
  id: number;
  referee?: string | null;
  timezone?: string | null;
  date?: string | null;
  /** Seconds since epoch — the only unambiguous kick-off field. */
  timestamp?: number | null;
  periods?: { first?: number | null; second?: number | null } | null;
  venue?: ApiVenue | null;
  status?: ApiStatus | null;
}

export interface ApiLeagueRef {
  id?: number | null;
  name?: string | null;
  country?: string | null;
  logo?: string | null;
  flag?: string | null;
  season?: number | null;
  round?: string | null;
}

export interface ApiGoals {
  home?: number | null;
  away?: number | null;
}

export interface ApiFixture {
  fixture: ApiFixtureInfo;
  league?: ApiLeagueRef | null;
  teams?: { home?: ApiTeamRef | null; away?: ApiTeamRef | null } | null;
  goals?: ApiGoals | null;
  score?: {
    halftime?: ApiGoals | null;
    fulltime?: ApiGoals | null;
    extratime?: ApiGoals | null;
    penalty?: ApiGoals | null;
  } | null;
}

export interface ApiEvent {
  time?: { elapsed?: number | null; extra?: number | null } | null;
  team?: ApiTeamRef | null;
  /** For `type: "subst"` this is the player coming ON. */
  player?: ApiPlayerRef | null;
  /** For `type: "subst"` this is the player going OFF. */
  assist?: ApiPlayerRef | null;
  type?: string | null;
  detail?: string | null;
  comments?: string | null;
}

export interface ApiLineupPlayer {
  id?: number | null;
  name?: string | null;
  number?: number | null;
  pos?: string | null;
  /** "row:column", counted outwards from the goalkeeper. Null on the bench. */
  grid?: string | null;
}

export interface ApiLineupSlot {
  player?: ApiLineupPlayer | null;
}

export interface ApiLineup {
  team?: ApiTeamRef | null;
  coach?: { id?: number | null; name?: string | null; photo?: string | null } | null;
  formation?: string | null;
  startXI?: ApiLineupSlot[] | null;
  substitutes?: ApiLineupSlot[] | null;
}

export interface ApiStatistic {
  type?: string | null;
  /** int, "55%" or null depending on the metric. */
  value?: string | number | null;
}

export interface ApiTeamStatistics {
  team?: ApiTeamRef | null;
  statistics?: ApiStatistic[] | null;
}

export interface ApiTeamCatalogueEntry {
  team?: {
    id?: number | null;
    name?: string | null;
    code?: string | null;
    country?: string | null;
    founded?: number | null;
    national?: boolean | null;
    logo?: string | null;
  } | null;
  venue?: ApiVenue | null;
}

export interface ApiLeagueCatalogueEntry {
  league?: {
    id?: number | null;
    name?: string | null;
    type?: string | null;
    logo?: string | null;
  } | null;
  country?: { name?: string | null; code?: string | null; flag?: string | null } | null;
  seasons?: Array<{ year?: number | null; start?: string | null; end?: string | null; current?: boolean | null }> | null;
}

export interface ProviderQuota {
  dailyLimit: number | undefined;
  dailyRemaining: number | undefined;
  minuteLimit: number | undefined;
  minuteRemaining: number | undefined;
}

export interface BudgetState {
  budget: number;
  used: number;
  remaining: number;
  /** Next 00:00 UTC — when the local counter and the provider's daily quota both reset. */
  resetsAt: Date;
}

/** A call reached the provider and came back unusable (transport, HTTP or envelope `errors`). */
export class ProviderError extends Error {
  readonly path: string;
  readonly status: number | undefined;
  readonly detail: string | undefined;

  constructor(
    message: string,
    options: { path: string; status?: number | undefined; detail?: string | undefined; cause?: unknown },
  ) {
    super(message, { cause: options.cause });
    this.name = 'ProviderError';
    this.path = options.path;
    this.status = options.status;
    this.detail = options.detail;
  }
}

/** The local daily budget is spent; the call was never issued. */
export class QuotaExhaustedError extends Error {
  readonly used: number;
  readonly budget: number;
  readonly resetsAt: Date;

  constructor(options: { path: string; used: number; budget: number; resetsAt: Date }) {
    super(
      `Daily request budget exhausted (${options.used}/${options.budget}); ` +
        `refusing ${options.path} until ${options.resetsAt.toISOString()}`,
    );
    this.name = 'QuotaExhaustedError';
    this.used = options.used;
    this.budget = options.budget;
    this.resetsAt = options.resetsAt;
  }
}

export interface ApiFootballClientOptions {
  apiKey: string;
  baseUrl?: string;
  logger?: ProviderLogger;
  /** DAILY_REQUEST_BUDGET: local ceiling, kept below the plan limit on purpose. */
  budget?: number;
  cacheTtlSeconds?: number;
  timeoutMs?: number;
  /** Injectable for tests; defaults to the Node 22 global fetch. */
  fetchImpl?: typeof fetch;
  /** Injectable clock, so budget rollover and cache expiry are testable. */
  now?: () => number;
}

export interface FixtureQuery {
  id?: number;
  date?: string;
  from?: string;
  to?: string;
  team?: number;
  league?: number;
  season?: number;
}

export interface TeamQuery {
  league?: number;
  season?: number;
  search?: string;
}

interface CacheEntry {
  expiresAt: number;
  value: unknown[];
}

export function teamCrestUrl(teamId: number): string {
  return `${MEDIA_BASE_URL}/teams/${teamId}.png`;
}

export function leagueLogoUrl(leagueId: number): string {
  return `${MEDIA_BASE_URL}/leagues/${leagueId}.png`;
}

export function playerPhotoUrl(playerId: number): string {
  return `${MEDIA_BASE_URL}/players/${playerId}.png`;
}

/**
 * Reads the envelope's `errors` whatever shape it arrived in and returns a human-readable
 * problem, or null when the call really did succeed. Empty array and empty object both mean OK.
 */
export function describeProviderErrors(errors: unknown): string | null {
  if (errors === null || errors === undefined) return null;
  if (Array.isArray(errors)) {
    if (errors.length === 0) return null;
    return errors.map(describeErrorValue).join('; ');
  }
  if (typeof errors === 'string') {
    const trimmed = errors.trim();
    return trimmed.length === 0 || trimmed === 'null' ? null : trimmed;
  }
  if (typeof errors === 'object') {
    const entries = Object.entries(errors as Record<string, unknown>);
    if (entries.length === 0) return null;
    return entries.map(([key, value]) => `${key}: ${describeErrorValue(value)}`).join('; ');
  }
  return String(errors);
}

/** Values are serialised, not coerced: `String({...})` would log the message as [object Object]. */
function describeErrorValue(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value ?? null);
}

export class ApiFootballClient {
  readonly #apiKey: string;
  readonly #baseUrl: string;
  readonly #logger: ProviderLogger;
  readonly #budget: number;
  readonly #cacheTtlMs: number;
  readonly #timeoutMs: number;
  readonly #fetch: typeof fetch;
  readonly #now: () => number;
  readonly #cache = new Map<string, CacheEntry>();
  /** Cacheable calls currently over the wire, keyed like the cache. See `#request`. */
  readonly #inflight = new Map<string, Promise<unknown[]>>();

  #quota: ProviderQuota = {
    dailyLimit: undefined,
    dailyRemaining: undefined,
    minuteLimit: undefined,
    minuteRemaining: undefined,
  };
  #used = 0;
  #budgetDay: number;
  #lowQuotaWarned = false;

  constructor(options: ApiFootballClientOptions) {
    this.#apiKey = options.apiKey;
    this.#baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '');
    this.#logger = options.logger ?? NOOP_LOGGER;
    this.#budget = options.budget ?? DEFAULT_BUDGET;
    this.#cacheTtlMs = (options.cacheTtlSeconds ?? DEFAULT_CACHE_TTL_SECONDS) * 1000;
    this.#timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.#fetch = options.fetchImpl ?? globalThis.fetch;
    this.#now = options.now ?? Date.now;
    this.#budgetDay = Math.floor(this.#now() / DAY_MS);
  }

  /** Last rate-limit headers seen. Undefined fields mean the provider has not told us yet. */
  getQuota(): ProviderQuota {
    return { ...this.#quota };
  }

  getBudgetState(): BudgetState {
    const day = Math.floor(this.#now() / DAY_MS);
    const used = day === this.#budgetDay ? this.#used : 0;
    return {
      budget: this.#budget,
      used,
      remaining: Math.max(0, this.#budget - used),
      resetsAt: new Date((day + 1) * DAY_MS),
    };
  }

  clearCache(): void {
    this.#cache.clear();
  }

  async leagues(current?: boolean): Promise<ApiLeagueCatalogueEntry[]> {
    return this.#request<ApiLeagueCatalogueEntry>('/leagues', { current }, true);
  }

  async teams(query: TeamQuery = {}): Promise<ApiTeamCatalogueEntry[]> {
    return this.#request<ApiTeamCatalogueEntry>(
      '/teams',
      { league: query.league, season: query.season, search: query.search },
      true,
    );
  }

  async fixtures(query: FixtureQuery = {}): Promise<ApiFixture[]> {
    // A calendar day (or range) of fixtures is stable enough to cache; a single fixture
    // fetched by id is usually an in-play one the poller is diffing, so it never is.
    const cacheable = query.id === undefined && (query.date !== undefined || query.from !== undefined);
    return this.#request<ApiFixture>(
      '/fixtures',
      {
        id: query.id,
        date: query.date,
        from: query.from,
        to: query.to,
        team: query.team,
        league: query.league,
        season: query.season,
      },
      cacheable,
    );
  }

  /**
   * One request returns every in-play match worldwide — the cheapest possible live poll.
   * Passing league ids narrows it server-side (`live=39-140`); results are never cached.
   */
  async liveFixtures(leagueIds?: number[]): Promise<ApiFixture[]> {
    const live = leagueIds && leagueIds.length > 0 ? leagueIds.join('-') : 'all';
    return this.#request<ApiFixture>('/fixtures', { live }, false);
  }

  async events(fixtureId: number): Promise<ApiEvent[]> {
    return this.#request<ApiEvent>('/fixtures/events', { fixture: fixtureId }, false);
  }

  async lineups(fixtureId: number): Promise<ApiLineup[]> {
    return this.#request<ApiLineup>('/fixtures/lineups', { fixture: fixtureId }, false);
  }

  async statistics(fixtureId: number): Promise<ApiTeamStatistics[]> {
    return this.#request<ApiTeamStatistics>('/fixtures/statistics', { fixture: fixtureId }, false);
  }

  async #request<T>(path: string, params: QueryParams, cacheable: boolean): Promise<T[]> {
    const key = cacheKey(path, params);
    if (!cacheable) return this.#fetchItems<T>(path, params, key, false);

    const hit = this.#cacheGet(key);
    if (hit) return [...hit] as T[];

    // Everyone who arrives while one call is in flight rides on it. Without this the cache
    // saves nothing under load: a screenful of users hitting an expired day query at once
    // would each spend a request from the daily budget for the same list of fixtures.
    const inflight = this.#inflight.get(key);
    if (inflight !== undefined) return [...(await inflight)] as T[];

    const call = this.#fetchItems<T>(path, params, key, true);
    this.#inflight.set(key, call as Promise<unknown[]>);
    try {
      return await call;
    } finally {
      this.#inflight.delete(key);
    }
  }

  async #fetchItems<T>(path: string, params: QueryParams, key: string, cacheable: boolean): Promise<T[]> {
    this.#spendBudget(path);

    const url = new URL(this.#baseUrl + path);
    for (const [name, value] of Object.entries(params)) {
      if (value !== undefined) url.searchParams.set(name, String(value));
    }

    let response: Response;
    try {
      response = await this.#fetch(url, {
        headers: { 'x-apisports-key': this.#apiKey, accept: 'application/json' },
        signal: AbortSignal.timeout(this.#timeoutMs),
      });
    } catch (cause) {
      throw new ProviderError(`Request to ${path} failed`, { path, cause });
    }

    this.#readQuotaHeaders(response.headers);

    if (!response.ok) {
      const body = await safeText(response);
      throw new ProviderError(`${path} returned HTTP ${response.status}`, {
        path,
        status: response.status,
        detail: body,
      });
    }

    let payload: unknown;
    try {
      payload = await response.json();
    } catch (cause) {
      throw new ProviderError(`${path} returned a body that is not JSON`, { path, status: response.status, cause });
    }

    // An edge proxy can answer 200 with `null` or a bare array. Reading `errors` off that
    // throws a TypeError, which sails past every `instanceof ProviderError` guard upstream
    // and turns a provider hiccup into a 500 instead of a degraded section.
    if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
      throw new ProviderError(`${path} returned a body that is not an API-Football envelope`, {
        path,
        status: response.status,
        detail: JSON.stringify(payload ?? null).slice(0, 200),
      });
    }
    const envelope = payload as ApiEnvelope<T>;

    // The HTTP status is 200 here even for a dead key or a blown plan quota.
    const problem = describeProviderErrors(envelope.errors);
    if (problem !== null) {
      throw new ProviderError(`${path} reported "${problem}"`, { path, status: response.status, detail: problem });
    }

    const items = Array.isArray(envelope.response) ? envelope.response : [];
    // The cached array must not be the one handed to the caller: a consumer that sorts or
    // truncates its result in place would otherwise rewrite every hit until the TTL expires.
    if (cacheable) this.#cacheSet(key, [...items] as unknown[]);
    return items;
  }

  #spendBudget(path: string): void {
    const day = Math.floor(this.#now() / DAY_MS);
    if (day !== this.#budgetDay) {
      this.#budgetDay = day;
      this.#used = 0;
      this.#lowQuotaWarned = false;
    }
    if (this.#used >= this.#budget) {
      throw new QuotaExhaustedError({
        path,
        used: this.#used,
        budget: this.#budget,
        resetsAt: new Date((day + 1) * DAY_MS),
      });
    }
    this.#used += 1;
  }

  #readQuotaHeaders(headers: Headers): void {
    const quota: ProviderQuota = {
      dailyLimit: numberHeader(headers, 'x-ratelimit-requests-limit') ?? this.#quota.dailyLimit,
      dailyRemaining: numberHeader(headers, 'x-ratelimit-requests-remaining') ?? this.#quota.dailyRemaining,
      minuteLimit: numberHeader(headers, 'x-ratelimit-limit') ?? this.#quota.minuteLimit,
      minuteRemaining: numberHeader(headers, 'x-ratelimit-remaining') ?? this.#quota.minuteRemaining,
    };
    this.#quota = quota;

    const { dailyLimit, dailyRemaining } = quota;
    if (dailyLimit === undefined || dailyRemaining === undefined || dailyLimit <= 0) return;
    const low = dailyRemaining <= dailyLimit * LOW_QUOTA_FRACTION;
    // Warn on the crossing only, otherwise every remaining call of the day logs a warning.
    if (low && !this.#lowQuotaWarned) {
      this.#lowQuotaWarned = true;
      this.#logger.warn('API-Football daily quota nearly exhausted', {
        dailyRemaining,
        dailyLimit,
        localBudgetUsed: this.#used,
      });
    } else if (!low && this.#lowQuotaWarned) {
      this.#lowQuotaWarned = false;
    }
  }

  #cacheGet(key: string): unknown[] | undefined {
    const entry = this.#cache.get(key);
    if (!entry) return undefined;
    if (entry.expiresAt <= this.#now()) {
      this.#cache.delete(key);
      return undefined;
    }
    return entry.value;
  }

  #cacheSet(key: string, value: unknown[]): void {
    if (this.#cacheTtlMs <= 0) return;
    if (this.#cache.size >= MAX_CACHE_ENTRIES) this.#sweepCache();
    this.#cache.set(key, { expiresAt: this.#now() + this.#cacheTtlMs, value });
  }

  #sweepCache(): void {
    const now = this.#now();
    for (const [key, entry] of this.#cache) {
      if (entry.expiresAt <= now) this.#cache.delete(key);
    }
    if (this.#cache.size >= MAX_CACHE_ENTRIES) {
      const oldest = this.#cache.keys().next();
      if (!oldest.done) this.#cache.delete(oldest.value);
    }
  }
}

function cacheKey(path: string, params: QueryParams): string {
  const query = Object.entries(params)
    .filter((entry): entry is [string, string | number | boolean] => entry[1] !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([name, value]) => `${name}=${String(value)}`)
    .join('&');
  return `${path}?${query}`;
}

function numberHeader(headers: Headers, name: string): number | undefined {
  const raw = headers.get(name);
  if (raw === null) return undefined;
  const value = Number(raw);
  return Number.isFinite(value) ? value : undefined;
}

async function safeText(response: Response): Promise<string | undefined> {
  try {
    const text = await response.text();
    return text.slice(0, 500);
  } catch {
    return undefined;
  }
}
