package com.tzvi.kickoff.core.model

import java.time.Instant

/** A group of people guessing scores at the same set of matches. */
data class PredictGroup(
    val id: Long,
    val name: String,
    /** What you send a friend. Short and shoutable, because it gets read out loud. */
    val inviteCode: String,
    val isOwner: Boolean,
    val memberCount: Int,
    val leagueIds: List<Int>,
    /**
     * The clubs the group follows.
     *
     * A fixture counts if EITHER side is in here - picking Arsenal without picking
     * whoever they are playing still puts that match on the card, which is the whole
     * point of choosing teams rather than choosing fixtures.
     */
    val teamIds: List<Int>,
)

data class GroupMember(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val points: Int,
    /** How many scorelines they got exactly right. */
    val exactCount: Int,
    /**
     * How many they called the right way, exact scores included - an exact score is also
     * the right direction, and a table that counted them separately would read as though
     * getting it perfectly right did not also mean getting it right.
     */
    val outcomeCount: Int,
    val settledCount: Int = 0,
    /** Dense, so two people level both show the same number. */
    val rank: Int = 0,
)

/** One person's guess at one fixture. */
data class PredictionEntry(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val home: Int,
    val away: Int,
    /** Null until the match is settled. */
    val points: Int?,
) {
    val scoreLabel: String get() = "$home-$away"
}

/**
 * A fixture as the group sees it.
 *
 * [others] is empty until kick-off, and that is the server withholding them rather than
 * the screen hiding them: a guess nobody can peek at is the only kind worth making.
 */
data class GroupFixture(
    /** The full fixture, so the card can reuse the same rendering as everywhere else. */
    val match: Match,
    val locked: Boolean,
    val myPrediction: PredictionEntry?,
    /** Everybody's, and empty until the match kicks off. */
    val others: List<PredictionEntry>,
) {
    val matchId: Long get() = match.id
    val isLive: Boolean get() = match.isLive

    /**
     * Open for guessing.
     *
     * [locked] is the server's word and is the one that counts; the phase checks only
     * stop a card looking editable in the seconds before a refresh catches up.
     */
    val isOpen: Boolean
        get() = !locked && !isLive && !match.phase.isFinished
}

data class ChatMessage(
    val id: Long,
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val text: String,
    val sentAt: Instant,
)

/**
 * What a guess is worth.
 *
 * Mirrored from the server, which is the only place it is actually applied - these
 * constants exist so the rules can be shown on screen without the app inventing its own
 * version of them.
 */
object PredictScoring {
    const val EXACT = 3
    const val OUTCOME = 1
    const val WRONG = 0
}
