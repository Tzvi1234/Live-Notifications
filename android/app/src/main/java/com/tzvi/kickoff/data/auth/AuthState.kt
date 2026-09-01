package com.tzvi.kickoff.data.auth

import com.clerk.api.user.User

/**
 * Where the account layer has got to.
 *
 * [NotConfigured] is a first-class state, not an error: matchUP is a football app that
 * happens to offer accounts, and a build with no publishable key - or one whose backend
 * cannot supply one - has to run exactly as it always did, minus the account features.
 */
sealed interface AuthState {

    /** Resolving the publishable key, or waiting for Clerk to load a stored session. */
    data object Initialising : AuthState

    /** No key from the build, the cache or the backend. Accounts are simply unavailable. */
    data object NotConfigured : AuthState

    data object SignedOut : AuthState

    data class SignedIn(val user: User) : AuthState
}

/** True once the state has settled into something a screen can act on. */
val AuthState.isSettled: Boolean
    get() = this !is AuthState.Initialising

/** What a sign-up or sign-in attempt left the user needing to do next. */
sealed interface AuthOutcome {

    /** A session exists; nothing else is being asked for. */
    data object Complete : AuthOutcome

    /** Clerk sent a code to [email] and wants it back. */
    data class NeedsEmailCode(val email: String) : AuthOutcome

    /**
     * The instance requires attributes this form did not collect.
     *
     * [fields] is Clerk's own `missingFields` list, unedited, so the screen asks for
     * exactly what this instance is configured to want and nothing more.
     */
    data class NeedsFields(val fields: List<String>) : AuthOutcome

    data class Failed(val message: String) : AuthOutcome
}
