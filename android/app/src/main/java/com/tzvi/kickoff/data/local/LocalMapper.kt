package com.tzvi.kickoff.data.local

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.data.local.entity.FavouriteTeamEntity
import com.tzvi.kickoff.data.local.entity.FollowedLeagueEntity
import com.tzvi.kickoff.data.local.entity.MatchEntity
import com.tzvi.kickoff.data.local.entity.MatchEventEntity
import java.time.Instant

fun MatchEntity.toDomain() = Match(
    id = id,
    leagueId = leagueId,
    leagueName = leagueName,
    leagueLogoUrl = leagueLogoUrl,
    round = round,
    kickoffAt = Instant.ofEpochSecond(kickoffAt),
    venue = venue,
    phase = runCatching { MatchPhase.valueOf(phase) }.getOrDefault(MatchPhase.UNKNOWN),
    elapsedMinutes = elapsed,
    extraMinutes = extra,
    home = Team(homeTeamId, homeTeamName, homeTeamShort, homeCrestUrl),
    away = Team(awayTeamId, awayTeamName, awayTeamShort, awayCrestUrl),
    score = if (homeScore == null && awayScore == null) null
    else Score(homeScore ?: 0, awayScore ?: 0),
    halfTimeScore = if (halfTimeHome == null && halfTimeAway == null) null
    else Score(halfTimeHome ?: 0, halfTimeAway ?: 0),
    penaltyScore = if (penaltyHome == null && penaltyAway == null) null
    else Score(penaltyHome ?: 0, penaltyAway ?: 0),
    referee = referee,
)

fun Match.toEntity(updatedAt: Long = System.currentTimeMillis()) = MatchEntity(
    id = id,
    leagueId = leagueId,
    leagueName = leagueName,
    leagueLogoUrl = leagueLogoUrl,
    round = round,
    kickoffAt = kickoffAt.epochSecond,
    venue = venue,
    phase = phase.name,
    elapsed = elapsedMinutes,
    extra = extraMinutes,
    homeTeamId = home.id,
    homeTeamName = home.name,
    homeTeamShort = home.shortName,
    homeCrestUrl = home.crestUrl,
    awayTeamId = away.id,
    awayTeamName = away.name,
    awayTeamShort = away.shortName,
    awayCrestUrl = away.crestUrl,
    homeScore = score?.home,
    awayScore = score?.away,
    halfTimeHome = halfTimeScore?.home,
    halfTimeAway = halfTimeScore?.away,
    penaltyHome = penaltyScore?.home,
    penaltyAway = penaltyScore?.away,
    referee = referee,
    updatedAt = updatedAt,
)

fun MatchEventEntity.toDomain() = MatchEvent(
    id = id,
    matchId = matchId,
    type = runCatching { MatchEventType.valueOf(type) }.getOrDefault(MatchEventType.OTHER),
    side = runCatching { MatchSide.valueOf(side) }.getOrDefault(MatchSide.NEUTRAL),
    teamId = teamId,
    teamName = teamName,
    minute = minute,
    extraMinute = extra,
    playerName = playerName,
    assistName = assistName,
    detail = detail,
    comment = comment,
    scoreAfter = if (scoreHome == null && scoreAway == null) null
    else Score(scoreHome ?: 0, scoreAway ?: 0),
)

fun MatchEvent.toEntity(receivedAt: Long = System.currentTimeMillis()) = MatchEventEntity(
    id = id,
    matchId = matchId,
    type = type.name,
    side = side.name,
    teamId = teamId,
    teamName = teamName,
    minute = minute,
    extra = extraMinute,
    playerName = playerName,
    assistName = assistName,
    detail = detail,
    comment = comment,
    scoreHome = scoreAfter?.home,
    scoreAway = scoreAfter?.away,
    receivedAt = receivedAt,
)

fun FavouriteTeamEntity.toDomain() =
    Team(teamId, name, shortName, crestUrl, countryName)

fun Team.toFavouriteEntity(leagueId: Int?, leagueName: String?, sortOrder: Int) =
    FavouriteTeamEntity(
        teamId = id,
        name = name,
        shortName = shortName,
        crestUrl = crestUrl,
        countryName = countryName,
        leagueId = leagueId,
        leagueName = leagueName,
        sortOrder = sortOrder,
        addedAt = System.currentTimeMillis(),
    )

fun FollowedLeagueEntity.toDomain() = League(leagueId, name, countryName, logoUrl, season)

fun League.toEntity(sortOrder: Int = 0) =
    FollowedLeagueEntity(id, name, countryName, logoUrl, season, sortOrder)
