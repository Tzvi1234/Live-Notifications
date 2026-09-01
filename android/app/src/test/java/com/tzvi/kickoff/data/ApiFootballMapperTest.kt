package com.tzvi.kickoff.data

import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.data.remote.ApiFootballMapper
import com.tzvi.kickoff.data.remote.dto.ApiEnvelope
import com.tzvi.kickoff.data.remote.dto.EventResponse
import com.tzvi.kickoff.data.remote.dto.EventTimeDto
import com.tzvi.kickoff.data.remote.dto.PlayerRefDto
import com.tzvi.kickoff.data.remote.dto.TeamRefDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private const val HOME = 42
private const val AWAY = 77

class ApiFootballEventMappingTest {

    private fun goal(team: Int, minute: Int, player: String, detail: String = "Normal Goal") =
        EventResponse(
            time = EventTimeDto(elapsed = minute),
            team = TeamRefDto(id = team, name = "T$team"),
            player = PlayerRefDto(id = 1, name = player),
            type = "Goal",
            detail = detail,
        )

    @Test
    fun `the running scoreline is accumulated across events`() {
        val events = ApiFootballMapper.events(
            matchId = 1,
            homeTeamId = HOME,
            dtos = listOf(
                goal(HOME, 10, "A"),
                goal(AWAY, 30, "B"),
                goal(HOME, 70, "C"),
            ),
        )
        assertEquals("1-0", events[0].scoreAfter.toString())
        assertEquals("1-1", events[1].scoreAfter.toString())
        assertEquals("2-1", events[2].scoreAfter.toString())
    }

    @Test
    fun `an own goal is credited to the other side`() {
        // The provider attributes an own goal to the team that scored it, but the goal
        // belongs on the opponent's side of the scoreboard.
        val events = ApiFootballMapper.events(
            matchId = 1,
            homeTeamId = HOME,
            dtos = listOf(goal(HOME, 25, "Unlucky", detail = "Own Goal")),
        )
        assertEquals(MatchEventType.OWN_GOAL, events[0].type)
        assertEquals(MatchSide.HOME, events[0].side)
        assertEquals("0-1", events[0].scoreAfter.toString())
    }

    @Test
    fun `a missed penalty is not a goal`() {
        val events = ApiFootballMapper.events(
            matchId = 1,
            homeTeamId = HOME,
            dtos = listOf(goal(HOME, 55, "X", detail = "Missed Penalty")),
        )
        assertEquals(MatchEventType.PENALTY_MISSED, events[0].type)
        assertEquals("0-0", events[0].scoreAfter.toString())
    }

    @Test
    fun `card details map onto the right severities`() {
        fun card(detail: String) = EventResponse(
            time = EventTimeDto(elapsed = 40),
            team = TeamRefDto(id = HOME),
            player = PlayerRefDto(name = "P"),
            type = "Card",
            detail = detail,
        )
        val events = ApiFootballMapper.events(
            1, HOME,
            listOf(card("Yellow Card"), card("Second Yellow card"), card("Red Card")),
        )
        assertEquals(MatchEventType.YELLOW_CARD, events[0].type)
        assertEquals(MatchEventType.SECOND_YELLOW, events[1].type)
        assertEquals(MatchEventType.RED_CARD, events[2].type)
    }

    @Test
    fun `mapping the same payload twice yields identical ids`() {
        val payload = listOf(goal(HOME, 10, "A"), goal(AWAY, 30, "B"))
        val first = ApiFootballMapper.events(1, HOME, payload).map { it.id }
        val second = ApiFootballMapper.events(1, HOME, payload).map { it.id }
        assertEquals(first, second)
    }

    @Test
    fun `an event from an unknown team is neutral rather than away`() {
        val events = ApiFootballMapper.events(
            1, HOME,
            listOf(EventResponse(time = EventTimeDto(elapsed = 5), type = "Var", detail = "Goal cancelled")),
        )
        assertEquals(MatchSide.NEUTRAL, events[0].side)
        assertEquals(MatchEventType.VAR, events[0].type)
    }
}

class ApiFootballSeasonTest {

    @Test
    fun `a season is labelled by the year it started in`() {
        // European seasons run August to May, so anything before July still belongs to
        // the previous label - getting this wrong returns an empty fixture list.
        assertEquals(2026, ApiFootballMapper.currentSeason(Instant.parse("2026-08-15T00:00:00Z")))
        assertEquals(2025, ApiFootballMapper.currentSeason(Instant.parse("2026-03-15T00:00:00Z")))
        assertEquals(2026, ApiFootballMapper.currentSeason(Instant.parse("2026-07-01T00:00:00Z")))
        assertEquals(2025, ApiFootballMapper.currentSeason(Instant.parse("2026-06-30T00:00:00Z")))
    }
}

class ApiEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `an empty errors array means success`() {
        val envelope = ApiEnvelope<String>(errors = JsonArray(emptyList()))
        assertNull(envelope.errorMessage)
    }

    @Test
    fun `an errors object is surfaced even though the status was 200`() {
        // This is the trap: API-Football answers auth and quota failures with HTTP 200
        // and describes the problem in the body.
        val envelope = ApiEnvelope<String>(
            errors = buildJsonObject { put("token", JsonPrimitive("Invalid API key")) },
        )
        val message = envelope.errorMessage
        assertNotNull(message)
        assertTrue(message!!.contains("Invalid API key"))
    }

    @Test
    fun `a null errors field means success`() {
        assertNull(ApiEnvelope<String>(errors = null).errorMessage)
    }

    @Test
    fun `the envelope survives an unexpected errors shape`() {
        val parsed = json.decodeFromString<ApiEnvelope<String>>(
            """{"errors":"rate limit reached","results":0,"response":[]}""",
        )
        assertEquals("rate limit reached", parsed.errorMessage)
    }
}
