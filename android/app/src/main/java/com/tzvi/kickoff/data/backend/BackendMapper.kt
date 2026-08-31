package com.tzvi.kickoff.data.backend

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.LineupPlayer
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
import java.time.Instant

/** Backend wire shapes -> domain. */
object BackendMapper {

    fun team(json: TeamJson) = Team(
        id = json.id,
        name = json.name,
        shortName = json.shortName ?: json.name.take(3).uppercase(),
        crestUrl = json.crestUrl,
        countryName = json.country,
        founded = json.founded,
        venueName = json.venue,
    )

    fun league(json: LeagueJson) = League(
        id = json.id,
        name = json.name,
        countryName = json.country,
        logoUrl = json.logoUrl,
        season = json.season,
        type = json.type,
    )

    fun match(json: MatchJson) = Match(
        id = json.id,
        leagueId = json.leagueId,
        leagueName = json.leagueName,
        leagueLogoUrl = json.leagueLogoUrl,
        round = json.round,
        kickoffAt = Instant.ofEpochSecond(json.kickoffAt),
        venue = json.venue,
        phase = runCatching { MatchPhase.valueOf(json.phase) }.getOrDefault(MatchPhase.UNKNOWN),
        elapsedMinutes = json.elapsed,
        extraMinutes = json.extra,
        home = team(json.home),
        away = team(json.away),
        score = json.score?.let { Score(it.home, it.away) },
        halfTimeScore = json.halfTimeScore?.let { Score(it.home, it.away) },
        penaltyScore = json.penaltyScore?.let { Score(it.home, it.away) },
        referee = json.referee,
    )

    fun event(matchId: Long, json: MatchEventJson) = MatchEvent(
        id = json.id,
        matchId = matchId,
        type = runCatching { MatchEventType.valueOf(json.type) }.getOrDefault(MatchEventType.OTHER),
        side = runCatching { MatchSide.valueOf(json.side) }.getOrDefault(MatchSide.NEUTRAL),
        teamId = json.teamId,
        teamName = json.teamName,
        minute = json.minute,
        extraMinute = json.extra,
        playerName = json.player,
        assistName = json.assist,
        detail = json.detail,
        comment = json.comment,
        scoreAfter = json.scoreAfter?.let { Score(it.home, it.away) },
    )

    fun lineups(json: MatchDetailJson) = MatchLineups(
        matchId = json.match.id,
        home = json.homeLineup?.let(::teamLineup),
        away = json.awayLineup?.let(::teamLineup),
    )

    private fun teamLineup(json: TeamLineupJson) = TeamLineup(
        teamId = json.teamId,
        teamName = json.teamName,
        crestUrl = json.crestUrl,
        formation = json.formation,
        startingXi = json.startingXi.map(::player),
        substitutes = json.substitutes.map(::player),
        coachName = json.coach,
        shirtColor = json.shirtColor,
    )

    private fun player(json: LineupPlayerJson) = LineupPlayer(
        id = json.id,
        name = json.name,
        number = json.number,
        position = json.position,
        gridRow = json.row,
        gridColumn = json.column,
        photoUrl = json.photoUrl,
    )

    fun statistics(json: MatchDetailJson) =
        MatchStatistics(json.match.id, json.homeStats, json.awayStats)
}
