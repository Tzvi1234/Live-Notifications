package com.tzvi.kickoff.data

import com.tzvi.kickoff.data.repository.looksLikeBackendHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendHealthProbeTest {

    @Test
    fun `the deployed health body is recognised`() {
        // Byte-for-byte what https://kickoff-api-tato.onrender.com/v1/health returns. The
        // probe used to look for a "status" key that this route has never sent, so a
        // correctly deployed backend was rejected during onboarding as "not a matchUP
        // backend" - and the address in the box was right the whole time.
        assertTrue(
            looksLikeBackendHealth(
                """{"ok":true,"version":"1.0.0","provider":"api-football","pollingEnabled":true}""",
            ),
        )
    }

    @Test
    fun `a newer body with extra fields still passes`() {
        assertTrue(
            looksLikeBackendHealth(
                """{"ok":true,"version":"1.1.0","provider":"api-football","pollingEnabled":true,""" +
                    """"quota":{"dailyLimit":7500,"dailyRemaining":7100}}""",
            ),
        )
    }

    @Test
    fun `something else answering 200 is rejected`() {
        assertFalse(looksLikeBackendHealth("""{"service":"kickoff","health":"/v1/health"}"""))
        assertFalse(looksLikeBackendHealth("""{"ok":true}"""))
        assertFalse(looksLikeBackendHealth("""{"ok":false,"provider":"api-football"}"""))
        assertFalse(looksLikeBackendHealth("<html><body>Parked domain</body></html>"))
        assertFalse(looksLikeBackendHealth(""))
    }
}
