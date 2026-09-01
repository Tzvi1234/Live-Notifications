package com.tzvi.kickoff.data.remote

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
import com.tzvi.kickoff.data.remote.api.ApiFootballService
import com.tzvi.kickoff.data.remote.dto.EventResponse
import com.tzvi.kickoff.data.remote.dto.FixtureResponse
import com.tzvi.kickoff.data.remote.dto.LeagueCatalogueResponse
import com.tzvi.kickoff.data.remote.dto.FixturePlayersResponse
import com.tzvi.kickoff.data.remote.dto.PlayerFixtureStatsDto
import com.tzvi.kickoff.data.remote.dto.PlayerProfileDto
import com.tzvi.kickoff.core.model.PlayerMatchStats
import com.tzvi.kickoff.core.model.PlayerProfile
import com.tzvi.kickoff.data.remote.dto.LineupPlayerDto
import com.tzvi.kickoff.data.remote.dto.SquadPlayerDto
import com.tzvi.kickoff.data.remote.dto.LineupResponse
import com.tzvi.kickoff.data.remote.dto.StatisticsResponse
import com.tzvi.kickoff.data.remote.dto.TeamCatalogueResponse
import com.tzvi.kickoff.data.remote.dto.TeamRefDto
import java.time.Instant

/** API-Football wire shapes -> domain. Nothing above this file knows the provider exists. */
object ApiFootballMapper {

    fun team(dto: TeamRefDto?): Team = Team(
        id = dto?.id ?: 0,
        name = dto?.name ?: "Unknown",
        shortName = abbreviate(dto?.name),
        crestUrl = dto?.logo ?: dto?.id?.let(ApiFootballService::teamCrestUrl),
    )

    fun team(dto: TeamCatalogueResponse): Team = Team(
        id = dto.team?.id ?: 0,
        name = dto.team?.name ?: "Unknown",
        shortName = dto.team?.code ?: abbreviate(dto.team?.name),
        crestUrl = dto.team?.logo ?: dto.team?.id?.let(ApiFootballService::teamCrestUrl),
        countryName = dto.team?.country,
        founded = dto.team?.founded,
        venueName = dto.venue?.name,
    )

    fun league(dto: LeagueCatalogueResponse): League? {
        val id = dto.league?.id ?: return null
        val season = dto.seasons.firstOrNull { it.current }?.year
            ?: dto.seasons.maxOfOrNull { it.year }
            ?: currentSeason()
        return League(
            id = id,
            name = dto.league.name ?: "League $id",
            countryName = dto.country?.name,
            logoUrl = dto.league.logo ?: ApiFootballService.leagueLogoUrl(id),
            season = season,
            type = dto.league.type,
        )
    }

    fun match(dto: FixtureResponse): Match {
        val status = dto.fixture.status
        val phase = MatchPhase.fromProviderCode(status?.short)
        return Match(
            id = dto.fixture.id,
            leagueId = dto.league.id,
            leagueName = dto.league.name ?: "",
            leagueLogoUrl = dto.league.logo ?: ApiFootballService.leagueLogoUrl(dto.league.id),
            round = dto.league.round,
            kickoffAt = dto.fixture.timestamp?.let(Instant::ofEpochSecond)
                ?: runCatching { Instant.parse(dto.fixture.date) }.getOrElse { Instant.EPOCH },
            venue = dto.fixture.venue?.name,
            phase = phase,
            elapsedMinutes = status?.elapsed,
            extraMinutes = status?.extra,
            home = team(dto.teams.home),
            away = team(dto.teams.away),
            score = dto.goals?.let { g ->
                if (g.home == null && g.away == null) null else Score(g.home ?: 0, g.away ?: 0)
            },
            halfTimeScore = dto.score?.halftime?.let { s ->
                if (s.home == null && s.away == null) null else Score(s.home ?: 0, s.away ?: 0)
            },
            penaltyScore = dto.score?.penalty?.let { s ->
                if (s.home == null && s.away == null) null else Score(s.home ?: 0, s.away ?: 0)
            },
            referee = dto.fixture.referee,
        )
    }

    /**
     * Events arrive without ids and in provider order. They are re-keyed deterministically
     * and re-scored here so that a later refetch of the same match produces byte-identical
     * ids and the notification layer can diff them.
     */
    fun events(matchId: Long, homeTeamId: Int, dtos: List<EventResponse>): List<MatchEvent> {
        var home = 0
        var away = 0
        // How many incidents of this exact shape have already been seen in this match:
        // the second goal by the same player gets occurrence 1, and stays occurrence 1
        // however often its minute is revised.
        val seen = mutableMapOf<String, Int>()
        return dtos.map { dto ->
            val type = eventType(dto.type, dto.detail)
            val teamId = dto.team?.id
            val side = when (teamId) {
                null -> MatchSide.NEUTRAL
                homeTeamId -> MatchSide.HOME
                else -> MatchSide.AWAY
            }
            if (type.isGoal) {
                // An own goal is credited to the team that did *not* score it - but only
                // when we know which team that was. An unattributed event is NEUTRAL, and
                // `side != HOME` would silently hand it to the home team.
                val creditHome = when {
                    side == MatchSide.NEUTRAL -> null
                    type == MatchEventType.OWN_GOAL -> side != MatchSide.HOME
                    else -> side == MatchSide.HOME
                }
                when (creditHome) {
                    true -> home++
                    false -> away++
                    null -> Unit
                }
            }
            val shape = "${type.name}:${teamId ?: -1}:${dto.player?.name.orEmpty()}"
            val occurrence = seen.getOrDefault(shape, 0)
            seen[shape] = occurrence + 1
            MatchEvent(
                id = MatchEvent.key(matchId, type, occurrence, teamId, dto.player?.name),
                matchId = matchId,
                type = type,
                side = side,
                teamId = teamId,
                teamName = dto.team?.name,
                minute = dto.time?.elapsed,
                extraMinute = dto.time?.extra,
                // For a substitution API-Football puts the player coming ON in `player`
                // and the one going OFF in `assist`.
                playerName = dto.player?.name,
                assistName = dto.assist?.name,
                detail = dto.detail,
                comment = dto.comments,
                scoreAfter = Score(home, away),
            )
        }
    }

    private fun eventType(type: String?, detail: String?): MatchEventType {
        val d = detail?.lowercase().orEmpty()
        return when (type?.lowercase()) {
            "goal" -> when {
                d.contains("own goal") -> MatchEventType.OWN_GOAL
                d.contains("missed penalty") -> MatchEventType.PENALTY_MISSED
                d.contains("penalty") -> MatchEventType.PENALTY_GOAL
                else -> MatchEventType.GOAL
            }
            "card" -> when {
                d.contains("second yellow") || d.contains("2nd yellow") ->
                    MatchEventType.SECOND_YELLOW
                d.contains("red") -> MatchEventType.RED_CARD
                else -> MatchEventType.YELLOW_CARD
            }
            "subst" -> MatchEventType.SUBSTITUTION
            "var" -> MatchEventType.VAR
            else -> MatchEventType.OTHER
        }
    }

    fun lineups(matchId: Long, homeTeamId: Int, dtos: List<LineupResponse>): MatchLineups {
        val byTeam = dtos.associateBy { it.team?.id }
        return MatchLineups(
            matchId = matchId,
            home = byTeam[homeTeamId]?.let(::teamLineup),
            away = byTeam.entries.firstOrNull { it.key != null && it.key != homeTeamId }
                ?.value?.let(::teamLineup),
        )
    }

    private fun teamLineup(dto: LineupResponse) = TeamLineup(
        teamId = dto.team?.id ?: 0,
        teamName = dto.team?.name ?: "",
        crestUrl = dto.team?.logo ?: dto.team?.id?.let(ApiFootballService::teamCrestUrl),
        formation = dto.formation,
        startingXi = dto.startXi.mapNotNull { it.player?.let(::lineupPlayer) },
        substitutes = dto.substitutes.mapNotNull { it.player?.let(::lineupPlayer) },
        coachName = dto.coach?.name,
        shirtColor = dto.team?.colors?.player?.primary?.let { "#$it" },
    )

    private fun lineupPlayer(dto: LineupPlayerDto): LineupPlayer {
        val grid = dto.grid?.split(":")
        return LineupPlayer(
            id = dto.id,
            name = dto.name ?: "",
            number = dto.number,
            position = dto.pos,
            gridRow = grid?.getOrNull(0)?.toIntOrNull(),
            gridColumn = grid?.getOrNull(1)?.toIntOrNull(),
            photoUrl = dto.id?.let(ApiFootballService::playerPhotoUrl),
        )
    }

    fun statistics(
        matchId: Long,
        homeTeamId: Int,
        dtos: List<StatisticsResponse>,
    ): MatchStatistics {
        fun flatten(r: StatisticsResponse?) = r?.statistics.orEmpty()
            .mapNotNull { s -> s.type?.let { t -> s.displayValue?.let { v -> t to v } } }
            .toMap()
        val home = dtos.firstOrNull { it.team?.id == homeTeamId }
        val away = dtos.firstOrNull { it.team?.id != homeTeamId }
        return MatchStatistics(matchId, flatten(home), flatten(away))
    }

    /**
     * European seasons are labelled by the year they start in, so anything before July
     * still belongs to the previous label.
     */
    fun currentSeason(now: Instant = Instant.now()): Int {
        val date = now.atZone(java.time.ZoneOffset.UTC)
        return if (date.monthValue >= 7) date.year else date.year - 1
    }

    private fun abbreviate(name: String?): String {
        if (name.isNullOrBlank()) return "?"
        val words = name.split(' ', '-').filter { it.isNotBlank() }
        return if (words.size >= 2) {
            words.take(3).map { it.first().uppercaseChar() }.joinToString("")
        } else {
            name.take(3).uppercase()
        }
    }

    // ---- players -----------------------------------------------------------------

    /**
     * One entry per player across both sides, keyed by id.
     *
     * The provider nests the stat block in a one-element list even for a single fixture,
     * and a player with no id at all cannot be looked up later, so both are dropped here
     * rather than defended against at every call site.
     */
    fun playersInMatch(teams: List<FixturePlayersResponse>): Map<Int, PlayerMatchStats> =
        teams.asSequence()
            .flatMap { it.players.asSequence() }
            .mapNotNull { entry ->
                val id = entry.player?.id ?: return@mapNotNull null
                val stats = entry.statistics.firstOrNull() ?: return@mapNotNull null
                id to playerStats(stats)
            }
            .toMap()

    private fun playerStats(dto: PlayerFixtureStatsDto) = PlayerMatchStats(
        minutes = dto.games?.minutes,
        number = dto.games?.number,
        position = dto.games?.position,
        rating = dto.games?.rating,
        captain = dto.games?.captain == true,
        startedOnBench = dto.games?.substitute == true,
        goals = dto.goals?.total,
        assists = dto.goals?.assists,
        conceded = dto.goals?.conceded,
        saves = dto.goals?.saves,
        shotsTotal = dto.shots?.total,
        shotsOnTarget = dto.shots?.on,
        passesTotal = dto.passes?.total,
        passesKey = dto.passes?.key,
        passAccuracy = dto.passes?.accuracy,
        tackles = dto.tackles?.total,
        interceptions = dto.tackles?.interceptions,
        duelsTotal = dto.duels?.total,
        duelsWon = dto.duels?.won,
        dribbleAttempts = dto.dribbles?.attempts,
        dribblesSuccessful = dto.dribbles?.success,
        dribblesPast = dto.dribbles?.past,
        foulsDrawn = dto.fouls?.drawn,
        foulsCommitted = dto.fouls?.committed,
        yellowCards = dto.cards?.yellow,
        redCards = dto.cards?.red,
        offsides = dto.offsides,
        penaltiesScored = dto.penalty?.scored,
        penaltiesMissed = dto.penalty?.missed,
        penaltiesSaved = dto.penalty?.saved,
    )

    fun playerProfile(dto: PlayerProfileDto): PlayerProfile = PlayerProfile(
        firstName = dto.firstname,
        lastName = dto.lastname,
        age = dto.age,
        birthDate = dto.birth?.date,
        birthPlace = listOfNotNull(dto.birth?.place, dto.birth?.country)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", "),
        nationality = dto.nationality,
        height = dto.height,
        weight = dto.weight,
        position = dto.position,
        number = dto.number,
    )

    /** A roster row without an id cannot open a sheet or a photo, so it is dropped. */
    fun squadMember(dto: SquadPlayerDto): LineupPlayer? {
        val id = dto.id ?: return null
        return LineupPlayer(
            id = id,
            name = dto.name ?: "Player $id",
            number = dto.number,
            position = dto.position,
            gridRow = null,
            gridColumn = null,
            photoUrl = dto.photo ?: ApiFootballService.playerPhotoUrl(id),
        )
    }
}