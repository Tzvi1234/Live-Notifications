/**
 * Provider payloads -> the JSON the Android app is already written against.
 *
 * Everything here is pure: no clock beyond an injectable `date`, no network, no config.
 */

import {
  leagueLogoUrl,
  playerPhotoUrl,
  teamCrestUrl,
  type ApiCoverage,
  type ApiEvent,
  type ApiFixture,
  type ApiFixtureInfo,
  type ApiFixturePlayers,
  type ApiFixturePlayerStats,
  type ApiGoals,
  type ApiLeagueCatalogueEntry,
  type ApiLeagueRef,
  type ApiLineup,
  type ApiLineupPlayer,
  type ApiPlayerEntry,
  type ApiPrediction,
  type ApiSeasonEntry,
  type ApiSquadEntry,
  type ApiTeamCatalogueEntry,
  type ApiTeamRef,
  type ApiTeamStatistics,
} from './apiFootball.js';
import {
  eventId,
  phaseFromProviderCode,
  type FixturePlayerJson,
  type LeagueCoverageJson,
  type LeagueJson,
  type LineupPlayerJson,
  type MatchEventJson,
  type MatchEventType,
  type MatchJson,
  type MatchPlayersJson,
  type MatchPredictionJson,
  type MatchSide,
  type PlayerProfileJson,
  type PlayerSeasonStatsJson,
  type ScoreJson,
  type SquadPlayerJson,
  type TeamFixturePlayersJson,
  type TeamJson,
  type TeamLineupJson,
  type TeamSquadJson,
} from '../types.js';

/**
 * The wire contract has exactly one definition, in `../types.ts`, because the installed
 * Android app is compiled against it. Re-exported here so a caller can take the mapper and
 * the shapes it produces from one import.
 */
export type {
  FixturePlayerJson,
  LeagueCoverageJson,
  LeagueJson,
  LineupPlayerJson,
  MatchDetailJson,
  MatchEventJson,
  MatchEventType,
  MatchJson,
  MatchPhase,
  MatchPlayersJson,
  MatchPredictionJson,
  MatchSide,
  PlayerProfileJson,
  ScoreJson,
  SquadPlayerJson,
  TeamJson,
  TeamLineupJson,
  TeamSquadJson,
} from '../types.js';

/**
 * `toPhase` and `eventKey` are this module's names for the shared rules, aliased rather
 * than reimplemented. The poller mints synthetic KICK_OFF/HALF_TIME/FULL_TIME ids with
 * `eventId` and dedupes them against the ids `toEvents` produces below, so a second copy of
 * the key rule would push every incident the two copies disagreed on twice.
 */
export { eventId as eventKey, phaseFromProviderCode as toPhase } from '../types.js';

/**
 * European seasons are labelled by the year they start in, so anything before July
 * still belongs to the previous label. Evaluated in UTC to match the provider.
 */
export function currentSeason(date: Date = new Date()): number {
  const year = date.getUTCFullYear();
  return date.getUTCMonth() >= 6 ? year : year - 1;
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
    // One season entry, not two lookups: `coverage` describes the season it sits on, and
    // reporting the current season's year beside the latest season's coverage would tell the
    // client a competition has line-ups this year because it had them last year.
    const season = pickSeason(raw.seasons ?? []);
    return {
      id,
      name: raw.league?.name ?? `League ${id}`,
      country: raw.country?.name ?? undefined,
      logoUrl: raw.league?.logo ?? leagueLogoUrl(id),
      season: season?.year ?? fallbackSeason,
      type: raw.league?.type ?? undefined,
      coverage: toCoverage(season?.coverage),
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
    // The league block embedded in a fixture carries no coverage; only /leagues does.
    coverage: undefined,
  };
}

/** The season marked current, else the most recent one the provider listed. */
function pickSeason(seasons: readonly ApiSeasonEntry[]): ApiSeasonEntry | undefined {
  const current = seasons.find((season) => season.current === true);
  if (current) return current;
  return seasons.reduce<ApiSeasonEntry | undefined>((best, season) => {
    if (typeof season.year !== 'number') return best;
    if (best === undefined || season.year > (best.year ?? Number.NEGATIVE_INFINITY)) return season;
    return best;
  }, undefined);
}

/**
 * Absent stays absent. `{ lineups: false }` and "the provider said nothing" are different
 * answers — the first hides the line-up tab for good, the second only means this build of
 * the catalogue could not tell — so a missing block is undefined rather than all-false.
 */
export function toCoverage(raw: ApiCoverage | null | undefined): LeagueCoverageJson | undefined {
  if (!raw) return undefined;
  return {
    lineups: raw.fixtures?.lineups === true,
    events: raw.fixtures?.events === true,
    statisticsFixtures: raw.fixtures?.statistics_fixtures === true,
    statisticsPlayers: raw.fixtures?.statistics_players === true,
    standings: raw.standings === true,
    players: raw.players === true,
    injuries: raw.injuries === true,
    predictions: raw.predictions === true,
    odds: raw.odds === true,
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
    phase: phaseFromProviderCode(status?.short),
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
      id: eventId(matchId, type, event.time?.elapsed ?? undefined, teamId, player),
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

/** `/players/squads` -> the squad list. Entries without an id are dropped, not renumbered. */
export function toSquad(raw: ApiSquadEntry[]): TeamSquadJson | undefined {
  const entry = raw[0];
  if (!entry) return undefined;

  const players: SquadPlayerJson[] = [];
  for (const player of entry.players ?? []) {
    const id = player.id;
    if (typeof id !== 'number' || id <= 0) continue;
    players.push({
      id,
      name: player.name ?? '',
      number: player.number ?? undefined,
      position: player.position ?? undefined,
      age: player.age ?? undefined,
      photoUrl: player.photo ?? playerPhotoUrl(id),
    });
  }
  return { team: toTeam(entry.team), players };
}

/**
 * `/players?id=&season=` -> one profile with a line per competition that season. The
 * provider returns the same player once per team when they transferred mid-season, so the
 * profile is taken from the first entry and every entry's statistics are concatenated.
 */
export function toPlayerProfile(raw: ApiPlayerEntry[]): PlayerProfileJson | undefined {
  const first = raw[0]?.player;
  const id = first?.id;
  if (!first || typeof id !== 'number' || id <= 0) return undefined;

  const statistics: PlayerSeasonStatsJson[] = [];
  for (const entry of raw) {
    for (const line of entry.statistics ?? []) {
      statistics.push({
        leagueId: line.league?.id ?? undefined,
        leagueName: line.league?.name ?? undefined,
        teamId: line.team?.id ?? undefined,
        teamName: line.team?.name ?? undefined,
        season: line.league?.season ?? undefined,
        // The provider's own spelling of "appearances" is `appearences`; correcting it
        // here rather than in the client contract keeps the typo out of the app.
        appearances: line.games?.appearences ?? undefined,
        lineups: line.games?.lineups ?? undefined,
        minutes: line.games?.minutes ?? undefined,
        goals: line.goals?.total ?? undefined,
        assists: line.goals?.assists ?? undefined,
        yellowCards: line.cards?.yellow ?? undefined,
        redCards: line.cards?.red ?? undefined,
        rating: line.games?.rating ?? undefined,
      });
    }
  }

  return {
    id,
    name: first.name ?? '',
    firstName: first.firstname ?? undefined,
    lastName: first.lastname ?? undefined,
    age: first.age ?? undefined,
    birthDate: first.birth?.date ?? undefined,
    birthPlace: first.birth?.place ?? undefined,
    nationality: first.nationality ?? undefined,
    height: first.height ?? undefined,
    weight: first.weight ?? undefined,
    injured: first.injured ?? undefined,
    photoUrl: first.photo ?? playerPhotoUrl(id),
    statistics,
  };
}

/** Splits `/fixtures/players` into the two sides, the way `toLineups` splits the sheets. */
export function toMatchPlayers(
  matchId: number,
  homeTeamId: number,
  raw: ApiFixturePlayers[],
): MatchPlayersJson {
  const home = raw.find((entry) => entry.team?.id === homeTeamId);
  const away = raw.find(
    (entry) => typeof entry.team?.id === 'number' && entry.team.id !== homeTeamId,
  );
  return {
    matchId,
    home: home ? toTeamFixturePlayers(home) : undefined,
    away: away ? toTeamFixturePlayers(away) : undefined,
  };
}

function toTeamFixturePlayers(entry: ApiFixturePlayers): TeamFixturePlayersJson {
  const teamId = entry.team?.id ?? 0;
  const players: FixturePlayerJson[] = [];
  for (const slot of entry.players ?? []) {
    // The statistics array holds exactly one line per player in this endpoint; a second
    // would be a shape change, and taking the first is what the lineups mapper does too.
    players.push(toFixturePlayer(slot.player, slot.statistics?.[0]));
  }
  return {
    teamId,
    teamName: entry.team?.name ?? '',
    crestUrl: entry.team?.logo ?? (teamId > 0 ? teamCrestUrl(teamId) : undefined),
    players,
  };
}

function toFixturePlayer(
  player: ApiPlayerEntry['player'],
  stats: ApiFixturePlayerStats | undefined,
): FixturePlayerJson {
  const id = player?.id ?? undefined;
  return {
    id,
    name: player?.name ?? '',
    number: stats?.games?.number ?? undefined,
    position: stats?.games?.position ?? undefined,
    photoUrl: player?.photo ?? (typeof id === 'number' ? playerPhotoUrl(id) : undefined),
    minutes: stats?.games?.minutes ?? undefined,
    rating: stats?.games?.rating ?? undefined,
    captain: stats?.games?.captain ?? undefined,
    substitute: stats?.games?.substitute ?? undefined,
    goals: stats?.goals?.total ?? undefined,
    assists: stats?.goals?.assists ?? undefined,
    shotsTotal: stats?.shots?.total ?? undefined,
    shotsOn: stats?.shots?.on ?? undefined,
    passes: stats?.passes?.total ?? undefined,
    // "78%" on some plans, 78 on others; stringified rather than parsed, like match stats.
    passAccuracy: statValue(stats?.passes?.accuracy),
    tackles: stats?.tackles?.total ?? undefined,
    duelsWon: stats?.duels?.won ?? undefined,
    yellowCards: stats?.cards?.yellow ?? undefined,
    redCards: stats?.cards?.red ?? undefined,
  };
}

/** `/predictions?fixture=` -> the pre-match call, with its comparison table flattened. */
export function toMatchPrediction(
  matchId: number,
  raw: ApiPrediction[],
): MatchPredictionJson | undefined {
  const entry = raw[0];
  if (!entry) return undefined;

  const homeComparison: Record<string, string> = {};
  const awayComparison: Record<string, string> = {};
  for (const [metric, values] of Object.entries(entry.comparison ?? {})) {
    const home = statValue(values?.home);
    const away = statValue(values?.away);
    if (home !== undefined) homeComparison[metric] = home;
    if (away !== undefined) awayComparison[metric] = away;
  }

  return {
    matchId,
    // Absent when the provider predicts a draw, which is a real answer and not a gap.
    winnerTeamId: entry.predictions?.winner?.id ?? undefined,
    winnerName: entry.predictions?.winner?.name ?? undefined,
    winnerComment: entry.predictions?.winner?.comment ?? undefined,
    advice: entry.predictions?.advice ?? undefined,
    homePercent: entry.predictions?.percent?.home ?? undefined,
    drawPercent: entry.predictions?.percent?.draw ?? undefined,
    awayPercent: entry.predictions?.percent?.away ?? undefined,
    goalsHome: statValue(entry.predictions?.goals?.home),
    goalsAway: statValue(entry.predictions?.goals?.away),
    homeComparison,
    awayComparison,
  };
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
