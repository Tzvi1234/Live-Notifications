package com.tzvi.kickoff.core.model

/**
 * What the provider thinks of a fixture before it starts.
 *
 * This is the honest answer to "give me something to bet on". API-Football has no
 * expected line-up endpoint at all - the XI arrives twenty to forty minutes before
 * kick-off and not a moment sooner - so the pre-match indication has to come from the
 * things it does compute: a Poisson model over both teams' form, their season records,
 * and their meetings with each other. It is explicitly NOT derived from bookmakers'
 * odds, which is worth knowing before treating [advice] as a tip.
 *
 * Every field is nullable because the whole block is gated per competition by
 * [LeagueCoverage.predictions]: a domestic cup can carry fixtures and events and still
 * have nothing here.
 */
data class MatchPrediction(
    /** Percentages, 0..100, that sum to about a hundred. */
    val homePercent: Int?,
    val drawPercent: Int?,
    val awayPercent: Int?,
    /** The provider's one-line call, e.g. "Combo Double chance : Arsenal or draw". */
    val advice: String?,
    /** Named winner, when it is confident enough to name one. */
    val winnerName: String?,
    val winnerComment: String?,
    /** "-3.5" or "+2.5": which side of a goals line it leans. */
    val goalsLine: String?,
    val homeForm: TeamForm?,
    val awayForm: TeamForm?,
) {
    /** True when there is enough here to be worth drawing at all. */
    val hasNumbers: Boolean
        get() = homePercent != null || awayPercent != null || advice != null
}

/** A team's recent shape, as the provider summarises it. */
data class TeamForm(
    /** Most recent last, e.g. "WWDLW". */
    val recentResults: String?,
    val attackRating: String?,
    val defenceRating: String?,
    val goalsForAverage: String?,
    val goalsAgainstAverage: String?,
    val cleanSheets: Int?,
)
