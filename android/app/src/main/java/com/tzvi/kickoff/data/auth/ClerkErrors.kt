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
        return if (shortErrorMessageOrNull() == null && status != null) {
            "$message (HTTP $status)"
        } else {
            message
        }
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
