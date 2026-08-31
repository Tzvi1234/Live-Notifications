/**
 * Provider payloads -> the JSON the Android app is already written against.
 *
 * Everything here is pure: no clock beyond an injectable `date`, no network, no config.
 * The wire types live here too so the mapper and its consumers cannot drift apart.
 */

import {
  leagueLogoUrl,
  playerPhotoUrl,
  teamCrestUrl,
  type ApiEvent,
  type ApiFixture,
  type ApiFixtureInfo,
  type ApiGoals,
  type ApiLeagueCatalogueEntry,
  type ApiLeagueRef,
  type ApiLineup,
  type ApiLineupPlayer,
  type ApiTeamCatalogueEntry,
  type ApiTeamRef,
  type ApiTeamStatistics,
} from './apiFootball.js';

export type MatchPhase =
  | 'SCHEDULED'
  | 'OFF'
  | 'FIRST_HALF'
  | 'HALF_TIME'
  | 'SECOND_HALF'
  | 'EXTRA_TIME'
  | 'PENALTIES'
  | 'BREAK_TIME'
  | 'FINISHED'
  | 'UNKNOWN';

export type MatchEventType =
  | 'GOAL'
  | 'OWN_GOAL'
  | 'PENALTY_GOAL'
  | 'PENALTY_MISSED'
  | 'YELLOW_CARD'
  | 'SECOND_YELLOW'
  | 'RED_CARD'
  | 'SUBSTITUTION'
  | 'VAR'
  | 'KICK_OFF'
  | 'HALF_TIME'
  | 'FULL_TIME'
  | 'OTHER';

export type MatchSide = 'HOME' | 'AWAY' | 'NEUTRAL';

export interface TeamJson {
  id: number;
  name: string;
  shortName?: string | undefined;
  crestUrl?: string | undefined;
  country?: string | undefined;
  founded?: number | undefined;
  venue?: string | undefined;
}

export interface LeagueJson {
  id: number;
  name: string;
  country?: string | undefined;
  logoUrl?: string | undefined;
  season: number;
  type?: string | undefined;
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
  /** Seconds since epoch, UTC — the client feeds this straight to Instant.ofEpochSecond. */
  kickoffAt: number;
  venue?: string | undefined;
  phase: MatchPhase;
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
  scoreAfter?: ScoreJson | undefined;
}

export interface LineupPlayerJson {
  id?: number | undefined;
  name: string;
  number?: number | undefined;
  position?: string | undefined;
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
  homeStats: Record<string, string>;
  awayStats: Record<string, string>;
  sequence: number;
}

/**
 * European seasons are labelled by the year they start in, so anything before July
 * still belongs to the previous label. Evaluated in UTC to match the provider.
 */
export function currentSeason(date: Date = new Date()): number {
  const year = date.getUTCFullYear();
  return date.getUTCMonth() >= 6 ? year : year - 1;
}

/** API-Football `fixture.status.short` -> the phase names the client's enum uses. */
export function toPhase(short?: string | null): MatchPhase {
  switch (short) {
    case 'TBD':
    case 'NS':
      return 'SCHEDULED';
    case '1H':
      return 'FIRST_HALF';
    case 'HT':
      return 'HALF_TIME';
    case '2H':
      return 'SECOND_HALF';
    case 'ET':
      return 'EXTRA_TIME';
    case 'P':
      return 'PENALTIES';
    case 'BT':
    case 'SUSP':
    case 'INT':
      return 'BREAK_TIME';
    case 'FT':
    case 'AET':
    case 'PEN':
      return 'FINISHED';
    case 'PST':
    case 'CANC':
    case 'ABD':
    case 'AWD':
    case 'WO':
      return 'OFF';
    default:
      return 'UNKNOWN';
  }
}

/**
 * Provider events carry no id and get re-reported as minutes are corrected and VAR
 * overturns things. The key is the tuple that identifies the incident; the Android client
 * derives the identical string, so a re-report dedupes on both sides instead of alerting twice.
 */
export function eventKey(
  matchId: number,
  type: MatchEventType,
  minute: number | undefined,
  teamId: number | undefined,
  playerName: string | undefined,
): string {
  return `${matchId}:${type}:${minute ?? -1}:${teamId ?? -1}:${playerName ?? ''}`;
}

export function toTeam(raw: ApiTeamRef | ApiTeamCatalogueEntry | null | undefined): TeamJson {
  if (!raw) return { id: 0, name: 'Unknown', shortName: '?' };

  if (isTeamCatalogueEntry(raw)) {
    const id = raw.team?.id ?? 0;
    const name = raw.team?.name ?? 'Unknown';
    return {
      id,
      name,
      shortName: raw.team?.code ?? abbreviate(name),
      crestUrl: raw.team?.logo ?? (id > 0 ? teamCrestUrl(id) : undefined),
      country: raw.team?.country ?? undefined,
      founded: raw.team?.founded ?? undefined,
      venue: raw.venue?.name ?? undefined,
    };
  }

  const id = raw.id ?? 0;
  const name = raw.name ?? 'Unknown';
  return {
    id,
    name,
    shortName: abbreviate(name),
    crestUrl: raw.logo ?? (id > 0 ? teamCrestUrl(id) : undefined),
  };
}

/**
 * Accepts either a `/leagues` catalogue entry or the league block embedded in a fixture.
 * Returns undefined for an entry without an id rather than inventing league 0.
 */
export function toLeague(
  raw: ApiLeagueCatalogueEntry | ApiLeagueRef | null | undefined,
  fallbackSeason: number = currentSeason(),
): LeagueJson | undefined {
  if (!raw) return undefined;

  if (isLeagueCatalogueEntry(raw)) {
    const id = raw.league?.id;
    if (typeof id !== 'number') return undefined;
    const seasons = raw.seasons ?? [];
    const current = seasons.find((season) => season.current === true)?.year;
    const latest = seasons.reduce<number | undefined>(
      (best, season) =>
        typeof season.year === 'number' && (best === undefined || season.year > best) ? season.year : best,
      undefined,
    );
    return {
      id,
      name: raw.league?.name ?? `League ${id}`,
      country: raw.country?.name ?? undefined,
      logoUrl: raw.league?.logo ?? leagueLogoUrl(id),
      season: current ?? latest ?? fallbackSeason,
      type: raw.league?.type ?? undefined,
    };
  }

  const id = raw.id;
  if (typeof id !== 'number') return undefined;
  return {
    id,
    name: raw.name ?? `League ${id}`,
    country: raw.country ?? undefined,
    logoUrl: raw.logo ?? leagueLogoUrl(id),
    season: raw.season ?? fallbackSeason,
    type: undefined,
  };
}

export function toLeagues(
  raw: Array<ApiLeagueCatalogueEntry | ApiLeagueRef>,
  fallbackSeason: number = currentSeason(),
): LeagueJson[] {
  const mapped: LeagueJson[] = [];
  for (const entry of raw) {
    const league = toLeague(entry, fallbackSeason);
    if (league) mapped.push(league);
  }
  return mapped;
}

export function toMatch(raw: ApiFixture): MatchJson {
  const status = raw.fixture?.status;
  const leagueId = raw.league?.id ?? 0;
  return {
    id: raw.fixture?.id ?? 0,
    leagueId,
    leagueName: raw.league?.name ?? '',
    leagueLogoUrl: raw.league?.logo ?? (leagueId > 0 ? leagueLogoUrl(leagueId) : undefined),
    round: raw.league?.round ?? undefined,
    kickoffAt: kickoffSeconds(raw.fixture),
    venue: raw.fixture?.venue?.name ?? undefined,
    phase: toPhase(status?.short),
    elapsed: status?.elapsed ?? undefined,
    extra: status?.extra ?? undefined,
    home: toTeam(raw.teams?.home),
    away: toTeam(raw.teams?.away),
    score: toScore(raw.goals),
    halfTimeScore: toScore(raw.score?.halftime),
    penaltyScore: toScore(raw.score?.penalty),
    referee: raw.fixture?.referee ?? undefined,
  };
}

/**
 * Re-keys and re-scores a match's event feed.
 *
 * The provider returns events in chronological order and never restates the scoreline, so
 * the running total below is the only source for `scoreAfter` — an own goal counts for the
 * team that did *not* score it. Ids are deterministic (see [eventKey]) so a refetch of the
 * same match produces byte-identical ids and the poller's diff stays quiet.
 */
export function toEvents(matchId: number, homeTeamId: number, raw: ApiEvent[]): MatchEventJson[] {
  let home = 0;
  let away = 0;

  return raw.map((event) => {
    const type = classifyEvent(event.type, event.detail);
    const teamId = event.team?.id ?? undefined;
    const side: MatchSide = teamId === undefined ? 'NEUTRAL' : teamId === homeTeamId ? 'HOME' : 'AWAY';
    const player = event.player?.name ?? undefined;

    if (isGoal(type)) {
      const creditHome = type === 'OWN_GOAL' ? side !== 'HOME' : side === 'HOME';
      if (creditHome) home += 1;
      else away += 1;
    }

    return {
      id: eventKey(matchId, type, event.time?.elapsed ?? undefined, teamId, player),
      type,
      side,
      teamId,
      teamName: event.team?.name ?? undefined,
      minute: event.time?.elapsed ?? undefined,
      extra: event.time?.extra ?? undefined,
      // On a substitution `player` is the one coming ON and `assist` the one going OFF.
      player,
      assist: event.assist?.name ?? undefined,
      detail: event.detail ?? undefined,
      comment: event.comments ?? undefined,
      scoreAfter: { home, away },
    };
  });
}

export function toLineup(raw: ApiLineup): TeamLineupJson {
  const teamId = raw.team?.id ?? 0;
  return {
    teamId,
    teamName: raw.team?.name ?? '',
    crestUrl: raw.team?.logo ?? (teamId > 0 ? teamCrestUrl(teamId) : undefined),
    formation: raw.formation ?? undefined,
    startingXi: (raw.startXI ?? []).flatMap((slot) => (slot.player ? [toLineupPlayer(slot.player)] : [])),
    substitutes: (raw.substitutes ?? []).flatMap((slot) => (slot.player ? [toLineupPlayer(slot.player)] : [])),
    coach: raw.coach?.name ?? undefined,
    shirtColor: toHexColor(raw.team?.colors?.player?.primary),
  };
}

/** Splits a `/fixtures/lineups` response into the two slots of MatchDetailJson. */
export function toLineups(
  homeTeamId: number,
  raw: ApiLineup[],
): { homeLineup?: TeamLineupJson | undefined; awayLineup?: TeamLineupJson | undefined } {
  const home = raw.find((lineup) => lineup.team?.id === homeTeamId);
  const away = raw.find((lineup) => typeof lineup.team?.id === 'number' && lineup.team.id !== homeTeamId);
  return {
    homeLineup: home ? toLineup(home) : undefined,
    awayLineup: away ? toLineup(away) : undefined,
  };
}

/** Flattens `/fixtures/statistics` into the two string maps the client renders. */
export function toStats(
  homeTeamId: number,
  raw: ApiTeamStatistics[],
): { homeStats: Record<string, string>; awayStats: Record<string, string> } {
  const home = raw.find((entry) => entry.team?.id === homeTeamId);
  const away = raw.find((entry) => typeof entry.team?.id === 'number' && entry.team.id !== homeTeamId);
  return { homeStats: flattenStats(home), awayStats: flattenStats(away) };
}

function flattenStats(entry: ApiTeamStatistics | undefined): Record<string, string> {
  const flat: Record<string, string> = {};
  for (const stat of entry?.statistics ?? []) {
    const label = stat.type?.trim();
    const value = statValue(stat.value);
    if (label && value !== undefined) flat[label] = value;
  }
  return flat;
}

function statValue(value: string | number | null | undefined): string | undefined {
  if (value === null || value === undefined) return undefined;
  const text = String(value).trim();
  return text.length === 0 || text === 'null' ? undefined : text;
}

function toLineupPlayer(player: ApiLineupPlayer): LineupPlayerJson {
  const grid = player.grid?.split(':');
  return {
    id: player.id ?? undefined,
    name: player.name ?? '',
    number: player.number ?? undefined,
    position: player.pos ?? undefined,
    row: toInt(grid?.[0]),
    column: toInt(grid?.[1]),
    photoUrl: typeof player.id === 'number' ? playerPhotoUrl(player.id) : undefined,
  };
}

function classifyEvent(type: string | null | undefined, detail: string | null | undefined): MatchEventType {
  const d = (detail ?? '').toLowerCase();
  switch ((type ?? '').toLowerCase()) {
    case 'goal':
      if (d.includes('own goal')) return 'OWN_GOAL';
      if (d.includes('missed penalty')) return 'PENALTY_MISSED';
      if (d.includes('penalty')) return 'PENALTY_GOAL';
      return 'GOAL';
    case 'card':
      if (d.includes('second yellow')) return 'SECOND_YELLOW';
      if (d.includes('red')) return 'RED_CARD';
      return 'YELLOW_CARD';
    case 'subst':
      return 'SUBSTITUTION';
    case 'var':
      return 'VAR';
    default:
      return 'OTHER';
  }
}

function isGoal(type: MatchEventType): boolean {
  return type === 'GOAL' || type === 'OWN_GOAL' || type === 'PENALTY_GOAL';
}

function toScore(goals: ApiGoals | null | undefined): ScoreJson | undefined {
  if (!goals) return undefined;
  const { home, away } = goals;
  if (home === null || home === undefined) {
    if (away === null || away === undefined) return undefined;
  }
  return { home: home ?? 0, away: away ?? 0 };
}

function kickoffSeconds(fixture: ApiFixtureInfo | null | undefined): number {
  if (typeof fixture?.timestamp === 'number') return fixture.timestamp;
  const parsed = fixture?.date ? Date.parse(fixture.date) : Number.NaN;
  return Number.isNaN(parsed) ? 0 : Math.floor(parsed / 1000);
}

function toInt(value: string | undefined): number | undefined {
  if (value === undefined) return undefined;
  const parsed = Number.parseInt(value, 10);
  return Number.isNaN(parsed) ? undefined : parsed;
}

function toHexColor(value: string | null | undefined): string | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (trimmed.length === 0) return undefined;
  return trimmed.startsWith('#') ? trimmed : `#${trimmed}`;
}

/** "Manchester United" -> "MU", "Ajax" -> "AJA". Mirrors the client's own abbreviation. */
function abbreviate(name: string | null | undefined): string {
  if (!name || name.trim().length === 0) return '?';
  const words = name.split(/[\s-]+/).filter((word) => word.length > 0);
  if (words.length >= 2) {
    return words
      .slice(0, 3)
      .map((word) => word.charAt(0).toUpperCase())
      .join('');
  }
  return name.slice(0, 3).toUpperCase();
}

function isTeamCatalogueEntry(raw: ApiTeamRef | ApiTeamCatalogueEntry): raw is ApiTeamCatalogueEntry {
  return 'team' in raw || 'venue' in raw;
}

function isLeagueCatalogueEntry(raw: ApiLeagueCatalogueEntry | ApiLeagueRef): raw is ApiLeagueCatalogueEntry {
  return 'league' in raw || 'seasons' in raw;
}
