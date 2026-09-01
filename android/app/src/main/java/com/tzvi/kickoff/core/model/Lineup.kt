package com.tzvi.kickoff.core.model

/**
 * A player in a starting XI or on the bench.
 *
 * [gridRow]/[gridColumn] come from the provider's `grid` field ("row:col", counted from
 * the goalkeeper outwards) and are what make an actual pitch rendering possible.
 */
data class LineupPlayer(
    val id: Int?,
    val name: String,
    val number: Int?,
    val position: String?,
    val gridRow: Int?,
    val gridColumn: Int?,
    val photoUrl: String? = null,
) {
    /** "Ødegaard" - the notification and pitch chip only have room for a surname. */
    val surname: String
        get() = name.trim().substringAfterLast(' ', name).takeIf { it.isNotBlank() } ?: name
}

data class TeamLineup(
    val teamId: Int,
    val teamName: String,
    val crestUrl: String?,
    val formation: String?,
    val startingXi: List<LineupPlayer>,
    val substitutes: List<LineupPlayer>,
    val coachName: String?,
    val shirtColor: String? = null,
) {
    /** Starting XI grouped into pitch rows, goalkeeper first. */
    val rows: List<List<LineupPlayer>>
        get() = startingXi
            .filter { it.gridRow != null }
            .groupBy { it.gridRow!! }
            .toSortedMap()
            .values
            .map { row -> row.sortedBy { it.gridColumn ?: 0 } }
            .toList()
}

data class MatchLineups(
    val matchId: Long,
    val home: TeamLineup?,
    val away: TeamLineup?,
) {
    val isConfirmed: Boolean get() = home?.startingXi?.size == 11 && away?.startingXi?.size == 11
}

/** Team-level match statistics, keyed by a normalised name. */
data class MatchStatistics(
    val matchId: Long,
    val home: Map<String, String>,
    val away: Map<String, String>,
) {
    fun pair(key: String): Pair<String, String>? {
        val h = home[key] ?: return null
        val a = away[key] ?: return null
        return h to a
    }

    companion object {
        const val POSSESSION = "Ball Possession"
        const val SHOTS = "Total Shots"
        const val SHOTS_ON_GOAL = "Shots on Goal"
        const val FOULS = "Fouls"
        const val CORNERS = "Corner Kicks"
        const val OFFSIDES = "Offsides"
        const val YELLOW = "Yellow Cards"
        const val RED = "Red Cards"
        const val XG = "expected_goals"
        val HIGHLIGHTS = listOf(POSSESSION, SHOTS, SHOTS_ON_GOAL, FOULS, CORNERS, OFFSIDES, XG)
    }
}
