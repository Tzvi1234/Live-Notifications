package com.tzvi.kickoff.feature

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.feature.predict.GroupLimits
import com.tzvi.kickoff.feature.predict.GroupSetup
import com.tzvi.kickoff.feature.predict.SetupNoticeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the server answers a 400 for, checked where the user can still act on them.
 */
class GroupSetupTest {

    private fun league(id: Int, name: String = "League $id") =
        League(id = id, name = name, countryName = "England", logoUrl = null, season = 2025)

    private fun team(id: Int, name: String = "Team $id") =
        Team(id = id, name = name, shortName = name.take(3).uppercase(), crestUrl = null)

    private fun setup(count: Int) = GroupSetup(
        name = "Sunday League",
        leagues = (1..count).map { league(it) },
    )

    @Test
    fun `the fifteenth competition is the last one in`() {
        val leagues = (1..GroupLimits.LEAGUES + 1).map { league(it) }
        val full = leagues.take(GroupLimits.LEAGUES)
            .fold(setup(GroupLimits.LEAGUES + 1)) { state, league -> state.toggleLeague(league) }
        assertEquals(GroupLimits.LEAGUES, full.leagueIds.size)

        val refused = full.toggleLeague(leagues.last())
        assertEquals(GroupLimits.LEAGUES, refused.leagueIds.size)
        // Refused out loud: a tap that does nothing and says nothing reads as a dead chip.
        assertEquals(SetupNoticeKind.LEAGUE_CAP, refused.notice?.kind)
        assertTrue(refused.notice?.message?.contains("League 16") == true)
    }

    @Test
    fun `the twentieth team is the last one in`() {
        val squad = (1..GroupLimits.TEAMS + 1).map { team(it) }
        val full = squad.take(GroupLimits.TEAMS).fold(
            GroupSetup(leagueIds = setOf(39), teamsByLeague = mapOf(39 to squad)),
        ) { state, team -> state.toggleTeam(team) }
        assertEquals(GroupLimits.TEAMS, full.selectedTeams.size)

        val refused = full.toggleTeam(squad.last())
        assertEquals(GroupLimits.TEAMS, refused.selectedTeams.size)
        assertEquals(SetupNoticeKind.TEAM_CAP, refused.notice?.kind)
    }

    @Test
    fun `a team an edited group already follows counts towards the cap`() {
        val state = GroupSetup(
            leagueIds = setOf(39),
            pendingTeamIds = (1..GroupLimits.TEAMS).toSet(),
        )
        val refused = state.toggleTeam(team(99))
        assertTrue(refused.selectedTeams.isEmpty())
        assertEquals(SetupNoticeKind.TEAM_CAP, refused.notice?.kind)
    }

    @Test
    fun `the name stops at sixty characters`() {
        val long = "x".repeat(GroupLimits.NAME + 20)
        assertEquals(GroupLimits.NAME, GroupSetup().rename(long).name.length)
    }

    @Test
    fun `taking a competition out takes its clubs with it`() {
        val premier = league(39, "Premier League")
        val bundesliga = league(78, "Bundesliga")
        val arsenal = team(42, "Arsenal")
        val bayern = team(157, "Bayern")
        val state = GroupSetup(
            leagues = listOf(premier, bundesliga),
            leagueIds = setOf(39, 78),
            teamsByLeague = mapOf(39 to listOf(arsenal), 78 to listOf(bayern)),
        ).toggleTeam(arsenal).toggleTeam(bayern)
        assertEquals(setOf(42, 157), state.selectedTeams.keys)

        val without = state.toggleLeague(bundesliga)
        assertEquals(setOf(42), without.selectedTeams.keys)
        assertEquals(setOf(39), without.leagueIds)
        // And says so: a club vanishing from the row with no explanation reads as a bug.
        assertEquals(SetupNoticeKind.TEAMS_DROPPED, without.notice?.kind)
        assertTrue(without.notice?.message?.contains("1 team") == true)
        assertTrue(without.notice?.message?.contains("Bundesliga") == true)
    }

    @Test
    fun `a club two chosen competitions list survives losing one of them`() {
        val premier = league(39, "Premier League")
        val cup = league(45, "FA Cup")
        val arsenal = team(42, "Arsenal")
        val state = GroupSetup(
            leagues = listOf(premier, cup),
            leagueIds = setOf(39, 45),
            teamsByLeague = mapOf(39 to listOf(arsenal), 45 to listOf(arsenal)),
        ).toggleTeam(arsenal)

        val without = state.toggleLeague(cup)
        assertEquals(setOf(42), without.selectedTeams.keys)
        assertNull(without.notice)
    }

    @Test
    fun `the picker lists a club in two competitions once`() {
        val arsenal = team(42, "Arsenal")
        val chelsea = team(49, "Chelsea")
        val state = GroupSetup(
            leagues = listOf(league(39, "Premier League"), league(45, "FA Cup")),
            leagueIds = setOf(39, 45),
            teamsByLeague = mapOf(39 to listOf(arsenal, chelsea), 45 to listOf(arsenal)),
        )
        assertEquals(listOf(42, 49), state.squad.map { it.team.id })
        assertEquals("Premier League", state.squad.first().leagueName)
    }

    @Test
    fun `an edited group's teams become clubs as the squads arrive`() {
        val arsenal = team(42, "Arsenal")
        val bayern = team(157, "Bayern")
        val state = GroupSetup(
            groupId = 7,
            name = "Sunday League",
            leagueIds = setOf(39, 78),
            pendingTeamIds = setOf(42, 157),
        ).withSquad(39, listOf(arsenal))

        assertEquals(setOf(42), state.selectedTeams.keys)
        // The Bundesliga has not answered, so 157 is still a team this group follows and
        // still goes back on the save.
        assertEquals(setOf(157), state.pendingTeamIds)
        assertEquals(listOf(42, 157), state.teamIdsToSave)

        val loaded = state.withSquad(78, listOf(bayern))
        assertEquals(setOf(42, 157), loaded.selectedTeams.keys)
        assertTrue(loaded.pendingTeamIds.isEmpty())
    }

    @Test
    fun `a team no chosen competition lists survives until they have all answered`() {
        val arsenal = team(42, "Arsenal")
        val state = GroupSetup(
            groupId = 7,
            leagueIds = setOf(39, 78),
            pendingTeamIds = setOf(42, 999),
            // Nothing will ever resolve 999, but a competition that failed is not proof
            // of that, so it is only dropped once every squad is in.
            failedLeagueIds = setOf(78),
        ).withSquad(39, listOf(arsenal))
        assertEquals(listOf(42, 999), state.teamIdsToSave)

        val loaded = state.withSquad(78, emptyList())
        assertEquals(listOf(42), loaded.teamIdsToSave)
    }

    @Test
    fun `saving needs a name and at least one team`() {
        val arsenal = team(42, "Arsenal")
        val ready = GroupSetup(
            name = "Sunday League",
            leagueIds = setOf(39),
            teamsByLeague = mapOf(39 to listOf(arsenal)),
        ).toggleTeam(arsenal)
        assertTrue(ready.canSave)
        assertNull(ready.blockedReason)

        assertFalse(ready.rename("  ").canSave)
        assertEquals("Give the group a name", ready.rename("").blockedReason)
        assertEquals("Pick at least one team", ready.removeTeam(42).blockedReason)
        assertFalse(ready.copy(saving = true).canSave)
    }

    @Test
    fun `squads are asked for one at a time, in pick order, and never twice`() {
        val state = GroupSetup(leagueIds = setOf(39, 78, 135))
        assertEquals(39, state.nextSquadLeagueId)

        val first = state.withSquad(39, emptyList())
        assertEquals(78, first.nextSquadLeagueId)

        val failed = first.copy(failedLeagueIds = setOf(78))
        assertEquals(135, failed.nextSquadLeagueId)
        assertNull(failed.withSquad(135, emptyList()).nextSquadLeagueId)
    }
}
