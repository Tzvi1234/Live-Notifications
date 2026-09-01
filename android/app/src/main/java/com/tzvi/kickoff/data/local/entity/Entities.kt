package com.tzvi.kickoff.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A team the user follows, plus enough cached detail to render it offline. */
@Entity(tableName = "favourite_teams")
data class FavouriteTeamEntity(
    @PrimaryKey val teamId: Int,
    val name: String,
    val shortName: String,
    val crestUrl: String?,
    val countryName: String?,
    val leagueId: Int?,
    val leagueName: String?,
    /** Lower sorts first; the onboarding pick order becomes the home-screen order. */
    val sortOrder: Int = 0,
    val addedAt: Long = 0,
)

@Entity(tableName = "followed_leagues")
data class FollowedLeagueEntity(
    @PrimaryKey val leagueId: Int,
    val name: String,
    val countryName: String?,
    val logoUrl: String?,
    val season: Int,
    val sortOrder: Int = 0,
    /**
     * What the provider carries for this competition, cached alongside it.
     *
     * Held per league rather than fetched per match because it is a property of the
     * competition and season, and a match screen opened offline still has to be able to
     * tell "the line-up is not out yet" from "this cup never has line-ups".
     */
    val coversLineups: Boolean = true,
    val coversEvents: Boolean = true,
    val coversFixtureStats: Boolean = true,
    val coversPlayerStats: Boolean = true,
    val coversStandings: Boolean = true,
    val coversInjuries: Boolean = true,
    val coversPredictions: Boolean = true,
)

/** Cached fixtures. Kept flat: this is a read-through cache, not a source of truth. */
@Entity(
    tableName = "matches",
    indices = [Index("kickoffAt"), Index("homeTeamId"), Index("awayTeamId"), Index("leagueId")],
)
data class MatchEntity(
    @PrimaryKey val id: Long,
    val leagueId: Int,
    val leagueName: String,
    val leagueLogoUrl: String?,
    val round: String?,
    val kickoffAt: Long,
    val venue: String?,
    val phase: String,
    val elapsed: Int?,
    val extra: Int?,
    val homeTeamId: Int,
    val homeTeamName: String,
    val homeTeamShort: String,
    val homeCrestUrl: String?,
    val awayTeamId: Int,
    val awayTeamName: String,
    val awayTeamShort: String,
    val awayCrestUrl: String?,
    val homeScore: Int?,
    val awayScore: Int?,
    val halfTimeHome: Int?,
    val halfTimeAway: Int?,
    val penaltyHome: Int?,
    val penaltyAway: Int?,
    val referee: String?,
    val updatedAt: Long = 0,
)

/**
 * Events we have already surfaced.
 *
 * [notified] is the dedupe gate: a provider re-report, an FCM push and a poll can all
 * deliver the same incident, and only the first one is allowed to alert.
 */
@Entity(tableName = "match_events", indices = [Index("matchId")])
data class MatchEventEntity(
    @PrimaryKey val id: String,
    val matchId: Long,
    val type: String,
    val side: String,
    val teamId: Int?,
    val teamName: String?,
    val minute: Int?,
    val extra: Int?,
    val playerName: String?,
    val assistName: String?,
    val detail: String?,
    val comment: String?,
    val scoreHome: Int?,
    val scoreAway: Int?,
    val notified: Boolean = false,
    val receivedAt: Long = 0,
)

/** Live activities currently on screen, so the service can rebuild them after a restart. */
@Entity(tableName = "tracked_activities")
data class TrackedActivityEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val matchId: Long?,
    val startsAt: Long,
    val endsAt: Long?,
    @ColumnInfo(defaultValue = "0") val dismissed: Boolean = false,
    val lastSequence: Long = 0,
    val updatedAt: Long = 0,
)
