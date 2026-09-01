package com.tzvi.kickoff.data

import com.tzvi.kickoff.data.remote.ApiFootballMapper
import com.tzvi.kickoff.data.remote.dto.CoverageDto
import com.tzvi.kickoff.data.remote.dto.FixtureCoverageDto
import com.tzvi.kickoff.data.remote.dto.LeagueCatalogueResponse
import com.tzvi.kickoff.data.remote.dto.LeagueInfoDto
import com.tzvi.kickoff.data.remote.dto.PredictionBlockDto
import com.tzvi.kickoff.data.remote.dto.PredictionPercentDto
import com.tzvi.kickoff.data.remote.dto.PredictionResponse
import com.tzvi.kickoff.data.remote.dto.SeasonDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeagueCoverageMappingTest {

    private fun league(seasons: List<SeasonDto>) = LeagueCatalogueResponse(
        league = LeagueInfoDto(id = 45, name = "FA Cup", type = "Cup"),
        seasons = seasons,
    )

    @Test
    fun `coverage is read from the current season, not the newest one`() {
        val mapped = ApiFootballMapper.league(
            league(
                listOf(
                    SeasonDto(
                        year = 2030,
                        coverage = CoverageDto(fixtures = FixtureCoverageDto(lineups = false)),
                    ),
                    SeasonDto(
                        year = 2025,
                        current = true,
                        coverage = CoverageDto(
                            fixtures = FixtureCoverageDto(lineups = true, events = true),
                            predictions = true,
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2025, mapped?.season)
        assertTrue(mapped?.coverage?.lineups == true)
        assertTrue(mapped?.coverage?.predictions == true)
    }

    @Test
    fun `a competition that carries no line-ups says so`() {
        val mapped = ApiFootballMapper.league(
            league(
                listOf(
                    SeasonDto(
                        year = 2025,
                        current = true,
                        coverage = CoverageDto(fixtures = FixtureCoverageDto(events = true)),
                    ),
                ),
            ),
        )
        // This is the whole point of reading coverage: "not published yet" and "this cup
        // never publishes one" were the same empty array before.
        assertFalse(mapped?.coverage?.lineups == true)
        assertTrue(mapped?.coverage?.events == true)
    }

    @Test
    fun `no coverage block at all stays optimistic`() {
        val mapped = ApiFootballMapper.league(league(listOf(SeasonDto(year = 2025, current = true))))
        // Absent means "we were not told", and announcing a limitation nobody stated is
        // worse than waiting for the API to state one.
        assertTrue(mapped?.coverage?.lineups == true)
        assertTrue(mapped?.coverage?.predictions == true)
    }
}

class PredictionMappingTest {

    @Test
    fun `percent strings become numbers`() {
        val mapped = ApiFootballMapper.prediction(
            PredictionResponse(
                predictions = PredictionBlockDto(
                    percent = PredictionPercentDto(home = "45%", draw = "30%", away = "25%"),
                    advice = "Double chance : Arsenal or draw",
                ),
            ),
        )
        assertEquals(45, mapped?.homePercent)
        assertEquals(30, mapped?.drawPercent)
        assertEquals(25, mapped?.awayPercent)
        assertEquals("Double chance : Arsenal or draw", mapped?.advice)
    }

    @Test
    fun `a prediction with nothing in it is null rather than an empty card`() {
        val mapped = ApiFootballMapper.prediction(
            PredictionResponse(predictions = PredictionBlockDto()),
        )
        assertNull(mapped)
    }
}
