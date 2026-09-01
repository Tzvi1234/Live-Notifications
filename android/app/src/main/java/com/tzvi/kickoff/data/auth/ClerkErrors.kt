package com.tzvi.kickoff.data.auth

import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.network.serialization.errorMessage
import com.clerk.api.network.serialization.shortErrorMessageOrNull

/**
 * What went wrong, in Clerk's own words.
 *
 * `errorMessage` prefers the API's long message and falls back to a connectivity-aware
 * line, so "that email address is taken already" reaches the screen intact instead of
 * being flattened into a shrug. The HTTP status is appended only when Clerk itself had
 * nothing to say - beside a real sentence it is noise.
 */
internal val ClerkResult.Failure<ClerkErrorResponse>.readableMessage: String
    get() {
        val message = errorMessage
        val status = code

        // `errorMessage` reads only the parsed ClerkErrorResponse and never the throwable.
        // A whole class of failures - every one raised by the SSO bridge, which arrive as
        // `unknownFailure(throwable)` with a null error and a null code - therefore came
        // out as the literal "Error occurred with unknown message." while the throwable
        // sitting right beside it carried Clerk's own sentence. That is how a Google
        // sign-in could fail and say nothing anybody could act on.
        if (error == null && code == null) {
            val fromThrowable = throwable?.message?.trim().orEmpty()
            if (fromThrowable.isNotBlank()) return explain(fromThrowable)
        }

        return if (shortErrorMessageOrNull() == null && status != null) {
            "$message (HTTP $status)"
        } else {
            message
        }
    }

/**
 * Clerk's machine-readable codes, in words.
 *
 * `external_account_exists` is the one worth naming: it is what an instance answers when
 * the Google address already belongs to an account created with a password, and Clerk's
 * SSO service does not treat it as a transferable sign-up - it aborts the flow. Left as
 * its raw code it reads as a bug in the app rather than as the two-line explanation it is.
 */
internal fun explain(reason: String): String = when {
    reason.contains("external_account_exists", ignoreCase = true) ->
        "That Google address already has a matchUP account with a password. Sign in with " +
            "the password, then connect Google from your profile."

    reason.contains("external_account_not_found", ignoreCase = true) ->
        "Google has not been connected to an account here yet. Create the account first, " +
            "or use an email address and a password."

    reason.contains("identifier_already_signed_in", ignoreCase = true) ->
        "You are already signed in on this device."

    reason.contains("oauth_access_denied", ignoreCase = true) ->
        "Google did not grant access. Try again and accept the permission prompt."

    else -> reason
}

/** Clerk names its attributes the way its API does; these are the ones a form can ask for. */
internal fun missingFieldLabel(field: String): String = when (field) {
    "first_name" -> "First name"
    "last_name" -> "Last name"
    "username" -> "Username"
    "email_address" -> "Email address"
    "phone_number" -> "Phone number"
    "password" -> "Password"
    else -> field.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
