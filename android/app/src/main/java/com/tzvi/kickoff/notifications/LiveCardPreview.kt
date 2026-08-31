package com.tzvi.kickoff.notifications

import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.core.model.LiveCardStyle
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchLineups
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.MatchStatistics
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.core.model.TeamLineup
import com.tzvi.kickoff.core.model.LineupPlayer
import com.tzvi.kickoff.data.remote.api.ApiFootballService
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a fabricated live card on demand.
 *
 * Without this the only way to see the feature is to follow a team that happens to be
 * playing right now, which makes it almost impossible to check how the card looks on a
 * particular device — and whether the system actually promoted it — during development.
 * The card is real: it goes through the same builder, the same channel and the same
 * eligibility rules as a genuine match, so what you see here is what a real match gets.
 *
 * The fixture id is negative so it can never collide with a provider id, and the
 * activity is not tracked, so nothing tries to keep polling it.
 */
@Singleton
class LiveCardPreview @Inject constructor(
    private val notifier: LiveActivityNotifier,
    private val capability: LiveUpdateCapability,
) {
    /**
     * @return which of the three renderings the system actually used, so a settings
     *   screen can report the truth rather than the intent.
     */
    suspend fun show(
        style: LiveCardStyle = LiveCardStyle.AUTO,
        stage: LiveActivity.MatchActivity.Stage = LiveActivity.MatchActivity.Stage.LIVE,
    ): MatchNotificationBuilder.Rendering? {
        val activity = sampleActivity(stage)
        return notifier.postMatch(activity, style)?.rendering
    }

    fun hide() {
        notifier.cancel(LiveActivity.MatchActivity.matchKey(DEMO_MATCH_ID))
    }

    fun promotionAllowed(): Boolean = capability.canPostPromoted()

    private fun sampleActivity(
        stage: LiveActivity.MatchActivity.Stage,
    ): LiveActivity.MatchActivity {
        val live = stage == LiveActivity.MatchActivity.Stage.LIVE
        val match = Match(
            id = DEMO_MATCH_ID,
            leagueId = 39,
            leagueName = "Premier League",
            leagueLogoUrl = ApiFootballService.leagueLogoUrl(39),
            round = "Matchweek 4",
            kickoffAt = if (live) Instant.now().minus(Duration.ofMinutes(67))
            else Instant.now().plus(Duration.ofMinutes(43)),
            venue = "Emirates Stadium",
            phase = if (live) MatchPhase.SECOND_HALF else MatchPhase.SCHEDULED,
            elapsedMinutes = if (live) 67 else null,
            extraMinutes = null,
            home = Team(42, "Arsenal", "ARS", ApiFootballService.teamCrestUrl(42)),
            away = Team(49, "Chelsea", "CHE", ApiFootballService.teamCrestUrl(49)),
            score = if (live) Score(2, 1) else null,
            halfTimeScore = if (live) Score(1, 0) else null,
        )

        return LiveActivity.MatchActivity(
            match = match,
            stage = stage,
            lineups = if (live) null else sampleLineups(),
            recentEvents = if (live) sampleEvents() else emptyList(),
            statistics = if (live) {
                MatchStatistics(
                    matchId = DEMO_MATCH_ID,
                    home = mapOf(MatchStatistics.POSSESSION to "58%", MatchStatistics.SHOTS to "12"),
                    away = mapOf(MatchStatistics.POSSESSION to "42%", MatchStatistics.SHOTS to "7"),
                )
            } else {
                null
            },
        )
    }

    private fun sampleEvents(): List<MatchEvent> = listOf(
        demoEvent(MatchEventType.GOAL, MatchSide.HOME, 23, "Saka", "Ødegaard", Score(1, 0)),
        demoEvent(MatchEventType.YELLOW_CARD, MatchSide.AWAY, 41, "Fofana", null, Score(1, 0)),
        demoEvent(MatchEventType.GOAL, MatchSide.AWAY, 58, "Palmer", null, Score(1, 1)),
        demoEvent(MatchEventType.GOAL, MatchSide.HOME, 67, "Havertz", "Rice", Score(2, 1)),
    )

    private fun demoEvent(
        type: MatchEventType,
        side: MatchSide,
        minute: Int,
        player: String,
        assist: String?,
        score: Score,
    ) = MatchEvent(
        id = MatchEvent.key(DEMO_MATCH_ID, type, minute, if (side == MatchSide.HOME) 42 else 49, player),
        matchId = DEMO_MATCH_ID,
        type = type,
        side = side,
        teamId = if (side == MatchSide.HOME) 42 else 49,
        teamName = if (side == MatchSide.HOME) "Arsenal" else "Chelsea",
        minute = minute,
        extraMinute = null,
        playerName = player,
        assistName = assist,
        detail = null,
        scoreAfter = score,
    )

    private fun sampleLineups() = MatchLineups(
        matchId = DEMO_MATCH_ID,
        home = TeamLineup(
            teamId = 42,
            teamName = "Arsenal",
            crestUrl = ApiFootballService.teamCrestUrl(42),
            formation = "4-3-3",
            startingXi = xi(
                "Raya", "White", "Saliba", "Gabriel", "Timber",
                "Rice", "Ødegaard", "Merino", "Saka", "Havertz", "Martinelli",
            ),
            substitutes = emptyList(),
            coachName = "M. Arteta",
        ),
        away = TeamLineup(
            teamId = 49,
            teamName = "Chelsea",
            crestUrl = ApiFootballService.teamCrestUrl(49),
            formation = "4-2-3-1",
            startingXi = xi(
                "Sánchez", "Gusto", "Fofana", "Colwill", "Cucurella",
                "Caicedo", "Fernández", "Palmer", "Neto", "Madueke", "Jackson",
            ),
            substitutes = emptyList(),
            coachName = "E. Maresca",
        ),
    )

    /** Row/column follow the provider's grid convention: row 1 is the goalkeeper. */
    private fun xi(vararg names: String): List<LineupPlayer> {
        val rows = listOf(1, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4)
        var column = 0
        var lastRow = 0
        return names.mapIndexed { index, name ->
            val row = rows.getOrElse(index) { 4 }
            column = if (row == lastRow) column + 1 else 1
            lastRow = row
            LineupPlayer(
                id = null,
                name = name,
                number = index + 1,
                position = null,
                gridRow = row,
                gridColumn = column,
            )
        }
    }

    private companion object {
        /** Negative so it can never collide with a real provider fixture id. */
        const val DEMO_MATCH_ID = -1L
    }
}
