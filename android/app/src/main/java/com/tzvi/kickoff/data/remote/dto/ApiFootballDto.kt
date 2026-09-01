package com.tzvi.kickoff.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * API-Football wraps every response in this envelope.
 *
 * Two traps are handled here rather than at each call site:
 *  - authentication and quota failures come back as **HTTP 200** with the problem
 *    described inside [errors], so status codes alone never tell you a call failed;
 *  - [errors] is `[]` when empty but a `{"token": "..."}` *object* when not, so it
 *    cannot be typed as either a list or a map.
 */
@Serializable
data class ApiEnvelope<T>(
    val get: String? = null,
    val errors: JsonElement? = null,
    val results: Int = 0,
    val paging: Paging? = null,
    val response: List<T> = emptyList(),
) {
    @Serializable
    data class Paging(val current: Int = 1, val total: Int = 1)

    /** Human-readable problem text, or null when the call really did succeed. */
    val errorMessage: String?
        get() = when (val e = errors) {
            null -> null
            is JsonArray -> if (e.isEmpty()) null else e.joinToString { it.toString() }
            is JsonObject -> if (e.isEmpty()) null else e.entries.joinToString { (k, v) ->
                "$k: ${(v as? JsonPrimitive)?.content ?: v}"
            }
            is JsonPrimitive -> e.content.takeIf { it.isNotBlank() && it != "null" }
        }
}

@Serializable
data class FixtureResponse(
    val fixture: FixtureDto,
    val league: LeagueRefDto,
    val teams: TeamsDto,
    val goals: GoalsDto? = null,
    val score: ScoreBlockDto? = null,
)

@Serializable
data class FixtureDto(
    val id: Long,
    val referee: String? = null,
    val timezone: String? = null,
    val date: String? = null,
    /** Seconds since epoch - the only kick-off field that is unambiguous. */
    val timestamp: Long? = null,
    val periods: PeriodsDto? = null,
    val venue: VenueDto? = null,
    val status: StatusDto? = null,
)

@Serializable
data class PeriodsDto(val first: Long? = null, val second: Long? = null)

@Serializable
data class VenueDto(
    val id: Int? = null,
    val name: String? = null,
    val city: String? = null,
    val capacity: Int? = null,
    val surface: String? = null,
    val image: String? = null,
)

@Serializable
data class StatusDto(
    val long: String? = null,
    val short: String? = null,
    val elapsed: Int? = null,
    val extra: Int? = null,
)

@Serializable
data class LeagueRefDto(
    val id: Int,
    val name: String? = null,
    val country: String? = null,
    val logo: String? = null,
    val flag: String? = null,
    val season: Int? = null,
    val round: String? = null,
)

@Serializable
data class TeamsDto(val home: TeamRefDto? = null, val away: TeamRefDto? = null)

@Serializable
data class TeamRefDto(
    val id: Int = 0,
    val name: String? = null,
    val logo: String? = null,
    val winner: Boolean? = null,
    val colors: LineupColorsDto? = null,
)

@Serializable
data class GoalsDto(val home: Int? = null, val away: Int? = null)

@Serializable
data class ScoreBlockDto(
    val halftime: GoalsDto? = null,
    val fulltime: GoalsDto? = null,
    val extratime: GoalsDto? = null,
    val penalty: GoalsDto? = null,
)

// ---- events -----------------------------------------------------------------

@Serializable
data class EventResponse(
    val time: EventTimeDto? = null,
    val team: TeamRefDto? = null,
    val player: PlayerRefDto? = null,
    val assist: PlayerRefDto? = null,
    val type: String? = null,
    val detail: String? = null,
    val comments: String? = null,
)

@Serializable
data class EventTimeDto(val elapsed: Int? = null, val extra: Int? = null)

@Serializable
data class PlayerRefDto(
    val id: Int? = null,
    val name: String? = null,
    /** Only /fixtures/players sends this; event payloads carry no photo. */
    val photo: String? = null,
)

// ---- lineups ----------------------------------------------------------------

@Serializable
data class LineupResponse(
    val team: TeamRefDto? = null,
    val coach: CoachDto? = null,
    val formation: String? = null,
    @SerialName("startXI") val startXi: List<LineupSlotDto> = emptyList(),
    val substitutes: List<LineupSlotDto> = emptyList(),
)

@Serializable
data class CoachDto(val id: Int? = null, val name: String? = null, val photo: String? = null)

@Serializable
data class LineupSlotDto(val player: LineupPlayerDto? = null)

@Serializable
data class LineupPlayerDto(
    val id: Int? = null,
    val name: String? = null,
    val number: Int? = null,
    val pos: String? = null,
    /** "row:column", counted outwards from the goalkeeper. Null for the bench. */
    val grid: String? = null,
)

@Serializable
data class LineupColorsDto(
    val player: KitDto? = null,
    val goalkeeper: KitDto? = null,
)

@Serializable
data class KitDto(
    val primary: String? = null,
    val number: String? = null,
    val border: String? = null,
)

// ---- statistics -------------------------------------------------------------

@Serializable
data class StatisticsResponse(
    val team: TeamRefDto? = null,
    val statistics: List<StatisticDto> = emptyList(),
)

/** [value] is variously an int, a "55%" string, or null - hence [JsonElement]. */
@Serializable
data class StatisticDto(val type: String? = null, val value: JsonElement? = null) {
    val displayValue: String?
        get() = (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}

// ---- catalogue --------------------------------------------------------------

@Serializable
data class TeamCatalogueResponse(val team: TeamInfoDto? = null, val venue: VenueDto? = null)

@Serializable
data class TeamInfoDto(
    val id: Int = 0,
    val name: String? = null,
    val code: String? = null,
    val country: String? = null,
    val founded: Int? = null,
    val national: Boolean? = null,
    val logo: String? = null,
)

@Serializable
data class LeagueCatalogueResponse(
    val league: LeagueInfoDto? = null,
    val country: CountryDto? = null,
    val seasons: List<SeasonDto> = emptyList(),
)

@Serializable
data class LeagueInfoDto(
    val id: Int = 0,
    val name: String? = null,
    val type: String? = null,
    val logo: String? = null,
)

@Serializable
data class CountryDto(val name: String? = null, val code: String? = null, val flag: String? = null)

@Serializable
data class SeasonDto(
    val year: Int = 0,
    val start: String? = null,
    val end: String? = null,
    val current: Boolean = false,
    val coverage: CoverageDto? = null,
)

/**
 * Per-season coverage, which the provider nests under the season rather than the league -
 * the same competition can carry line-ups one year and not the next.
 */
@Serializable
data class CoverageDto(
    val fixtures: FixtureCoverageDto? = null,
    val standings: Boolean = false,
    val players: Boolean = false,
    val injuries: Boolean = false,
    val predictions: Boolean = false,
    val odds: Boolean = false,
)

@Serializable
data class FixtureCoverageDto(
    val events: Boolean = false,
    val lineups: Boolean = false,
    @SerialName("statistics_fixtures") val statisticsFixtures: Boolean = false,
    @SerialName("statistics_players") val statisticsPlayers: Boolean = false,
)

// ---- players -------------------------------------------------------------------------

@Serializable
data class FixturePlayersResponse(
    val team: TeamRefDto? = null,
    val players: List<FixturePlayerDto> = emptyList(),
)

@Serializable
data class FixturePlayerDto(
    val player: PlayerRefDto? = null,
    /** Exactly one element for a single fixture, but the provider still sends a list. */
    val statistics: List<PlayerFixtureStatsDto> = emptyList(),
)

@Serializable
data class PlayerFixtureStatsDto(
    val games: PlayerGamesDto? = null,
    val offsides: Int? = null,
    val shots: PlayerShotsDto? = null,
    val goals: PlayerGoalsDto? = null,
    val passes: PlayerPassesDto? = null,
    val tackles: PlayerTacklesDto? = null,
    val duels: PlayerDuelsDto? = null,
    val dribbles: PlayerDribblesDto? = null,
    val fouls: PlayerFoulsDto? = null,
    val cards: PlayerCardsDto? = null,
    val penalty: PlayerPenaltyDto? = null,
)

@Serializable
data class PlayerGamesDto(
    val minutes: Int? = null,
    val number: Int? = null,
    val position: String? = null,
    /** A string upstream ("6.3"), so it is kept as one rather than parsed and re-rendered. */
    val rating: String? = null,
    val captain: Boolean? = null,
    val substitute: Boolean? = null,
)

@Serializable
data class PlayerShotsDto(val total: Int? = null, val on: Int? = null)

@Serializable
data class PlayerGoalsDto(
    val total: Int? = null,
    val conceded: Int? = null,
    val assists: Int? = null,
    val saves: Int? = null,
)

/**
 * `accuracy` is "68%" on this endpoint but a bare integer on the season-stats one, so it
 * is typed as a string here and never shared with that parser.
 */
@Serializable
data class PlayerPassesDto(
    val total: Int? = null,
    val key: Int? = null,
    val accuracy: String? = null,
)

@Serializable
data class PlayerTacklesDto(
    val total: Int? = null,
    val blocks: Int? = null,
    val interceptions: Int? = null,
)

@Serializable
data class PlayerDuelsDto(val total: Int? = null, val won: Int? = null)

@Serializable
data class PlayerDribblesDto(
    val attempts: Int? = null,
    val success: Int? = null,
    /** How often the player was dribbled past, not how often they dribbled. */
    val past: Int? = null,
)

@Serializable
data class PlayerFoulsDto(val drawn: Int? = null, val committed: Int? = null)

/** No `yellowred` key on this endpoint, unlike the season-statistics one. */
@Serializable
data class PlayerCardsDto(val yellow: Int? = null, val red: Int? = null)

/** The provider's own misspelling of "committed" is load-bearing - do not correct it. */
@Serializable
data class PlayerPenaltyDto(
    val won: Int? = null,
    val commited: Int? = null,
    val scored: Int? = null,
    val missed: Int? = null,
    val saved: Int? = null,
)

@Serializable
data class PlayerProfileResponse(val player: PlayerProfileDto? = null)

@Serializable
data class PlayerProfileDto(
    val id: Int? = null,
    val name: String? = null,
    val firstname: String? = null,
    val lastname: String? = null,
    val age: Int? = null,
    val birth: PlayerBirthDto? = null,
    val nationality: String? = null,
    val height: String? = null,
    val weight: String? = null,
    val number: Int? = null,
    val position: String? = null,
    val photo: String? = null,
)

@Serializable
data class PlayerBirthDto(
    val date: String? = null,
    val place: String? = null,
    val country: String? = null,
)

@Serializable
data class SquadResponse(
    val team: TeamRefDto? = null,
    /** Flat objects here, unlike lineups and fixture players - the provider's own shape. */
    val players: List<SquadPlayerDto> = emptyList(),
)

@Serializable
data class SquadPlayerDto(
    val id: Int? = null,
    val name: String? = null,
    val age: Int? = null,
    val number: Int? = null,
    /** Long form on this endpoint: "Goalkeeper", "Defender", "Midfielder", "Attacker". */
    val position: String? = null,
    val photo: String? = null,
)

// ---- predictions -----------------------------------------------------------------------

@Serializable
data class PredictionResponse(
    val predictions: PredictionBlockDto? = null,
    val teams: PredictionTeamsDto? = null,
)

@Serializable
data class PredictionBlockDto(
    val winner: PredictionWinnerDto? = null,
    @SerialName("win_or_draw") val winOrDraw: Boolean? = null,
    @SerialName("under_over") val underOver: String? = null,
    val advice: String? = null,
    /** Strings with a per-cent sign on them - "45%" - not numbers. */
    val percent: PredictionPercentDto? = null,
)

@Serializable
data class PredictionWinnerDto(
    val id: Int? = null,
    val name: String? = null,
    val comment: String? = null,
)

@Serializable
data class PredictionPercentDto(
    val home: String? = null,
    val draw: String? = null,
    val away: String? = null,
)

@Serializable
data class PredictionTeamsDto(
    val home: PredictionTeamDto? = null,
    val away: PredictionTeamDto? = null,
)

@Serializable
data class PredictionTeamDto(
    val league: PredictionLeagueFormDto? = null,
    @SerialName("last_5") val lastFive: PredictionLastFiveDto? = null,
)

@Serializable
data class PredictionLeagueFormDto(
    val form: String? = null,
    @SerialName("clean_sheet") val cleanSheet: PredictionTotalsDto? = null,
    val goals: PredictionGoalsDto? = null,
)

@Serializable
data class PredictionLastFiveDto(
    val form: String? = null,
    val att: String? = null,
    @SerialName("def") val defence: String? = null,
)

@Serializable
data class PredictionTotalsDto(
    val home: Int? = null,
    val away: Int? = null,
    val total: Int? = null,
)

@Serializable
data class PredictionGoalsDto(
    @SerialName("for") val scored: PredictionGoalSideDto? = null,
    val against: PredictionGoalSideDto? = null,
)

@Serializable
data class PredictionGoalSideDto(val average: PredictionAverageDto? = null)

@Serializable
data class PredictionAverageDto(val total: String? = null)
