/**
 * Wire and internal types for the matchUP backend.
 *
 * The `*Json` shapes are the Android client contract (mirrored in
 * `android/app/src/main/java/com/tzvi/kickoff/data/backend/BackendDto.kt`). The app is
 * already shipped against them: renaming or retyping a field here breaks installed
 * clients silently, because kotlinx.serialization drops unknown keys and defaults the
 * missing ones.
 */

/* -------------------------------------------------------------------------- */
/* Enumerations (kept as const arrays so the union type cannot drift from them) */
/* -------------------------------------------------------------------------- */

export const MATCH_PHASES = [
  'SCHEDULED',
  'OFF',
  'FIRST_HALF',
  'HALF_TIME',
  'SECOND_HALF',
  'EXTRA_TIME',
  'PENALTIES',
  'BREAK_TIME',
  'FINISHED',
  'UNKNOWN',
] as const;

export type MatchPhase = (typeof MATCH_PHASES)[number];

export const MATCH_EVENT_TYPES = [
  'GOAL',
  'OWN_GOAL',
  'PENALTY_GOAL',
  'PENALTY_MISSED',
  'YELLOW_CARD',
  'SECOND_YELLOW',
  'RED_CARD',
  'SUBSTITUTION',
  'VAR',
  'KICK_OFF',
  'HALF_TIME',
  'FULL_TIME',
  'OTHER',
] as const;

export type MatchEventType = (typeof MATCH_EVENT_TYPES)[number];

export const MATCH_SIDES = ['HOME', 'AWAY', 'NEUTRAL'] as const;

export type MatchSide = (typeof MATCH_SIDES)[number];

/**
 * API-Football `fixture.status.short` -> phase. Table, not a switch, so the mapping is
 * greppable from the provider docs; anything unlisted degrades to UNKNOWN rather than
 * throwing, since the provider adds codes without notice.
 */
const PHASE_BY_PROVIDER_CODE: Readonly<Record<string, MatchPhase>> = Object.freeze({
  TBD: 'SCHEDULED',
  NS: 'SCHEDULED',
  '1H': 'FIRST_HALF',
  HT: 'HALF_TIME',
  '2H': 'SECOND_HALF',
  ET: 'EXTRA_TIME',
  P: 'PENALTIES',
  BT: 'BREAK_TIME',
  SUSP: 'BREAK_TIME',
  INT: 'BREAK_TIME',
  FT: 'FINISHED',
  AET: 'FINISHED',
  PEN: 'FINISHED',
  PST: 'OFF',
  CANC: 'OFF',
  ABD: 'OFF',
  AWD: 'OFF',
  WO: 'OFF',
});

/** Takes null as well as undefined: the provider omits `status.short` on abandoned fixtures. */
export function phaseFromProviderCode(code?: string | null): MatchPhase {
  if (!code) return 'UNKNOWN';
  return PHASE_BY_PROVIDER_CODE[code.trim().toUpperCase()] ?? 'UNKNOWN';
}

/** Phases in which the fixture is on the pitch or between periods of one. */
const LIVE_PHASES: ReadonlySet<MatchPhase> = new Set<MatchPhase>([
  'FIRST_HALF',
  'HALF_TIME',
  'SECOND_HALF',
  'EXTRA_TIME',
  'PENALTIES',
  'BREAK_TIME',
]);

export function isLivePhase(phase: MatchPhase): boolean {
  return LIVE_PHASES.has(phase);
}

export function isTerminalPhase(phase: MatchPhase): boolean {
  return phase === 'FINISHED' || phase === 'OFF';
}

/* -------------------------------------------------------------------------- */
/* Client contract                                                             */
/* -------------------------------------------------------------------------- */

export interface TeamJson {
  id: number;
  name: string;
  shortName?: string | undefined;
  crestUrl?: string | undefined;
  country?: string | undefined;
  founded?: number | undefined;
  venue?: string | undefined;
}

/**
 * What the provider publishes for a competition's season, verbatim from `/leagues`.
 *
 * The client needs this to tell "this competition has no line-ups at all" from "the line-up
 * for this fixture is not out yet" — two states that look identical from an empty section
 * and which lead to opposite UI: a permanently hidden tab versus a "not published yet" note
 * that is worth refreshing. Absent when the provider did not report a coverage block.
 */
export interface LeagueCoverageJson {
  lineups: boolean;
  events: boolean;
  statisticsFixtures: boolean;
  statisticsPlayers: boolean;
  standings: boolean;
  players: boolean;
  injuries: boolean;
  predictions: boolean;
  odds: boolean;
}

export interface LeagueJson {
  id: number;
  name: string;
  country?: string | undefined;
  logoUrl?: string | undefined;
  season: number;
  type?: string | undefined;
  /** For `season`, never for a different one; see `toLeague`. */
  coverage?: LeagueCoverageJson | undefined;
}

export interface ScoreJson {
  home: number;
  away: number;
}

export interface MatchJson {
  id: number;
  leagueId: number;
  leagueName: string;
  leagueLogoUrl?: string | undefined;
  round?: string | undefined;
  /** SECONDS since epoch, UTC — the client reads it with Instant.ofEpochSecond. */
  kickoffAt: number;
  venue?: string | undefined;
  phase: MatchPhase;
  /** Provider clock, never derived from kickoffAt: stoppage, VAR and delays break arithmetic. */
  elapsed?: number | undefined;
  extra?: number | undefined;
  home: TeamJson;
  away: TeamJson;
  score?: ScoreJson | undefined;
  halfTimeScore?: ScoreJson | undefined;
  penaltyScore?: ScoreJson | undefined;
  referee?: string | undefined;
}

export interface MatchEventJson {
  /** Deterministic key — see `eventId()`. */
  id: string;
  type: MatchEventType;
  side: MatchSide;
  teamId?: number | undefined;
  teamName?: string | undefined;
  minute?: number | undefined;
  extra?: number | undefined;
  player?: string | undefined;
  assist?: string | undefined;
  detail?: string | undefined;
  comment?: string | undefined;
  /** Scoreline immediately after the event; drives the client's VAR-correction diffing. */
  scoreAfter?: ScoreJson | undefined;
}

export interface LineupPlayerJson {
  id?: number | undefined;
  name: string;
  number?: number | undefined;
  position?: string | undefined;
  /** Provider `grid` is "row:col"; split before it reaches the client. */
  row?: number | undefined;
  column?: number | undefined;
  photoUrl?: string | undefined;
}

export interface TeamLineupJson {
  teamId: number;
  teamName: string;
  crestUrl?: string | undefined;
  formation?: string | undefined;
  startingXi: LineupPlayerJson[];
  substitutes: LineupPlayerJson[];
  coach?: string | undefined;
  shirtColor?: string | undefined;
}

export interface MatchDetailJson {
  match: MatchJson;
  events: MatchEventJson[];
  homeLineup?: TeamLineupJson | undefined;
  awayLineup?: TeamLineupJson | undefined;
  /** Provider stat label -> display value, already stringified (e.g. "Ball Possession" -> "55%"). */
  homeStats: Record<string, string>;
  awayStats: Record<string, string>;
  /** Monotonic per match; the client drops any detail payload older than the one it holds. */
  sequence: number;
}

export interface MatchListJson {
  matches: MatchJson[];
}

export interface TeamListJson {
  teams: TeamJson[];
}

export interface LeagueListJson {
  leagues: LeagueJson[];
}

export interface HealthJson {
  ok: boolean;
  version: string;
  provider: string;
  pollingEnabled: boolean;
  /** Last rate-limit headers the provider returned; undefined fields mean "not yet told". */
  quota?: ProviderQuotaJson | undefined;
}

export interface ProviderQuotaJson {
  dailyLimit?: number | undefined;
  dailyRemaining?: number | undefined;
  minuteLimit?: number | undefined;
  minuteRemaining?: number | undefined;
}

/**
 * Everything the app needs before a user has signed in, so the publishable key is fetched
 * rather than baked into the APK: a Clerk instance can then be swapped without shipping a
 * release. Public by definition — the *secret* key never appears in any response.
 */
export interface ClientConfigJson {
  clerkPublishableKey?: string | undefined;
  features: ClientFeaturesJson;
}

export interface ClientFeaturesJson {
  /** False when the instance has no Clerk credentials; every authenticated route is 503. */
  auth: boolean;
  /** The prediction game needs both an account and a database that survives a restart. */
  predictionGame: boolean;
  push: boolean;
  polling: boolean;
}

/* -------------------------------------------------------------------------- */
/* Accounts and the prediction game                                            */
/* -------------------------------------------------------------------------- */

/**
 * Epoch SECONDS everywhere below, matching `MatchJson.kickoffAt` and the client's
 * `Instant.ofEpochSecond`. The internal records further down are millis, like `DeviceRecord`.
 */
export interface UserJson {
  userId: string;
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
  createdAt: number;
  lastSeenAt: number;
}

export interface GroupMemberJson {
  userId: string;
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
  joinedAt: number;
  isOwner: boolean;
}

export interface GroupJson {
  id: number;
  name: string;
  ownerId: string;
  /** Only ever sent to a member; it is the sole credential for joining. */
  inviteCode: string;
  leagueIds: number[];
  teamIds: number[];
  memberCount: number;
  isOwner: boolean;
  createdAt: number;
}

export interface GroupDetailJson extends GroupJson {
  members: GroupMemberJson[];
}

export interface GroupListJson {
  groups: GroupJson[];
}

export interface PredictionJson {
  userId: string;
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
  home: number;
  away: number;
  /** Null until the poller settles the fixture; 3, 1 or 0 once it has. */
  points?: number | undefined;
  exact?: boolean | undefined;
  /** Whether the predicted home-win / draw / away-win was right; true for an exact score too. */
  correctOutcome?: boolean | undefined;
  updatedAt: number;
}

export interface GroupFixtureJson {
  match: MatchJson;
  /** `kickoffAt <= now`: predictions are final and everyone's are visible. */
  locked: boolean;
  myPrediction?: PredictionJson | undefined;
  /** Empty until kick-off — the store's query never returns another member's row before it. */
  predictions: PredictionJson[];
}

export interface GroupFixtureListJson {
  fixtures: GroupFixtureJson[];
}

export interface LeaderboardEntryJson {
  userId: string;
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
  points: number;
  /** Predictions that named the score exactly. */
  exactCount: number;
  /** Predictions that got home-win / draw / away-win right, the exact ones included. */
  correctOutcomeCount: number;
  /** Predictions the poller has scored, i.e. the denominator for the two counts above. */
  settledCount: number;
  /** 1-based, dense: two members on equal points share a rank. */
  rank: number;
}

export interface LeaderboardJson {
  groupId: number;
  exactPoints: number;
  outcomePoints: number;
  entries: LeaderboardEntryJson[];
}

export interface ChatMessageJson {
  id: number;
  userId: string;
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
  text: string;
  createdAt: number;
}

export interface ChatJson {
  messages: ChatMessageJson[];
  /** The window's lower bound, so the client can label "nothing said today". */
  since: number;
}

/* -------------------------------------------------------------------------- */
/* Squads, players and pre-match predictions                                   */
/* -------------------------------------------------------------------------- */

export interface SquadPlayerJson {
  id: number;
  name: string;
  number?: number | undefined;
  position?: string | undefined;
  age?: number | undefined;
  photoUrl?: string | undefined;
}

export interface TeamSquadJson {
  team: TeamJson;
  players: SquadPlayerJson[];
}

export interface PlayerSeasonStatsJson {
  leagueId?: number | undefined;
  leagueName?: string | undefined;
  teamId?: number | undefined;
  teamName?: string | undefined;
  season?: number | undefined;
  appearances?: number | undefined;
  lineups?: number | undefined;
  minutes?: number | undefined;
  goals?: number | undefined;
  assists?: number | undefined;
  yellowCards?: number | undefined;
  redCards?: number | undefined;
  rating?: string | undefined;
}

export interface PlayerProfileJson {
  id: number;
  name: string;
  firstName?: string | undefined;
  lastName?: string | undefined;
  age?: number | undefined;
  birthDate?: string | undefined;
  birthPlace?: string | undefined;
  nationality?: string | undefined;
  height?: string | undefined;
  weight?: string | undefined;
  injured?: boolean | undefined;
  photoUrl?: string | undefined;
  statistics: PlayerSeasonStatsJson[];
}

export interface FixturePlayerJson {
  id?: number | undefined;
  name: string;
  number?: number | undefined;
  position?: string | undefined;
  photoUrl?: string | undefined;
  minutes?: number | undefined;
  rating?: string | undefined;
  captain?: boolean | undefined;
  substitute?: boolean | undefined;
  goals?: number | undefined;
  assists?: number | undefined;
  shotsTotal?: number | undefined;
  shotsOn?: number | undefined;
  passes?: number | undefined;
  passAccuracy?: string | undefined;
  tackles?: number | undefined;
  duelsWon?: number | undefined;
  yellowCards?: number | undefined;
  redCards?: number | undefined;
}

export interface TeamFixturePlayersJson {
  teamId: number;
  teamName: string;
  crestUrl?: string | undefined;
  players: FixturePlayerJson[];
}

export interface MatchPlayersJson {
  matchId: number;
  home?: TeamFixturePlayersJson | undefined;
  away?: TeamFixturePlayersJson | undefined;
}

/** Percentages arrive as "45%" strings and are passed through unparsed, like match stats. */
export interface MatchPredictionJson {
  matchId: number;
  /** Provider's own call: the winning team, or absent when it predicts a draw. */
  winnerTeamId?: number | undefined;
  winnerName?: string | undefined;
  winnerComment?: string | undefined;
  advice?: string | undefined;
  homePercent?: string | undefined;
  drawPercent?: string | undefined;
  awayPercent?: string | undefined;
  goalsHome?: string | undefined;
  goalsAway?: string | undefined;
  /** Provider stat label -> value, same flattening as MatchDetailJson's stats maps. */
  homeComparison: Record<string, string>;
  awayComparison: Record<string, string>;
}

export interface RegisterDeviceRequest {
  token: string;
  platform?: string | undefined;
  appVersion?: string | undefined;
  timeZone?: string | undefined;
  locale?: string | undefined;
}

export interface RegisterDeviceResponse {
  deviceId: string;
  ok: boolean;
}

export interface SubscriptionPreferences {
  goals: boolean;
  cards: boolean;
  substitutions: boolean;
  kickoffAndFullTime: boolean;
  lineups: boolean;
  preMatchLeadMinutes: number;
}

export interface SubscriptionRequest {
  token: string;
  teamIds: number[];
  leagueIds: number[];
  matchIds: number[];
  preferences: SubscriptionPreferences;
}

/** Must equal the client-side defaults in SubscriptionPreferencesJson. */
export const DEFAULT_SUBSCRIPTION_PREFERENCES: Readonly<SubscriptionPreferences> = Object.freeze({
  goals: true,
  cards: true,
  substitutions: false,
  kickoffAndFullTime: true,
  lineups: true,
  preMatchLeadMinutes: 60,
});

/**
 * Stable across refetches. The provider ships no event ids and re-reports incidents as
 * minutes are corrected, so both sides derive the same key from the identifying tuple;
 * a re-report then dedupes instead of firing a second notification. Byte-for-byte
 * identical to MatchEvent.key() on Android — change one, change both.
 */
export function eventId(
  matchId: number,
  type: MatchEventType,
  minute: number | undefined,
  teamId: number | undefined,
  playerName: string | undefined,
): string {
  return `${matchId}:${type}:${minute ?? -1}:${teamId ?? -1}:${playerName ?? ''}`;
}

/* -------------------------------------------------------------------------- */
/* Internal records                                                            */
/* -------------------------------------------------------------------------- */

export interface DeviceRecord {
  /** FCM registration token; the primary key everywhere, deviceId is only for the client. */
  token: string;
  deviceId: string;
  platform: string;
  appVersion?: string | undefined;
  timeZone?: string | undefined;
  locale?: string | undefined;
  /** Epoch millis. */
  createdAt: number;
  lastSeenAt: number;
}

export interface SubscriptionRecord {
  token: string;
  teamIds: number[];
  leagueIds: number[];
  matchIds: number[];
  preferences: SubscriptionPreferences;
}

/** Epoch millis on every record below, matching `DeviceRecord`; the DTOs convert to seconds. */
export interface UserRecord {
  clerkUserId: string;
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
  createdAt: number;
  lastSeenAt: number;
}

export interface GroupRecord {
  id: number;
  name: string;
  ownerId: string;
  inviteCode: string;
  leagueIds: number[];
  teamIds: number[];
  memberCount: number;
  createdAt: number;
}

export interface GroupMemberRecord {
  userId: string;
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
  joinedAt: number;
  isOwner: boolean;
}

export interface PredictionRecord {
  groupId: number;
  fixtureId: number;
  userId: string;
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
  home: number;
  away: number;
  /** Epoch millis; the snapshot both the write lock and the read filter are decided by. */
  kickoffAt: number;
  points?: number | undefined;
  exact?: boolean | undefined;
  correctOutcome?: boolean | undefined;
  updatedAt: number;
}

export interface LeaderboardRow {
  userId: string;
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
  points: number;
  exactCount: number;
  correctOutcomeCount: number;
  settledCount: number;
}

export interface GroupMessageRecord {
  id: number;
  groupId: number;
  userId: string;
  displayName?: string | undefined;
  avatarUrl?: string | undefined;
  text: string;
  createdAt: number;
}

/* -------------------------------------------------------------------------- */
/* Push                                                                        */
/* -------------------------------------------------------------------------- */

/**
 * DURABLE = something that happened and must survive a sleeping radio (goal, red card,
 * kick-off, half/full time, lineups). TICK = a score/clock refresh that is worthless the
 * moment a newer one exists.
 */
export type PushKind = 'DURABLE' | 'TICK';

/** Ten minutes: long enough to survive a doze window, short enough that a stale goal never lands. */
export const DURABLE_PUSH_TTL_MS = 600_000;

/**
 * ttl 0 = deliver now or drop. Also the only exemption from FCM's collapsible throttle
 * (20-message burst per app per device, refilling 1 per 3 minutes), which a 30s tick
 * would otherwise exhaust in ten minutes.
 */
export const TICK_PUSH_TTL_MS = 0;

/**
 * FCM keeps only 4 distinct collapse keys per device. One key per match ("m123_tick")
 * scoped to ticks leaves room for three concurrent matches plus a spare; a bare matchId
 * would let a durable event collapse a tick, or the reverse.
 */
export function tickCollapseKey(matchId: number): string {
  return `m${matchId}_tick`;
}

export interface PushEnvelope {
  kind: PushKind;
  /** Fan-out is per token: only tokens surface `registration-token-not-registered` for pruning. */
  tokens: string[];
  /** Set for TICK only; durable messages must stay non-collapsible. */
  collapseKey?: string | undefined;
  ttlMillis: number;
  title?: string | undefined;
  body?: string | undefined;
  /** FCM requires every data value to be a string, and the whole map to fit in 4096 bytes. */
  data: Record<string, string>;
  matchId: number;
  /** Present for durable event pushes; the dedupe key that stops a re-report re-notifying. */
  eventId?: string | undefined;
}

/* -------------------------------------------------------------------------- */
/* Poller state                                                                */
/* -------------------------------------------------------------------------- */

export interface TrackedMatchState {
  matchId: number;
  phase: MatchPhase;
  score?: ScoreJson | undefined;
  elapsed?: number | undefined;
  lastSequence: number;
  /** Dedupe invariant: an id in here has been pushed and must never be pushed again. */
  sentEventIds: Set<string>;
  lineupsSent: boolean;
}
