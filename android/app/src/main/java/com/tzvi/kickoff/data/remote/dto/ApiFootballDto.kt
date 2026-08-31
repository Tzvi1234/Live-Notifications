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
data class PlayerRefDto(val id: Int? = null, val name: String? = null)

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
)
