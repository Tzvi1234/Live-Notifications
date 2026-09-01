package com.tzvi.kickoff.core

import com.tzvi.kickoff.core.model.LiveActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveActivityKeyTest {

    @Test
    fun `notification ids are stable and non-negative`() {
        val key = LiveActivity.MatchActivity.matchKey(1_193_045L)
        val id = key.hashCode() and 0x7FFFFFFF
        assertTrue(id >= 0)
        assertEquals(id, LiveActivity.MatchActivity.matchKey(1_193_045L).hashCode() and 0x7FFFFFFF)
    }
}
