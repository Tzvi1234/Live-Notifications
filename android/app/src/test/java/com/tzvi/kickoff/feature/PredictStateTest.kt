package com.tzvi.kickoff.feature

import com.tzvi.kickoff.core.model.GroupFixture
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.PredictionEntry
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.feature.predict.PredictUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PredictStateTest {

    private fun fixture(
        matchId: Long = 1,
        phase: MatchPhase = MatchPhase.SCHEDULED,
        locked: Boolean = false,
        mine: PredictionEntry? = null,
    ) = GroupFixture(
        match = Match(
            id = matchId,
            leagueId = 39,
            leagueName = "Premier League",
            leagueLogoUrl = null,
            round = null,
            kickoffAt = Instant.now().plusSeconds(3_600),
            venue = null,
            phase = phase,
            elapsedMinutes = null,
            extraMinutes = null,
            home = Team(42, "Arsenal", "ARS", null),
            away = Team(49, "Chelsea", "CHE", null),
            score = null,
        ),
        locked = locked,
        myPrediction = mine,
        others = emptyList(),
    )

    private fun entry(home: Int, away: Int) =
        PredictionEntry("u1", "Tzvi", null, home, away, null)

    @Test
    fun `an unsent draft wins over what was sent`() {
        val f = fixture(mine = entry(1, 1))
        val state = PredictUiState(fixtures = listOf(f), drafts = mapOf(1L to (2 to 0)))
        assertEquals(2 to 0, state.draftFor(f))
        assertTrue(state.isDirty(f))
    }

    @Test
    fun `a draft equal to what was sent is not dirty`() {
        val f = fixture(mine = entry(2, 1))
        val state = PredictUiState(fixtures = listOf(f), drafts = mapOf(1L to (2 to 1)))
        // Otherwise the commit button stays lit after a successful send and invites a
        // pointless second write.
        assertFalse(state.isDirty(f))
    }

    @Test
    fun `nothing sent and nothing drafted starts at nil-nil`() {
        val f = fixture()
        assertEquals(0 to 0, PredictUiState(fixtures = listOf(f)).draftFor(f))
    }

    @Test
    fun `a kicked-off fixture is closed`() {
        assertTrue(fixture().isOpen)
        assertFalse(fixture(phase = MatchPhase.FIRST_HALF).isOpen)
        assertFalse(fixture(phase = MatchPhase.FINISHED).isOpen)
        // The server locks it at kick-off; the client must never re-derive that from a
        // clock it does not control.
        assertFalse(fixture(locked = true).isOpen)
    }

    @Test
    fun `the live match is the one the table sits under`() {
        val state = PredictUiState(
            fixtures = listOf(
                fixture(matchId = 1),
                fixture(matchId = 2, phase = MatchPhase.SECOND_HALF),
                fixture(matchId = 3, phase = MatchPhase.FINISHED),
            ),
        )
        assertEquals(2L, state.liveFixture?.matchId)
        assertEquals(listOf(1L), state.openFixtures.map { it.matchId })
        assertEquals(listOf(2L, 3L), state.settledFixtures.map { it.matchId })
    }
}
