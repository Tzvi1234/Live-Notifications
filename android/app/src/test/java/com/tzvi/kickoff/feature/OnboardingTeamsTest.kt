package com.tzvi.kickoff.feature

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.feature.onboarding.TeamOption
import com.tzvi.kickoff.feature.onboarding.onceEach
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The team picker's one invariant: a club appears once, however many competitions list it.
 *
 * Pinned because it crashed a real phone on every attempt. The rows are keyed on the team
 * id and Compose throws on a duplicate key, so a club in both a league and its cup - which
 * is every English and Israeli club, once the catalogue carried the cups - took the whole
 * step down the moment both competitions were chosen.
 */
class OnboardingTeamsTest {

    private fun league(id: Int, name: String) =
        League(id = id, name = name, countryName = "England", logoUrl = null, season = 2025)

    private fun team(id: Int, name: String) =
        Team(id = id, name = name, shortName = name.take(3).uppercase(), crestUrl = null)

    private val premierLeague = league(39, "Premier League")
    private val faCup = league(45, "FA Cup")

    @Test
    fun `a club in a league and its cup is one row`() {
        val arsenal = team(42, "Arsenal")
        val listed = listOf(
            TeamOption(arsenal, premierLeague),
            TeamOption(team(33, "Manchester United"), premierLeague),
            TeamOption(arsenal, faCup),
            TeamOption(team(1359, "Luton"), faCup),
        )

        val rows = listed.onceEach()

        assertEquals(listOf(42, 1359, 33), rows.map { it.team.id })
        assertEquals("no id may repeat", rows.size, rows.map { it.team.id }.toSet().size)
    }

    @Test
    fun `the first competition a club was seen in is the one it is labelled with`() {
        val arsenal = team(42, "Arsenal")
        val rows = listOf(TeamOption(arsenal, premierLeague), TeamOption(arsenal, faCup)).onceEach()
        assertEquals(premierLeague, rows.single().league)
    }

    @Test
    fun `the list is in name order`() {
        val rows = listOf(
            TeamOption(team(3, "Wolves"), premierLeague),
            TeamOption(team(1, "Arsenal"), premierLeague),
            TeamOption(team(2, "Chelsea"), faCup),
        ).onceEach()
        assertEquals(listOf("Arsenal", "Chelsea", "Wolves"), rows.map { it.team.name })
    }

    @Test
    fun `nothing in means nothing out`() {
        assertEquals(emptyList<TeamOption>(), emptyList<TeamOption>().onceEach())
    }
}
