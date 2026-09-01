package com.tzvi.kickoff.data.backend

import kotlinx.serialization.Serializable

/**
 * Wire shapes for the matchUP backend (see `server/`).
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
    val coverage: LeagueCoverageJson? = null,
)

/** What the provider actually carries for this competition this season. */
@Serializable
data class LeagueCoverageJson(
    val lineups: Boolean = true,
    val events: Boolean = true,
    val fixtureStatistics: Boolean = true,
    val playerStatistics: Boolean = true,
    val standings: Boolean = true,
    val injuries: Boolean = true,
    val predictions: Boolean = true,
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

@Serializable
data class SquadJson(val players: List<SquadPlayerJson> = emptyList())

@Serializable
data class SquadPlayerJson(
    val id: Int? = null,
    val name: String,
    val number: Int? = null,
    val position: String? = null,
    val photoUrl: String? = null,
)

/** Mirrors [com.tzvi.kickoff.core.model.PlayerProfile] field for field. */

@Serializable
data class PlayerProfileJson(
    val firstName: String? = null,
    val lastName: String? = null,
    val age: Int? = null,
    val birthDate: String? = null,
    val birthPlace: String? = null,
    val nationality: String? = null,
    val height: String? = null,
    val weight: String? = null,
    val position: String? = null,
    val number: Int? = null,
)

@Serializable
data class MatchPlayersJson(val players: Map<String, PlayerMatchStatsJson> = emptyMap())

/**
 * Mirrors [com.tzvi.kickoff.core.model.PlayerMatchStats].
 *
 * Everything is nullable for the same reason it is there: the provider leaves a third of
 * a keeper's line blank after a full ninety, and a zero would be a different claim.
 */
@Serializable
data class PlayerMatchStatsJson(
    val minutes: Int? = null,
    val number: Int? = null,
    val position: String? = null,
    val rating: String? = null,
    val captain: Boolean = false,
    val startedOnBench: Boolean = false,
    val goals: Int? = null,
    val assists: Int? = null,
    val conceded: Int? = null,
    val saves: Int? = null,
    val shotsTotal: Int? = null,
    val shotsOnTarget: Int? = null,
    val passesTotal: Int? = null,
    val passesKey: Int? = null,
    val passAccuracy: String? = null,
    val tackles: Int? = null,
    val interceptions: Int? = null,
    val duelsTotal: Int? = null,
    val duelsWon: Int? = null,
    val dribbleAttempts: Int? = null,
    val dribblesPast: Int? = null,
    val dribblesSuccessful: Int? = null,
    val foulsDrawn: Int? = null,
    val foulsCommitted: Int? = null,
    val yellowCards: Int? = null,
    val redCards: Int? = null,
    val offsides: Int? = null,
    val penaltiesScored: Int? = null,
    val penaltiesMissed: Int? = null,
    val penaltiesSaved: Int? = null,
)

/** The provider's pre-match read, already mapped by the server. */
@Serializable
data class PredictionJson(
    val homePercent: Int? = null,
    val drawPercent: Int? = null,
    val awayPercent: Int? = null,
    val advice: String? = null,
    val winnerName: String? = null,
    val winnerComment: String? = null,
    val goalsLine: String? = null,
    val homeForm: TeamFormJson? = null,
    val awayForm: TeamFormJson? = null,
)

@Serializable
data class TeamFormJson(
    val recentResults: String? = null,
    val attackRating: String? = null,
    val defenceRating: String? = null,
    val goalsForAverage: String? = null,
    val goalsAgainstAverage: String? = null,
    val cleanSheets: Int? = null,
)

/**
 * Values the server hands the app at start-up.
 *
 * The Clerk publishable key lives here rather than in the APK so it can be rotated on
 * Render without shipping a new build, and so a self-hosted backend can point the app at
 * its own Clerk instance.
 */
@Serializable
data class ServerConfigJson(
    val clerkPublishableKey: String? = null,
    val predictionsEnabled: Boolean = false,
    val chatEnabled: Boolean = false,
)
