package com.tzvi.kickoff.core

import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.core.model.mayFollowAutomatically
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The rule behind every notification the user did not personally ask for.
 *
 * Pinned by tests because it has gone wrong twice on a real phone: once by adopting
 * arbitrary live matches when nothing was followed, and once by posting a card for a match
 * and cancelling it in the same breath. Both were only visible as "an attack of
 * notifications", which is the worst possible way to find out.
 */
class FollowRulesTest {

    private fun team(id: Int, name: String) =
        Team(id = id, name = name, shortName = name.take(3), crestUrl = null)

    private fun match(homeId: Int, awayId: Int) = Match(
        id = 1,
        leagueId = 39,
        leagueName = "Premier League",
        leagueLogoUrl = null,
        round = "Regular Season - 1",
        kickoffAt = Instant.parse("2026-09-01T18:00:00Z"),
        venue = null,
        phase = MatchPhase.FIRST_HALF,
        elapsedMinutes = 20,
        extraMinutes = null,
        home = team(homeId, "Home"),
        away = team(awayId, "Away"),
        score = null,
    )

    @Test
    fun `a followed home side is enough`() {
        assertTrue(mayFollowAutomatically(match(homeId = 42, awayId = 77), listOf(42)))
    }

    @Test
    fun `a followed away side is enough`() {
        // The away half matters as much as the home one: following a club means following
        // it everywhere, not only at home.
        assertTrue(mayFollowAutomatically(match(homeId = 42, awayId = 77), listOf(77)))
    }

    @Test
    fun `a match between two strangers is never followed`() {
        assertFalse(mayFollowAutomatically(match(homeId = 42, awayId = 77), listOf(1, 2, 3)))
    }

    @Test
    fun `following nothing means being notified about nothing`() {
        // THE regression. Both data sources treat an empty team list as "do not filter" -
        // the direct provider returns every in-play match on earth and the backend drops
        // the query parameter - so an empty favourites list used to mean the sweep adopted
        // five arbitrary matches and posted cards for clubs the user had never heard of.
        // A fresh install is exactly this state, which is how it reached a real phone.
        assertFalse(mayFollowAutomatically(match(homeId = 42, awayId = 77), emptyList()))
    }

    @Test
    fun `an empty list is not treated as a wildcard whichever teams are playing`() {
        for (ids in listOf(1 to 2, 42 to 77, 0 to 0)) {
            assertFalse(
                "no favourites must never authorise ${ids.first} v ${ids.second}",
                mayFollowAutomatically(match(ids.first, ids.second), emptySet()),
            )
        }
    }

    @Test
    fun `the check works on any collection, not just a list`() {
        // The sweep worker holds a Set and the service holds a List; the rule must not
        // care, or one of the two call sites quietly gets a different answer.
        assertTrue(mayFollowAutomatically(match(homeId = 42, awayId = 77), setOf(42, 99)))
        assertFalse(mayFollowAutomatically(match(homeId = 42, awayId = 77), setOf(99)))
    }
}
