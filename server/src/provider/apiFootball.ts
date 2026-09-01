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
const MINUTE_MS = 60_000;
/** Warn once the account's daily allowance is down to this fraction of the plan limit. */
const LOW_QUOTA_FRACTION = 0.1;
const MAX_CACHE_ENTRIES = 500;

/**
 * How long a cached answer stays servable AFTER its TTL, for the case where the refresh
 * fails.
 *
 * A stale league table is not a good answer; it is a far better one than an error page.
 * Only cacheable calls reach this - a live fixture is never cached - so nothing that
 * changes minute to minute can be served out of date by it.
 */
const STALE_GRACE_MS = 6 * 60 * 60 * 1000;

/**
 * How long each kind of answer is worth keeping, in seconds.
 *
 * CACHE_TTL_SECONDS is the general default and it is tuned for fixtures, which move. The
 * catalogue does not: the list of competitions changes once a year and a club's badge and
 * name change rather less often than that, so refreshing them every minute spends the
 * day's request budget re-fetching a list that is identical. These are the numbers that
 * make a screenful of users cost one upstream call instead of a hundred.
 */
const CATALOGUE_TTL_SECONDS = 12 * 60 * 60;
/** A registered squad changes at a transfer window, not during a match. */
const SQUAD_TTL_SECONDS = 6 * 60 * 60;
/** The provider computes these once per fixture; they do not move before kick-off. */
const PREDICTION_TTL_SECONDS = 60 * 60;

/** How long an empty answer is held. See `#fetchItems` for why it is not the full TTL. */
const EMPTY_RESULT_TTL_MS = 60_000;

/**
 * Requests left in the provider's PER-MINUTE window below which a call is held back rather
 * than issued. The daily budget is a wall the local counter refuses to walk into; the minute
 * window is a wall you can wait out, and waiting is strictly better than spending the day's
 * quota on a 429. Two rather than zero because a poll tick issues several calls in a burst
 * and the headers only describe the state *before* them.
 */
const MIN_MINUTE_HEADROOM = 2;

/**
 * Longest a single call waits for that window to roll over. The provider sends no reset
 * header, so the window's end is inferred (see `#readQuotaHeaders`) and can be most of a
 * minute away — but a REST handler that blocks for a minute is worse than one that tries
 * and is told to slow down, so the wait is capped well inside any client timeout and the
 * provider's own answer settles anything longer. Only the last MIN_MINUTE_HEADROOM requests
 * of a window pay it at all.
 */
const MAX_MINUTE_BACKOFF_MS = 5_000;

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
/**
 * API-Football's `/status`: who the key belongs to, whether the plan is live, and how much
 * of the day is spent.
 *
 * `account` is deliberately not read: the operator's name and email are of no use to this
 * server and every field it does not carry is one it cannot leak.
 */
export interface ApiAccountStatus {
  subscription?: {
    plan?: string | null;
    end?: string | null;
    active?: boolean | null;
  } | null;
  requests?: {
    current?: number | null;
    limit_day?: number | null;
  } | null;
}

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

/**
 * THE INLINE SECTIONS — the single biggest quota saving this client has.
 *
 * `GET /fixtures?id=<id>` does not return the bare fixture: it returns `events`, `lineups`,
 * `statistics` and `players` in the same response, so a detail view costs ONE request rather
 * than four. `GET /fixtures?live=all` carries `events` per fixture, so a poll tick's
 * per-match `/fixtures/events` call is already paid for by the live poll itself.
 *
 * They are optional because the other fixture queries — `?date=`, `?team=&from=&to=`,
 * `?league=` — do not carry them, and because a plan whose coverage excludes a section
 * omits it there too. Every consumer therefore uses the inline array *when it is present*
 * and falls back to the dedicated endpoint when it is not; an absent key and an empty array
 * are different answers, so these are `undefined`-able rather than defaulted to `[]`.
 */
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
  events?: ApiEvent[] | null;
  lineups?: ApiLineup[] | null;
  statistics?: ApiTeamStatistics[] | null;
  players?: ApiFixturePlayers[] | null;
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

/**
 * What the plan publishes for one season of one competition. The client needs it to tell a
 * competition with no line-up feed at all from one whose line-up is merely not out yet.
 */
export interface ApiCoverage {
  fixtures?: {
    events?: boolean | null;
    lineups?: boolean | null;
    statistics_fixtures?: boolean | null;
    statistics_players?: boolean | null;
  } | null;
  standings?: boolean | null;
  players?: boolean | null;
  top_scorers?: boolean | null;
  top_assists?: boolean | null;
  top_cards?: boolean | null;
  injuries?: boolean | null;
  predictions?: boolean | null;
  odds?: boolean | null;
}

export interface ApiSeasonEntry {
  year?: number | null;
  start?: string | null;
  end?: string | null;
  current?: boolean | null;
  coverage?: ApiCoverage | null;
}

export interface ApiLeagueCatalogueEntry {
  league?: {
    id?: number | null;
    name?: string | null;
    type?: string | null;
    logo?: string | null;
  } | null;
  country?: { name?: string | null; code?: string | null; flag?: string | null } | null;
  seasons?: ApiSeasonEntry[] | null;
}

/** `/players/squads?team=` — one entry, the whole squad. */
export interface ApiSquadEntry {
  team?: ApiTeamRef | null;
  players?: Array<{
    id?: number | null;
    name?: string | null;
    age?: number | null;
    number?: number | null;
    position?: string | null;
    photo?: string | null;
  }> | null;
}

export interface ApiPlayerInfo {
  id?: number | null;
  name?: string | null;
  firstname?: string | null;
  lastname?: string | null;
  age?: number | null;
  birth?: { date?: string | null; place?: string | null; country?: string | null } | null;
  nationality?: string | null;
  height?: string | null;
  weight?: string | null;
  injured?: boolean | null;
  photo?: string | null;
}

/** One competition-season line of a player's record; `/players` returns several. */
export interface ApiPlayerStatistics {
  team?: ApiTeamRef | null;
  league?: ApiLeagueRef | null;
  games?: {
    /** The provider's own spelling. */
    appearences?: number | null;
    lineups?: number | null;
    minutes?: number | null;
    number?: number | null;
    position?: string | null;
    rating?: string | null;
    captain?: boolean | null;
  } | null;
  goals?: {
    total?: number | null;
    conceded?: number | null;
    assists?: number | null;
    saves?: number | null;
  } | null;
  cards?: { yellow?: number | null; yellowred?: number | null; red?: number | null } | null;
}

export interface ApiPlayerEntry {
  player?: ApiPlayerInfo | null;
  statistics?: ApiPlayerStatistics[] | null;
}

/** One player's line in `/fixtures/players`; the array always holds exactly one entry. */
export interface ApiFixturePlayerStats {
  games?: {
    minutes?: number | null;
    number?: number | null;
    position?: string | null;
    rating?: string | null;
    captain?: boolean | null;
    substitute?: boolean | null;
  } | null;
  shots?: { total?: number | null; on?: number | null } | null;
  goals?: {
    total?: number | null;
    conceded?: number | null;
    assists?: number | null;
    saves?: number | null;
  } | null;
  passes?: { total?: number | null; key?: number | null; accuracy?: string | number | null } | null;
  tackles?: {
    total?: number | null;
    blocks?: number | null;
    interceptions?: number | null;
  } | null;
  duels?: { total?: number | null; won?: number | null } | null;
  cards?: { yellow?: number | null; red?: number | null } | null;
}

export interface ApiFixturePlayers {
  team?: ApiTeamRef | null;
  players?: Array<{
    player?: ApiPlayerInfo | null;
    statistics?: ApiFixturePlayerStats[] | null;
  }> | null;
}

/** `/predictions?fixture=` — the provider's own pre-match call, one entry per fixture. */
export interface ApiPrediction {
  predictions?: {
    winner?: { id?: number | null; name?: string | null; comment?: string | null } | null;
    win_or_draw?: boolean | null;
    under_over?: string | null;
    goals?: { home?: string | number | null; away?: string | number | null } | null;
    advice?: string | null;
    percent?: {
      home?: string | null;
      draw?: string | null;
      away?: string | null;
    } | null;
  } | null;
  league?: ApiLeagueRef | null;
  teams?: { home?: ApiTeamRef | null; away?: ApiTeamRef | null } | null;
  /** Metric -> per-side percentage, e.g. `{ form: { home: "60%", away: "40%" } }`. */
  comparison?: Record<
    string,
    { home?: string | number | null; away?: string | number | null } | null
  > | null;
}

export interface ProviderQuota {
  dailyLimit: number | undefined;
  dailyRemaining: number | undefined;
  minuteLimit: number | undefined;
  minuteRemaining: number | undefined;
  /**
   * Epoch millis at which the per-minute window is expected to roll over. Inferred, not
   * reported: the provider sends no reset header, so this is one minute from the last
   * response whose `minuteRemaining` went UP — the only observable sign of a new window.
   */
  minuteWindowResetsAt: number | undefined;
}

export interface BudgetState {
  budget: number;
  used: number;
  remaining: number;
  /** Next 00:00 UTC — when the local counter and the provider's daily quota both reset. */
  resetsAt: Date;
}

/**
 * What kind of wall a provider call hit.
 *
 * The distinction is the whole point of this type: `auth` and `plan` are configuration
 * problems only the operator can fix, `transport` and `upstream` are the provider having a
 * bad minute, and `rate-limited` fixes itself. A 502 that says none of them - which is what
 * this server used to answer - sends the operator to read logs on a host they do not want
 * to open.
 */
export type ProviderFaultKind =
  | 'transport'
  | 'auth'
  | 'plan'
  | 'rate-limited'
  | 'upstream'
  | 'malformed';

/** The last thing that went wrong, kept so /v1/health can stop claiming to be well. */
export interface ProviderFault {
  kind: ProviderFaultKind;
  /** The HTTP status, when the call got far enough to have one. */
  status: number | undefined;
  at: Date;
  /** The provider's own words. Operator-facing only - see `publicFaultReason`. */
  detail: string | undefined;
}

export interface ProviderHealth {
  /** False once a call has failed with nothing newer having succeeded. */
  reachable: boolean;
  lastSuccessAt: Date | undefined;
  lastFault: ProviderFault | undefined;
}

/**
 * One sentence per fault kind, written here rather than echoed from the provider.
 *
 * The provider names the account's state in its error body ("Your account is not
 * subscribed", "Token is invalid"), and that is the operator's business, not an anonymous
 * caller's. The kind alone is enough to act on.
 */
export function publicFaultReason(kind: ProviderFaultKind): string {
  switch (kind) {
    case 'auth':
      return 'The football data key was rejected. Check API_FOOTBALL_KEY.';
    case 'plan':
      return 'The football data subscription is not active for this key.';
    case 'rate-limited':
      return 'The football data provider is rate-limiting this key.';
    case 'transport':
      return 'The football data provider could not be reached.';
    case 'malformed':
      return 'The football data provider answered with something unreadable.';
    case 'upstream':
      return 'The football data provider returned an error.';
  }
}

/** A call reached the provider and came back unusable (transport, HTTP or envelope `errors`). */
export class ProviderError extends Error {
  readonly path: string;
  readonly status: number | undefined;
  readonly detail: string | undefined;
  readonly kind: ProviderFaultKind;

  constructor(
    message: string,
    options: {
      path: string;
      kind: ProviderFaultKind;
      status?: number | undefined;
      detail?: string | undefined;
      cause?: unknown;
    },
  ) {
    super(message, { cause: options.cause });
    this.name = 'ProviderError';
    this.path = options.path;
    this.kind = options.kind;
    this.status = options.status;
    this.detail = options.detail;
  }
}

/**
 * Reads API-Football's answer and says which wall it is.
 *
 * The provider reports auth and plan failures two different ways - as an HTTP 401/403 with
 * no envelope at all, and as an HTTP 200 whose envelope carries `errors.token` or
 * `errors.plan` - so both spellings have to be recognised or half the cases fall through
 * to a shrug.
 */
export function classifyProviderProblem(
  status: number | undefined,
  detail: string | undefined,
): ProviderFaultKind {
  if (status === 401 || status === 403) return 'auth';
  if (status === 429) return 'rate-limited';

  const text = (detail ?? '').toLowerCase();
  if (text.includes('token') || text.includes('api key') || text.includes('application key')) {
    return 'auth';
  }
  if (text.includes('subscri') || text.includes('plan') || text.includes('not allowed')) {
    return 'plan';
  }
  if (text.includes('rate limit') || text.includes('too many requests') || text.includes('requests')) {
    return 'rate-limited';
  }
  return 'upstream';
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
  /** The team's N most recent finished fixtures; needs `team` and needs no season. */
  last?: number;
  /** The team's next N fixtures. */
  next?: number;
}

/** Per-call cache override; without one a cacheable call uses CACHE_TTL_SECONDS. */
export interface RequestCacheOptions {
  cacheTtlSeconds?: number | undefined;
}

export interface TeamQuery {
  league?: number;
  season?: number;
  search?: string;
}

interface CacheEntry {
  /** Past this the value is refreshed on the next call... */
  expiresAt: number;
  /** ...but it stays servable until this, for when that refresh fails. */
  staleUntil: number;
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
    minuteWindowResetsAt: undefined,
  };
  #used = 0;
  #budgetDay: number;
  #lastSuccessAt: number | undefined;
  #lastFault: ProviderFault | undefined;
  #lowQuotaWarned = false;
  /** When the provider's current per-minute window is believed to have opened. */
  #minuteWindowStartedAt: number | undefined;

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

  /**
   * Whether the data path actually works, as opposed to whether the process is running.
   *
   * `reachable` is deliberately sticky: it flips false on a fault and only back on a later
   * success, so a server whose key was revoked keeps saying so instead of looking well
   * between requests.
   */
  getHealth(): ProviderHealth {
    return {
      reachable: this.#lastFault === undefined,
      lastSuccessAt: this.#lastSuccessAt === undefined ? undefined : new Date(this.#lastSuccessAt),
      lastFault: this.#lastFault === undefined ? undefined : { ...this.#lastFault },
    };
  }

  /**
   * API-Football's own account endpoint: the definitive answer to "is this key any good".
   *
   * Never cached and never counted against the local budget, because it is what gets called
   * when everything else is failing and the operator needs to know why. The provider does
   * not charge it against the plan either.
   */
  async accountStatus(): Promise<ApiAccountStatus | undefined> {
    const payload = await this.#fetchEnvelope('/status', {}, { spendBudget: false });
    // The envelope's `response` is an object here, not the list every other endpoint sends.
    if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) return undefined;
    return payload as ApiAccountStatus;
  }

  async leagues(current?: boolean): Promise<ApiLeagueCatalogueEntry[]> {
    return this.#request<ApiLeagueCatalogueEntry>(
      '/leagues',
      { current },
      true,
      CATALOGUE_TTL_SECONDS,
    );
  }

  async teams(query: TeamQuery = {}): Promise<ApiTeamCatalogueEntry[]> {
    return this.#request<ApiTeamCatalogueEntry>(
      '/teams',
      { league: query.league, season: query.season, search: query.search },
      true,
      CATALOGUE_TTL_SECONDS,
    );
  }

  async fixtures(
    query: FixtureQuery = {},
    options: RequestCacheOptions = {},
  ): Promise<ApiFixture[]> {
    // A calendar day, a range, or a team's last/next N is stable enough to cache; a single
    // fixture fetched by id is usually an in-play one the poller is diffing, so it never is.
    const cacheable =
      query.id === undefined &&
      (query.date !== undefined ||
        query.from !== undefined ||
        query.last !== undefined ||
        query.next !== undefined);
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
        last: query.last,
        next: query.next,
      },
      cacheable,
      options.cacheTtlSeconds,
    );
  }

  /**
   * The whole fixture with its inline `events`, `lineups`, `statistics` and `players` — one
   * request where the detail view used to spend four. Cached briefly (rather than not at
   * all, as the poller's own by-id fetch is) because several devices open the same match at
   * once and the sections it carries are what makes that call expensive to repeat.
   */
  async fixtureById(fixtureId: number, cacheTtlSeconds?: number): Promise<ApiFixture | undefined> {
    const [fixture] = await this.#request<ApiFixture>(
      '/fixtures',
      { id: fixtureId },
      true,
      cacheTtlSeconds,
    );
    return fixture;
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

  /** Per-player stats for one fixture. Also inline in `fixtureById`, which is cheaper. */
  async fixturePlayers(fixtureId: number, cacheTtlSeconds?: number): Promise<ApiFixturePlayers[]> {
    return this.#request<ApiFixturePlayers>(
      '/fixtures/players',
      { fixture: fixtureId },
      true,
      cacheTtlSeconds,
    );
  }

  /** The provider's own pre-match call. Nothing to fetch once the match has started. */
  async predictions(fixtureId: number, cacheTtlSeconds?: number): Promise<ApiPrediction[]> {
    return this.#request<ApiPrediction>(
      '/predictions',
      { fixture: fixtureId },
      true,
      cacheTtlSeconds ?? PREDICTION_TTL_SECONDS,
    );
  }

  /** A club's registered squad. Changes at transfer windows, so it caches for hours. */
  async squad(teamId: number, cacheTtlSeconds?: number): Promise<ApiSquadEntry[]> {
    return this.#request<ApiSquadEntry>(
      '/players/squads',
      { team: teamId },
      true,
      cacheTtlSeconds ?? SQUAD_TTL_SECONDS,
    );
  }

  /**
   * A player profile with that season's per-competition record. `/players` refuses an `id`
   * without a `season`, which is why the caller has to supply one.
   */
  async player(
    playerId: number,
    season: number,
    cacheTtlSeconds?: number,
  ): Promise<ApiPlayerEntry[]> {
    return this.#request<ApiPlayerEntry>(
      '/players',
      { id: playerId, season },
      true,
      cacheTtlSeconds,
    );
  }

  async #request<T>(
    path: string,
    params: QueryParams,
    cacheable: boolean,
    cacheTtlSeconds?: number | undefined,
  ): Promise<T[]> {
    const key = cacheKey(path, params);
    const hit = cacheable ? this.#cacheGet(key) : undefined;
    if (hit) return [...hit] as T[];

    // Everyone who arrives while one call is in flight rides on it. Without this the cache
    // saves nothing under load: a screenful of users hitting an expired day query at once
    // would each spend a request from the daily budget for the same list of fixtures.
    //
    // Deliberately BEFORE the cacheable test. A live scoreline must not be served from a
    // cache, but two devices asking for it in the same second still only need one call -
    // and the uncacheable paths are the hot ones, so skipping coalescing for them was
    // exactly backwards.
    const inflight = this.#inflight.get(key);
    if (inflight !== undefined) return [...(await inflight)] as T[];

    const ttlMs = !cacheable
      ? undefined
      : cacheTtlSeconds === undefined
        ? this.#cacheTtlMs
        : cacheTtlSeconds * 1000;
    const call = this.#fetchItems<T>(path, params, key, ttlMs);
    this.#inflight.set(key, call as Promise<unknown[]>);
    try {
      return await call;
    } catch (error) {
      // The refresh failed, but this call was answerable a moment ago. Serving the old
      // answer is the difference between a stale league list and an error screen - and for
      // an outage that lasts the afternoon, between an app that works and one that does
      // not. Only cacheable paths get here, so nothing live is served out of date.
      const stale = cacheable ? this.#cacheGetStale(key) : undefined;
      if (stale !== undefined) {
        this.#logger.warn('serving a stale answer; the refresh failed', {
          path,
          reason: error instanceof ProviderError ? error.kind : 'unknown',
        });
        return [...stale] as T[];
      }
      throw error;
    } finally {
      this.#inflight.delete(key);
    }
  }

  /** `cacheTtlMs === undefined` means "do not cache the result at all". */
  async #fetchItems<T>(
    path: string,
    params: QueryParams,
    key: string,
    cacheTtlMs: number | undefined,
    options: { spendBudget?: boolean } = {},
  ): Promise<T[]> {
    const payload = await this.#fetchEnvelope(path, params, options);
    const items = Array.isArray(payload) ? (payload as T[]) : [];
    // The cached array must not be the one handed to the caller: a consumer that sorts or
    // truncates its result in place would otherwise rewrite every hit until the TTL expires.
    //
    // An EMPTY list is cached briefly rather than for the full term. The provider answers
    // a plan restriction with HTTP 200 and `response: []`, which is indistinguishable here
    // from a competition that genuinely has no teams yet - and pinning that for twelve
    // hours means an upgraded plan, or a cup whose draw has just been made, stays empty
    // until tomorrow. A minute is long enough to absorb a burst, short enough to notice.
    if (cacheTtlMs !== undefined) {
      const ttl = items.length === 0 ? Math.min(cacheTtlMs, EMPTY_RESULT_TTL_MS) : cacheTtlMs;
      this.#cacheSet(key, [...items] as unknown[], ttl);
    }
    return items;
  }

  /**
   * One call, all the way to the envelope's `response` field, whatever shape that is.
   *
   * Split out from `#fetchItems` because `/status` answers with an object rather than a
   * list, and the list-shaped wrapper would quietly turn the only endpoint that can explain
   * an outage into an empty array.
   */
  async #fetchEnvelope(
    path: string,
    params: QueryParams,
    options: { spendBudget?: boolean } = {},
  ): Promise<unknown> {
    if (options.spendBudget !== false) this.#spendBudget(path);
    await this.#awaitMinuteHeadroom(path);

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
      throw this.#fail(
        new ProviderError(`Request to ${path} failed`, {
          path,
          kind: 'transport',
          detail: cause instanceof Error ? cause.message : undefined,
          cause,
        }),
      );
    }

    this.#readQuotaHeaders(response.headers);

    if (!response.ok) {
      const body = await safeText(response);
      throw this.#fail(
        new ProviderError(`${path} returned HTTP ${response.status}`, {
          path,
          kind: classifyProviderProblem(response.status, body),
          status: response.status,
          detail: body,
        }),
      );
    }

    let payload: unknown;
    try {
      payload = await response.json();
    } catch (cause) {
      throw this.#fail(
        new ProviderError(`${path} returned a body that is not JSON`, {
          path,
          kind: 'malformed',
          status: response.status,
          cause,
        }),
      );
    }

    // An edge proxy can answer 200 with `null` or a bare array. Reading `errors` off that
    // throws a TypeError, which sails past every `instanceof ProviderError` guard upstream
    // and turns a provider hiccup into a 500 instead of a degraded section.
    if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
      throw this.#fail(
        new ProviderError(`${path} returned a body that is not an API-Football envelope`, {
          path,
          kind: 'malformed',
          status: response.status,
          detail: JSON.stringify(payload ?? null).slice(0, 200),
        }),
      );
    }
    const envelope = payload as ApiEnvelope<unknown>;

    // The HTTP status is 200 here even for a dead key or a blown plan quota.
    const problem = describeProviderErrors(envelope.errors);
    if (problem !== null) {
      throw this.#fail(
        new ProviderError(`${path} reported "${problem}"`, {
          path,
          kind: classifyProviderProblem(response.status, problem),
          status: response.status,
          detail: problem,
        }),
      );
    }

    this.#lastSuccessAt = this.#now();
    this.#lastFault = undefined;
    return envelope.response;
  }

  /** Records a fault before it is thrown, so /v1/health can name it afterwards. */
  #fail(error: ProviderError): ProviderError {
    this.#lastFault = {
      kind: error.kind,
      status: error.status,
      at: new Date(this.#now()),
      detail: error.detail,
    };
    this.#logger.error('provider call failed', {
      path: error.path,
      kind: error.kind,
      status: error.status,
      detail: error.detail,
    });
    return error;
  }

  /**
   * Holds a call back when the provider's per-minute allowance is nearly spent.
   *
   * The daily budget and this are different problems: the day's is a wall, so `#spendBudget`
   * refuses outright and the poller waits for midnight. A minute passes on its own, so
   * waiting for it costs nothing but latency and saves a request that would come back 429 —
   * and a 429 is charged against the daily quota just the same.
   *
   * Capped at MAX_MINUTE_BACKOFF_MS. If the wait is not long enough the call goes out anyway
   * and the provider's own answer settles it, which beats blocking a request handler for a
   * full minute on an inferred window boundary.
   */
  async #awaitMinuteHeadroom(path: string): Promise<void> {
    const { minuteRemaining, minuteLimit } = this.#quota;
    if (minuteRemaining === undefined || minuteRemaining > MIN_MINUTE_HEADROOM) return;
    if (this.#minuteWindowStartedAt === undefined) return;

    const untilReset = this.#minuteWindowStartedAt + MINUTE_MS - this.#now();
    const waitMs = Math.min(untilReset, MAX_MINUTE_BACKOFF_MS);
    if (waitMs <= 0) return;

    this.#logger.warn('per-minute request allowance nearly spent; holding the call back', {
      path,
      minuteRemaining,
      minuteLimit,
      waitMs,
    });
    await delay(waitMs);
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
    // Two different windows, and the provider spells them differently: the lower-cased
    // `x-ratelimit-requests-*` pair is the DAILY plan allowance, the `X-RateLimit-*` pair is
    // the PER-MINUTE one. `Headers.get` is case-insensitive, so the names below are the
    // documented spellings only for readability.
    const minuteRemaining =
      numberHeader(headers, 'x-ratelimit-remaining') ?? this.#quota.minuteRemaining;
    const previousMinuteRemaining = this.#quota.minuteRemaining;

    // A remaining count that went UP is the only observable sign that the minute window
    // rolled over — there is no reset header — so that is when the window's clock restarts.
    if (
      minuteRemaining !== undefined &&
      (previousMinuteRemaining === undefined || minuteRemaining > previousMinuteRemaining)
    ) {
      this.#minuteWindowStartedAt = this.#now();
    }

    const quota: ProviderQuota = {
      dailyLimit: numberHeader(headers, 'x-ratelimit-requests-limit') ?? this.#quota.dailyLimit,
      dailyRemaining: numberHeader(headers, 'x-ratelimit-requests-remaining') ?? this.#quota.dailyRemaining,
      minuteLimit: numberHeader(headers, 'x-ratelimit-limit') ?? this.#quota.minuteLimit,
      minuteRemaining,
      minuteWindowResetsAt:
        this.#minuteWindowStartedAt === undefined
          ? undefined
          : this.#minuteWindowStartedAt + MINUTE_MS,
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

  /** A fresh hit only. An expired entry is left in place for `#cacheGetStale`. */
  #cacheGet(key: string): unknown[] | undefined {
    const entry = this.#cache.get(key);
    if (!entry) return undefined;
    if (entry.expiresAt <= this.#now()) return undefined;
    return entry.value;
  }

  /** An expired entry that has not yet aged out of its grace window. */
  #cacheGetStale(key: string): unknown[] | undefined {
    const entry = this.#cache.get(key);
    if (!entry) return undefined;
    if (entry.staleUntil <= this.#now()) {
      this.#cache.delete(key);
      return undefined;
    }
    return entry.value;
  }

  #cacheSet(key: string, value: unknown[], ttlMs: number): void {
    if (ttlMs <= 0) return;
    if (this.#cache.size >= MAX_CACHE_ENTRIES) this.#sweepCache();
    const now = this.#now();
    this.#cache.set(key, {
      expiresAt: now + ttlMs,
      // The grace is added ON TOP of the TTL, not max'd with it. Taking the larger of the
      // two collapsed the window to nothing for anything cached longer than the grace -
      // the catalogue at twelve hours and squads at six, which are exactly the calls that
      // must survive an outage. They had no stale fallback at all.
      staleUntil: now + ttlMs + STALE_GRACE_MS,
      value,
    });
  }

  #sweepCache(): void {
    const now = this.#now();
    // Only entries past their grace window go: an expired-but-servable one is exactly what
    // the next failed refresh will want.
    for (const [key, entry] of this.#cache) {
      if (entry.staleUntil <= now) this.#cache.delete(key);
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

/**
 * Deliberately NOT unref'd, unlike the poller's scheduling timers: a caller is awaiting this
 * one, and an unref'd timer in a process with nothing else pending would let Node exit with
 * that await never settling. MAX_MINUTE_BACKOFF_MS keeps the delay well inside the shutdown
 * grace in index.ts, so a back-off in flight cannot hold a deploy open either.
 */
function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
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
