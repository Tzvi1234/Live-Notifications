package com.tzvi.kickoff.core.model

/**
 * Everything a player sheet shows: who they are, and what they did in this match.
 *
 * The two halves come from different places and either can be missing. `/fixtures/players`
 * carries the match line for all ~36 players in one request, so it is fetched once per
 * fixture and indexed by id; `/players/profiles` carries the birth date and the physical
 * details and costs a request per player, so it is optional and never blocks the sheet.
 */
data class PlayerCard(
    val id: Int,
    val name: String,
    val photoUrl: String?,
    val teamName: String?,
    val profile: PlayerProfile?,
    val match: PlayerMatchStats?,
)

data class PlayerProfile(
    val firstName: String? = null,
    val lastName: String? = null,
    val age: Int? = null,
    val birthDate: String? = null,
    val birthPlace: String? = null,
    val nationality: String? = null,
    /** The provider hands these over as strings with their unit already in them. */
    val height: String? = null,
    val weight: String? = null,
    val position: String? = null,
    val number: Int? = null,
)

/**
 * One player's line from one fixture.
 *
 * Every field is nullable and that is not defensiveness: the provider's own sample has a
 * third of the stats null for a keeper who played the full ninety. Null means "not
 * recorded for this player in this competition", which is a different claim from zero -
 * so the sheet draws an em dash rather than a 0 and never invents a number.
 */
data class PlayerMatchStats(
    val minutes: Int? = null,
    val number: Int? = null,
    /** Short code as the provider sends it: G, D, M, F. */
    val position: String? = null,
    /** "7.4". A string upstream, kept as one rather than rounded through a Double. */
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
    /** Already a percentage string upstream ("68%") on this endpoint, unlike season stats. */
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
) {
    /** True once anything at all was recorded - otherwise the sheet says so plainly. */
    val hasAnything: Boolean
        get() = minutes != null || rating != null || goals != null || passesTotal != null
}
