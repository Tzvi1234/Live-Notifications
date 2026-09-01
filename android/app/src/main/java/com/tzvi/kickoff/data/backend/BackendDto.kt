package com.tzvi.kickoff.data.backend

import kotlinx.serialization.Serializable

/**
 * Wire shapes for the Kickoff backend (see `server/`).
 *
 * The server does all the provider mapping, so these mirror the domain model almost
 * one-to-one and stay stable even if the underlying football provider is swapped out.
 */
@Serializable
data class TeamJson(
    val id: Int,
    val name: String,
    val shortName: String? = null,
    val crestUrl: String? = null,
    val country: String? = null,
    val founded: Int? = null,
    val venue: String? = null,
)

@Serializable
data class LeagueJson(
    val id: Int,
    val name: String,
    val country: String? = null,
    val logoUrl: String? = null,
    val season: Int,
    val type: String? = null,
)

@Serializable
data class ScoreJson(val home: Int, val away: Int)

@Serializable
data class MatchJson(
    val id: Long,
    val leagueId: Int,
    val leagueName: String,
    val leagueLogoUrl: String? = null,
    val round: String? = null,
    /** Seconds since epoch, UTC. */
    val kickoffAt: Long,
    val venue: String? = null,
    /** Matches the names of [com.tzvi.kickoff.core.model.MatchPhase]. */
    val phase: String,
    val elapsed: Int? = null,
    val extra: Int? = null,
    val home: TeamJson,
    val away: TeamJson,
    val score: ScoreJson? = null,
    val halfTimeScore: ScoreJson? = null,
    val penaltyScore: ScoreJson? = null,
    val referee: String? = null,
)

@Serializable
data class MatchEventJson(
    val id: String,
    val type: String,
    val side: String,
    val teamId: Int? = null,
    val teamName: String? = null,
    val minute: Int? = null,
    val extra: Int? = null,
    val player: String? = null,
    val assist: String? = null,
    val detail: String? = null,
    val comment: String? = null,
    val scoreAfter: ScoreJson? = null,
)

@Serializable
data class LineupPlayerJson(
    val id: Int? = null,
    val name: String,
    val number: Int? = null,
    val position: String? = null,
    val row: Int? = null,
    val column: Int? = null,
    val photoUrl: String? = null,
)

@Serializable
data class TeamLineupJson(
    val teamId: Int,
    val teamName: String,
    val crestUrl: String? = null,
    val formation: String? = null,
    val startingXi: List<LineupPlayerJson> = emptyList(),
    val substitutes: List<LineupPlayerJson> = emptyList(),
    val coach: String? = null,
    val shirtColor: String? = null,
)

@Serializable
data class MatchDetailJson(
    val match: MatchJson,
    val events: List<MatchEventJson> = emptyList(),
    val homeLineup: TeamLineupJson? = null,
    val awayLineup: TeamLineupJson? = null,
    val homeStats: Map<String, String> = emptyMap(),
    val awayStats: Map<String, String> = emptyMap(),
    val sequence: Long = 0,
)

@Serializable
data class MatchListJson(val matches: List<MatchJson> = emptyList())

@Serializable
data class TeamListJson(val teams: List<TeamJson> = emptyList())

@Serializable
data class LeagueListJson(val leagues: List<LeagueJson> = emptyList())

@Serializable
data class RegisterDeviceRequest(
    val token: String,
    val platform: String = "android",
    val appVersion: String? = null,
    val timeZone: String? = null,
    val locale: String? = null,
)

@Serializable
data class RegisterDeviceResponse(val deviceId: String, val ok: Boolean = true)

@Serializable
data class SubscriptionRequest(
    val token: String,
    val teamIds: List<Int> = emptyList(),
    val leagueIds: List<Int> = emptyList(),
    val matchIds: List<Long> = emptyList(),
    val preferences: SubscriptionPreferencesJson = SubscriptionPreferencesJson(),
)

@Serializable
data class SubscriptionPreferencesJson(
    val goals: Boolean = true,
    val cards: Boolean = true,
    val substitutions: Boolean = false,
    val kickoffAndFullTime: Boolean = true,
    val lineups: Boolean = true,
    val preMatchLeadMinutes: Int = 60,
)

@Serializable
data class HealthJson(
    val ok: Boolean = false,
    val version: String? = null,
    val provider: String? = null,
    val pollingEnabled: Boolean = false,
)
