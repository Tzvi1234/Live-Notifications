package com.tzvi.kickoff.feature.matchdetail

import com.tzvi.kickoff.core.model.LineupPlayer
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchLineups
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.core.model.TeamLineup
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Fixtures for the @Preview composables in this package. Nothing here is reachable from
 * the running app; it exists so every state of the screen can be looked at in the IDE.
 */

private val PreviewHome = Team(1, "Arsenal", "ARS", null)
private val PreviewAway = Team(2, "Chelsea", "CHE", null)

internal fun previewMatch(
    phase: MatchPhase = MatchPhase.SCHEDULED,
    elapsedMinutes: Int? = null,
    score: Score? = null,
    halfTimeScore: Score? = null,
): Match = Match(
    id = 1,
    leagueId = 39,
    leagueName = "Premier League",
    leagueLogoUrl = null,
    round = "Matchweek 4",
    kickoffAt = Instant.now().minus(elapsedMinutes?.toLong() ?: -180L, ChronoUnit.MINUTES),
    venue = "Emirates Stadium, London",
    phase = phase,
    elapsedMinutes = elapsedMinutes,
    extraMinutes = null,
    home = PreviewHome,
    away = PreviewAway,
    score = score,
    halfTimeScore = halfTimeScore,
    referee = "Michael Oliver",
)

internal fun previewTimeline(): List<TimelineEntry> {
    var home = 0
    var away = 0
    return previewEvents()
        .map { event ->
            if (event.type.isGoal) {
                if (event.side == MatchSide.HOME) home++ else away++
            }
            TimelineEntry(event, Score(home, away))
        }
        .reversed()
}

private fun previewEvents(): List<MatchEvent> = listOf(
    previewEvent(MatchEventType.KICK_OFF, MatchSide.NEUTRAL, 0),
    previewEvent(MatchEventType.GOAL, MatchSide.HOME, 12, "Bukayo Saka", assist = "Martin Ødegaard"),
    previewEvent(MatchEventType.YELLOW_CARD, MatchSide.AWAY, 23, "Moisés Caicedo", detail = "Foul"),
    previewEvent(MatchEventType.GOAL, MatchSide.AWAY, 39, "Cole Palmer", assist = "Raheem Sterling"),
    previewEvent(MatchEventType.HALF_TIME, MatchSide.NEUTRAL, 45),
    previewEvent(MatchEventType.SUBSTITUTION, MatchSide.HOME, 58, "Leandro Trossard", assist = "Gabriel Martinelli"),
    previewEvent(MatchEventType.VAR, MatchSide.AWAY, 61, detail = "Goal disallowed - offside"),
    previewEvent(MatchEventType.PENALTY_GOAL, MatchSide.HOME, 64, "Martin Ødegaard"),
    previewEvent(MatchEventType.RED_CARD, MatchSide.AWAY, 66, "Trevoh Chalobah", detail = "Violent conduct"),
)

private fun previewEvent(
    type: MatchEventType,
    side: MatchSide,
    minute: Int,
    player: String? = null,
    assist: String? = null,
    detail: String? = null,
): MatchEvent = MatchEvent(
    id = MatchEvent.key(1, type, 0, if (side == MatchSide.HOME) 1 else 2, player),
    matchId = 1,
    type = type,
    side = side,
    teamId = if (side == MatchSide.HOME) 1 else 2,
    teamName = if (side == MatchSide.HOME) PreviewHome.name else PreviewAway.name,
    minute = minute,
    extraMinute = null,
    playerName = player,
    assistName = assist,
    detail = detail,
)

internal fun previewLineups(): MatchLineups = MatchLineups(
    matchId = 1,
    home = TeamLineup(
        teamId = 1,
        teamName = "Arsenal",
        crestUrl = null,
        formation = "4-2-3-1",
        startingXi = listOf(
            previewPlayer("David Raya", 22, 1, 1),
            previewPlayer("Ben White", 4, 2, 1),
            previewPlayer("William Saliba", 2, 2, 2),
            previewPlayer("Gabriel Magalhães", 6, 2, 3),
            previewPlayer("Riccardo Calafiori", 33, 2, 4),
            previewPlayer("Declan Rice", 41, 3, 1),
            previewPlayer("Thomas Partey", 5, 3, 2),
            previewPlayer("Bukayo Saka", 7, 4, 1),
            previewPlayer("Martin Ødegaard", 8, 4, 2),
            previewPlayer("Gabriel Martinelli", 11, 4, 3),
            previewPlayer("Kai Havertz", 29, 5, 1),
        ),
        substitutes = listOf(
            previewPlayer("Neto", 32, null, null),
            previewPlayer("Jakub Kiwior", 15, null, null),
            previewPlayer("Jurriën Timber", 12, null, null),
            previewPlayer("Jorginho", 20, null, null),
            previewPlayer("Leandro Trossard", 19, null, null),
            previewPlayer("Gabriel Jesus", 9, null, null),
        ),
        coachName = "Mikel Arteta",
    ),
    away = TeamLineup(
        teamId = 2,
        teamName = "Chelsea",
        crestUrl = null,
        formation = "4-3-3",
        startingXi = listOf(
            previewPlayer("Robert Sánchez", 1, 1, 1),
            previewPlayer("Malo Gusto", 27, 2, 1),
            previewPlayer("Trevoh Chalobah", 23, 2, 2),
            previewPlayer("Levi Colwill", 6, 2, 3),
            previewPlayer("Marc Cucurella", 3, 2, 4),
            previewPlayer("Moisés Caicedo", 25, 3, 1),
            previewPlayer("Enzo Fernández", 8, 3, 2),
            previewPlayer("Cole Palmer", 20, 3, 3),
            previewPlayer("Pedro Neto", 7, 4, 1),
            previewPlayer("Nicolas Jackson", 15, 4, 2),
            previewPlayer("Raheem Sterling", 30, 4, 3),
        ),
        substitutes = listOf(
            previewPlayer("Filip Jörgensen", 12, null, null),
            previewPlayer("Wesley Fofana", 29, null, null),
            previewPlayer("Reece James", 24, null, null),
            previewPlayer("Christopher Nkunku", 18, null, null),
            previewPlayer("Noni Madueke", 11, null, null),
        ),
        coachName = "Enzo Maresca",
    ),
)

private fun previewPlayer(
    name: String,
    number: Int?,
    gridRow: Int?,
    gridColumn: Int?,
): LineupPlayer = LineupPlayer(
    id = null,
    name = name,
    number = number,
    position = null,
    gridRow = gridRow,
    gridColumn = gridColumn,
)

internal fun previewStats(): List<StatComparison> = listOf(
    previewStat("Possession", "58%", "42%", 58f, 42f),
    previewStat("Shots", "14", "9", 14f, 9f),
    previewStat("Shots on target", "6", "3", 6f, 3f),
    previewStat("Fouls", "8", "12", 8f, 12f),
    previewStat("Corners", "7", "4", 7f, 4f),
    previewStat("Offsides", "2", "1", 2f, 1f),
    previewStat("Expected goals", "1.84", "0.97", 1.84f, 0.97f),
)

private fun previewStat(
    label: String,
    homeLabel: String,
    awayLabel: String,
    home: Float,
    away: Float,
): StatComparison = StatComparison(
    label = label,
    homeLabel = homeLabel,
    awayLabel = awayLabel,
    homeFraction = home / (home + away),
    awayFraction = away / (home + away),
)
