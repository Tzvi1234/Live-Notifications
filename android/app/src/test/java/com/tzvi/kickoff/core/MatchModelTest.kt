package com.tzvi.kickoff.core

import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.core.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MatchPhaseTest {

    @Test
    fun `provider codes map onto phases`() {
        assertEquals(MatchPhase.SCHEDULED, MatchPhase.fromProviderCode("NS"))
        assertEquals(MatchPhase.FIRST_HALF, MatchPhase.fromProviderCode("1H"))
        assertEquals(MatchPhase.HALF_TIME, MatchPhase.fromProviderCode("HT"))
        assertEquals(MatchPhase.SECOND_HALF, MatchPhase.fromProviderCode("2H"))
        assertEquals(MatchPhase.EXTRA_TIME, MatchPhase.fromProviderCode("ET"))
        assertEquals(MatchPhase.PENALTIES, MatchPhase.fromProviderCode("P"))
        assertEquals(MatchPhase.FINISHED, MatchPhase.fromProviderCode("FT"))
        assertEquals(MatchPhase.FINISHED, MatchPhase.fromProviderCode("AET"))
        assertEquals(MatchPhase.OFF, MatchPhase.fromProviderCode("PST"))
    }

    @Test
    fun `unknown and null codes do not throw`() {
        assertEquals(MatchPhase.UNKNOWN, MatchPhase.fromProviderCode("WHAT"))
        assertEquals(MatchPhase.UNKNOWN, MatchPhase.fromProviderCode(null))
    }

    @Test
    fun `half time counts as live but full time does not`() {
        assertTrue(MatchPhase.HALF_TIME.isLive)
        assertFalse(MatchPhase.FINISHED.isLive)
        assertFalse(MatchPhase.SCHEDULED.isLive)
    }
}

class MatchClockTest {

    private fun match(
        phase: MatchPhase,
        elapsed: Int? = null,
        extra: Int? = null,
    ) = Match(
        id = 1,
        leagueId = 39,
        leagueName = "Premier League",
        leagueLogoUrl = null,
        round = null,
        kickoffAt = Instant.EPOCH,
        venue = null,
        phase = phase,
        elapsedMinutes = elapsed,
        extraMinutes = extra,
        home = Team(1, "Arsenal", "ARS", null),
        away = Team(2, "Chelsea", "CHE", null),
        score = Score(0, 0),
    )

    @Test
    fun `stoppage time is rendered as 45+2`() {
        assertEquals("45+2'", match(MatchPhase.FIRST_HALF, 45, 2).clockLabel)
    }

    @Test
    fun `zero extra minutes is not shown`() {
        assertEquals("67'", match(MatchPhase.SECOND_HALF, 67, 0).clockLabel)
    }

    @Test
    fun `breaks use their own label rather than a minute`() {
        assertEquals("HT", match(MatchPhase.HALF_TIME, 45).clockLabel)
        assertEquals("FT", match(MatchPhase.FINISHED, 90).clockLabel)
    }

    @Test
    fun `progress is clamped to the regulation track`() {
        // Extra time would otherwise overflow the ProgressStyle bar's 90-minute span.
        assertEquals(90, match(MatchPhase.EXTRA_TIME, 118).progressMinutes)
        assertEquals(0, match(MatchPhase.SCHEDULED).progressMinutes)
        assertEquals(45, match(MatchPhase.HALF_TIME, 45).progressMinutes)
        assertEquals(90, match(MatchPhase.FINISHED, 90).progressMinutes)
    }

    @Test
    fun `a live match with no clock yet does not crash`() {
        assertEquals("", match(MatchPhase.FIRST_HALF, null).clockLabel)
        assertEquals(0, match(MatchPhase.FIRST_HALF, null).progressMinutes)
    }
}

class MatchEventKeyTest {

    @Test
    fun `the same incident always produces the same key`() {
        val a = MatchEvent.key(99, MatchEventType.GOAL, 67, 42, "Saka")
        val b = MatchEvent.key(99, MatchEventType.GOAL, 67, 42, "Saka")
        assertEquals(a, b)
    }

    @Test
    fun `a corrected minute produces a different key`() {
        // The provider re-reports events as minutes are corrected; a changed minute is a
        // different incident as far as de-duplication is concerned, which is deliberate -
        // it is better to alert twice than to miss a genuine second goal.
        val original = MatchEvent.key(99, MatchEventType.GOAL, 67, 42, "Saka")
        val corrected = MatchEvent.key(99, MatchEventType.GOAL, 68, 42, "Saka")
        assertTrue(original != corrected)
    }

    @Test
    fun `missing fields still yield a stable key`() {
        val a = MatchEvent.key(1, MatchEventType.VAR, null, null, null)
        val b = MatchEvent.key(1, MatchEventType.VAR, null, null, null)
        assertEquals(a, b)
        assertEquals("1:VAR:-1:-1:", a)
    }
}

class MatchEventHeadlineTest {

    private fun event(
        type: MatchEventType,
        player: String? = "Saka",
        assist: String? = null,
    ) = MatchEvent(
        id = "x",
        matchId = 1,
        type = type,
        side = MatchSide.HOME,
        teamId = 1,
        teamName = "Arsenal",
        minute = 67,
        extraMinute = null,
        playerName = player,
        assistName = assist,
        detail = null,
    )

    @Test
    fun `a goal names the scorer and the assist`() {
        val headline = event(MatchEventType.GOAL, assist = "Odegaard").headline()
        assertTrue(headline.contains("Saka"))
        assertTrue(headline.contains("Odegaard"))
    }

    @Test
    fun `a penalty is marked as one`() {
        assertTrue(event(MatchEventType.PENALTY_GOAL).headline().contains("(pen)"))
    }

    @Test
    fun `an own goal is marked as one`() {
        assertTrue(event(MatchEventType.OWN_GOAL).headline().contains("OG"))
    }

    @Test
    fun `a missing player name falls back to the team`() {
        val headline = event(MatchEventType.YELLOW_CARD, player = null).headline()
        assertTrue(headline.contains("Arsenal"))
    }

    @Test
    fun `only significant events are allowed to interrupt`() {
        assertTrue(MatchEventType.GOAL.isAlerting)
        assertTrue(MatchEventType.RED_CARD.isAlerting)
        assertFalse(MatchEventType.YELLOW_CARD.isAlerting)
        assertFalse(MatchEventType.SUBSTITUTION.isAlerting)
    }
}

class TeamCodeTest {

    @Test
    fun `a short name is used verbatim`() {
        assertEquals("ARS", Team(1, "Arsenal", "ARS", null).code)
    }

    @Test
    fun `an over-long short name falls back to the first letters of the name`() {
        assertEquals("MAN", Team(1, "Manchester United", "MANUTD", null).code)
    }
}
