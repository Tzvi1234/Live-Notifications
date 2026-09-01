package com.tzvi.kickoff.data.demo

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
import java.time.Duration
import java.time.Instant

/**
 * A believable season, generated on the spot.
 *
 * Real clubs and their real crests - the images come from the provider's public CDN, the
 * same one the live app uses, so the demo exercises the actual image pipeline rather than
 * a bundled placeholder. Fixture times are relative to *now*, so there is always exactly
 * one match in play, one about to kick off, and a few results behind you, whenever the app
 * happens to be opened.
 *
 * Ids match the provider's, which keeps the demo and the real thing on one set of keys and
 * means a crest URL built here is byte-identical to one built from a live response.
 */
object DemoCatalogue {

    private fun club(id: Int, name: String, code: String, country: String, venue: String) =
        Team(
            id = id,
            name = name,
            shortName = code,
            crestUrl = ApiFootballService.teamCrestUrl(id),
            countryName = country,
            venueName = venue,
        )

    val arsenal = club(42, "Arsenal", "ARS", "England", "Emirates Stadium")
    val chelsea = club(49, "Chelsea", "CHE", "England", "Stamford Bridge")
    val liverpool = club(40, "Liverpool", "LIV", "England", "Anfield")
    val manCity = club(50, "Manchester City", "MCI", "England", "Etihad Stadium")
    val manUnited = club(33, "Manchester United", "MUN", "England", "Old Trafford")
    val tottenham = club(47, "Tottenham", "TOT", "England", "Tottenham Hotspur Stadium")
    val barcelona = club(529, "Barcelona", "BAR", "Spain", "Spotify Camp Nou")
    val realMadrid = club(541, "Real Madrid", "RMA", "Spain", "Santiago Bernabéu")
    val atletico = club(530, "Atlético Madrid", "ATM", "Spain", "Cívitas Metropolitano")
    val bayern = club(157, "Bayern München", "BAY", "Germany", "Allianz Arena")
    val dortmund = club(165, "Borussia Dortmund", "BVB", "Germany", "Signal Iduna Park")
    val juventus = club(496, "Juventus", "JUV", "Italy", "Allianz Stadium")
    val inter = club(505, "Inter", "INT", "Italy", "San Siro")
    val milan = club(489, "AC Milan", "MIL", "Italy", "San Siro")
    val psg = club(85, "Paris Saint-Germain", "PSG", "France", "Parc des Princes")

    val teams: List<Team> = listOf(
        arsenal, chelsea, liverpool, manCity, manUnited, tottenham,
        barcelona, realMadrid, atletico, bayern, dortmund,
        juventus, inter, milan, psg,
    )

    private fun league(id: Int, name: String, country: String) = League(
        id = id,
        name = name,
        countryName = country,
        logoUrl = ApiFootballService.leagueLogoUrl(id),
        season = 2026,
        type = "League",
    )

    val premierLeague = league(39, "Premier League", "England")
    val laLiga = league(140, "La Liga", "Spain")
    val serieA = league(135, "Serie A", "Italy")
    val bundesliga = league(78, "Bundesliga", "Germany")
    val ligue1 = league(61, "Ligue 1", "France")
    val championsLeague = league(2, "UEFA Champions League", "World")

    val leagues: List<League> = listOf(
        premierLeague, laLiga, serieA, bundesliga, ligue1, championsLeague,
    )

    private val leagueByTeam: Map<Int, League> = buildMap {
        listOf(arsenal, chelsea, liverpool, manCity, manUnited, tottenham)
            .forEach { put(it.id, premierLeague) }
        listOf(barcelona, realMadrid, atletico).forEach { put(it.id, laLiga) }
        listOf(juventus, inter, milan).forEach { put(it.id, serieA) }
        listOf(bayern, dortmund).forEach { put(it.id, bundesliga) }
        put(psg.id, ligue1)
    }

    fun leagueFor(team: Team): League = leagueByTeam[team.id] ?: premierLeague

    fun teamsIn(leagueId: Int): List<Team> =
        teams.filter { leagueByTeam[it.id]?.id == leagueId }

    /** Negative ids: a demo fixture can never be confused with a real one. */
    const val LIVE_MATCH_ID = -101L
    const val PRE_MATCH_ID = -102L

    /**
     * The whole fixture list, anchored to [now].
     *
     * Offsets rather than dates: the demo has to look right whether it is opened on a
     * Tuesday morning or during a Saturday afternoon of real football.
     */
    fun fixtures(now: Instant = Instant.now()): List<Match> = listOf(
        // In play: the one the live card and the island are showing.
        fixture(
            id = LIVE_MATCH_ID, home = arsenal, away = chelsea,
            kickoff = now.minus(Duration.ofMinutes(72)),
            phase = MatchPhase.SECOND_HALF, elapsed = 67, score = Score(2, 1),
            halfTime = Score(1, 1), round = "Matchweek 24",
        ),
        // Inside the pre-match window, so the countdown card is reachable immediately.
        fixture(
            id = PRE_MATCH_ID, home = barcelona, away = realMadrid,
            kickoff = now.plus(Duration.ofMinutes(41)),
            phase = MatchPhase.SCHEDULED, round = "Matchweek 24",
        ),
        fixture(
            id = -103L, home = manCity, away = liverpool,
            kickoff = now.plus(Duration.ofHours(3)), phase = MatchPhase.SCHEDULED,
            round = "Matchweek 24",
        ),
        fixture(
            id = -104L, home = bayern, away = dortmund,
            kickoff = now.plus(Duration.ofHours(27)), phase = MatchPhase.SCHEDULED,
            round = "Matchday 21",
        ),
        fixture(
            id = -105L, home = inter, away = milan,
            kickoff = now.plus(Duration.ofHours(30)), phase = MatchPhase.SCHEDULED,
            round = "Giornata 23",
        ),
        fixture(
            id = -106L, home = juventus, away = psg,
            kickoff = now.plus(Duration.ofDays(2)), phase = MatchPhase.SCHEDULED,
            round = "Round of 16", league = championsLeague,
        ),
        fixture(
            id = -107L, home = atletico, away = barcelona,
            kickoff = now.plus(Duration.ofDays(4)), phase = MatchPhase.SCHEDULED,
            round = "Matchweek 25",
        ),
        // Behind you, so the fixture list is not all future.
        fixture(
            id = -108L, home = manUnited, away = tottenham,
            kickoff = now.minus(Duration.ofDays(1)),
            phase = MatchPhase.FINISHED, elapsed = 90, score = Score(1, 3),
            halfTime = Score(0, 2), round = "Matchweek 23",
        ),
        fixture(
            id = -109L, home = realMadrid, away = atletico,
            kickoff = now.minus(Duration.ofDays(2)),
            phase = MatchPhase.FINISHED, elapsed = 90, score = Score(2, 2),
            halfTime = Score(1, 0), round = "Matchweek 23",
        ),
    )

    private fun fixture(
        id: Long,
        home: Team,
        away: Team,
        kickoff: Instant,
        phase: MatchPhase,
        elapsed: Int? = null,
        score: Score? = null,
        halfTime: Score? = null,
        round: String? = null,
        league: League = leagueFor(home),
    ) = Match(
        id = id,
        leagueId = league.id,
        leagueName = league.name,
        leagueLogoUrl = league.logoUrl,
        round = round,
        kickoffAt = kickoff,
        venue = home.venueName,
        phase = phase,
        elapsedMinutes = elapsed,
        extraMinutes = null,
        home = home,
        away = away,
        score = score,
        halfTimeScore = halfTime,
        referee = "M. Oliver",
    )

    fun match(id: Long, now: Instant = Instant.now()): Match? =
        fixtures(now).firstOrNull { it.id == id }

    // ---- events ------------------------------------------------------------

    /** The live match's story so far, as a fixed script so the timeline stays coherent. */
    fun liveEvents(): List<MatchEvent> = listOf(
        event(LIVE_MATCH_ID, MatchEventType.GOAL, MatchSide.HOME, 23, "Saka", "Ødegaard", Score(1, 0)),
        event(LIVE_MATCH_ID, MatchEventType.YELLOW_CARD, MatchSide.AWAY, 34, "Fofana", null, Score(1, 0)),
        event(LIVE_MATCH_ID, MatchEventType.GOAL, MatchSide.AWAY, 41, "Palmer", "Neto", Score(1, 1)),
        event(LIVE_MATCH_ID, MatchEventType.SUBSTITUTION, MatchSide.HOME, 58, "Trossard", "Martinelli", Score(1, 1)),
        event(LIVE_MATCH_ID, MatchEventType.GOAL, MatchSide.HOME, 67, "Havertz", "Rice", Score(2, 1)),
    )

    fun event(
        matchId: Long,
        type: MatchEventType,
        side: MatchSide,
        minute: Int,
        player: String,
        assist: String?,
        score: Score,
    ): MatchEvent {
        val match = match(matchId)
        val teamId = if (side == MatchSide.HOME) match?.home?.id else match?.away?.id
        return MatchEvent(
            id = MatchEvent.key(matchId, type, minute, teamId, player),
            matchId = matchId,
            type = type,
            side = side,
            teamId = teamId,
            teamName = if (side == MatchSide.HOME) match?.home?.name else match?.away?.name,
            minute = minute,
            extraMinute = null,
            playerName = player,
            assistName = assist,
            detail = null,
            scoreAfter = score,
        )
    }

    // ---- line-ups and stats -------------------------------------------------

    fun lineups(matchId: Long): MatchLineups {
        val match = match(matchId)
        return MatchLineups(
            matchId = matchId,
            home = squadFor(match?.home ?: arsenal),
            away = squadFor(match?.away ?: chelsea),
        )
    }

    private val squads: Map<Int, Pair<String, List<String>>> = mapOf(
        42 to ("4-3-3" to listOf("Raya", "White", "Saliba", "Gabriel", "Timber", "Rice", "Ødegaard", "Merino", "Saka", "Havertz", "Martinelli")),
        49 to ("4-2-3-1" to listOf("Sánchez", "Gusto", "Fofana", "Colwill", "Cucurella", "Caicedo", "Fernández", "Palmer", "Neto", "Madueke", "Jackson")),
        529 to ("4-3-3" to listOf("Ter Stegen", "Koundé", "Cubarsí", "Íñigo", "Balde", "Pedri", "De Jong", "Gavi", "Yamal", "Lewandowski", "Raphinha")),
        541 to ("4-3-1-2" to listOf("Courtois", "Carvajal", "Rüdiger", "Alaba", "Mendy", "Valverde", "Tchouaméni", "Camavinga", "Bellingham", "Vinícius", "Mbappé")),
        40 to ("4-3-3" to listOf("Alisson", "Bradley", "Konaté", "Van Dijk", "Robertson", "Gravenberch", "Mac Allister", "Szoboszlai", "Salah", "Gakpo", "Díaz")),
        50 to ("4-2-3-1" to listOf("Ederson", "Walker", "Dias", "Gvardiol", "Aké", "Rodri", "Kovačić", "Foden", "De Bruyne", "Doku", "Haaland")),
        157 to ("4-2-3-1" to listOf("Neuer", "Kimmich", "Upamecano", "Kim", "Davies", "Goretzka", "Pavlović", "Sané", "Musiala", "Olise", "Kane")),
        165 to ("4-2-3-1" to listOf("Kobel", "Ryerson", "Hummels", "Schlotterbeck", "Maatsen", "Can", "Sabitzer", "Adeyemi", "Brandt", "Sancho", "Füllkrug")),
        505 to ("3-5-2" to listOf("Sommer", "Pavard", "Acerbi", "Bastoni", "Dumfries", "Barella", "Çalhanoğlu", "Mkhitaryan", "Dimarco", "Lautaro", "Thuram")),
        489 to ("4-2-3-1" to listOf("Maignan", "Calabria", "Tomori", "Thiaw", "Hernández", "Reijnders", "Fofana", "Pulisic", "Loftus-Cheek", "Leão", "Giroud")),
        496 to ("3-5-2" to listOf("Szczęsny", "Danilo", "Bremer", "Gatti", "Cambiaso", "McKennie", "Locatelli", "Rabiot", "Kostić", "Vlahović", "Chiesa")),
        85 to ("4-3-3" to listOf("Donnarumma", "Hakimi", "Marquinhos", "Beraldo", "Mendes", "Vitinha", "Ruiz", "Zaïre-Emery", "Dembélé", "Ramos", "Barcola")),
        33 to ("4-2-3-1" to listOf("Onana", "Dalot", "Varane", "Martínez", "Shaw", "Casemiro", "Mainoo", "Antony", "Fernandes", "Rashford", "Højlund")),
        47 to ("4-3-3" to listOf("Vicario", "Porro", "Romero", "Van de Ven", "Udogie", "Bissouma", "Sarr", "Maddison", "Kulusevski", "Solanke", "Son")),
        530 to ("5-3-2" to listOf("Oblak", "Molina", "Giménez", "Witsel", "Hermoso", "Lino", "Llorente", "De Paul", "Koke", "Griezmann", "Morata")),
    )

    private fun squadFor(team: Team): TeamLineup {
        val (formation, names) = squads[team.id] ?: ("4-4-2" to List(11) { "Player ${it + 1}" })
        // Grid rows follow the provider's convention: row 1 is the keeper, rising forward.
        val rows = listOf(1, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4)
        var column = 0
        var lastRow = 0
        val xi = names.mapIndexed { index, name ->
            val row = rows.getOrElse(index) { 4 }
            column = if (row == lastRow) column + 1 else 1
            lastRow = row
            LineupPlayer(
                id = null, name = name, number = index + 1,
                position = null, gridRow = row, gridColumn = column,
            )
        }
        return TeamLineup(
            teamId = team.id,
            teamName = team.name,
            crestUrl = team.crestUrl,
            formation = formation,
            startingXi = xi,
            substitutes = emptyList(),
            coachName = null,
        )
    }

    fun statistics(matchId: Long) = MatchStatistics(
        matchId = matchId,
        home = mapOf(
            MatchStatistics.POSSESSION to "58%",
            MatchStatistics.SHOTS to "14",
            MatchStatistics.SHOTS_ON_GOAL to "6",
            MatchStatistics.FOULS to "9",
            MatchStatistics.CORNERS to "7",
            MatchStatistics.XG to "1.84",
        ),
        away = mapOf(
            MatchStatistics.POSSESSION to "42%",
            MatchStatistics.SHOTS to "8",
            MatchStatistics.SHOTS_ON_GOAL to "3",
            MatchStatistics.FOULS to "13",
            MatchStatistics.CORNERS to "3",
            MatchStatistics.XG to "0.96",
        ),
    )
}
