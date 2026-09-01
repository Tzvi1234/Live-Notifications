package com.tzvi.kickoff.feature

import com.tzvi.kickoff.feature.auth.AccountAvailability
import com.tzvi.kickoff.feature.auth.AuthStep
import com.tzvi.kickoff.feature.auth.AuthUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules the auth screen draws itself from. */
class AuthStateTest {

    private fun signUp(
        availability: AccountAvailability = AccountAvailability.AVAILABLE,
        email: String = "",
        password: String = "",
        busy: Boolean = false,
        googleBusy: Boolean = false,
    ) = AuthUiState(
        step = AuthStep.SIGN_UP,
        availability = availability,
        email = email,
        password = password,
        busy = busy,
        googleBusy = googleBusy,
    )

    @Test
    fun `google is offered on the front door and nowhere else`() {
        assertTrue(
            AuthUiState(
                step = AuthStep.WELCOME,
                availability = AccountAvailability.AVAILABLE,
            ).googleOffered,
        )
        // Repeating it over the email form is the same button asking the same question
        // twice; choosing email is a choice, and these pages are that choice happening.
        for (step in listOf(AuthStep.SIGN_IN, AuthStep.SIGN_UP, AuthStep.VERIFY, AuthStep.DETAILS)) {
            val state = AuthUiState(step = step, availability = AccountAvailability.AVAILABLE)
            assertFalse("$step should not offer Google", state.googleOffered)
        }
    }

    @Test
    fun `google is not offered when this build has no Clerk instance`() {
        for (availability in listOf(
            AccountAvailability.UNAVAILABLE,
            AccountAvailability.RESOLVING,
        )) {
            assertFalse(AuthUiState(availability = availability).googleOffered)
        }
    }

    @Test
    fun `a Google redirect in flight locks the email form too`() {
        val state = signUp(email = "tzvi@example.com", password = "hunter22", googleBusy = true)
        assertTrue(state.working)
        assertFalse("the form must not submit under an open browser tab", state.canSubmit)
    }

    @Test
    fun `an otherwise valid sign-up can submit`() {
        val state = signUp(email = "tzvi@example.com", password = "hunter22")
        assertTrue(state.canSubmit)
        assertNull(state.blockedReason)
    }

    @Test
    fun `a short password says so, and a busy screen says nothing`() {
        assertEquals(
            "Passwords need at least 8 characters.",
            signUp(email = "tzvi@example.com", password = "short").blockedReason,
        )
        // Mid-flight, the spinner is the message; a validation line under it would be
        // answering a question nobody is asking any more.
        assertNull(signUp(email = "tzvi@example.com", password = "short", busy = true).blockedReason)
        assertNull(
            signUp(email = "tzvi@example.com", password = "short", googleBusy = true)
                .blockedReason,
        )
    }

    @Test
    fun `an address with no at sign is named as the blocker`() {
        assertEquals(
            "That does not look like an email address.",
            signUp(email = "tzvi.example.com", password = "hunter22").blockedReason,
        )
    }
}
