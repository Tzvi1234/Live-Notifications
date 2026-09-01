package com.tzvi.kickoff.feature.auth

/**
 * The one screen's five faces.
 *
 * [VERIFY] and [DETAILS] are not optional extras: Clerk decides at run time whether an
 * instance verifies email addresses and which attributes it insists on, so a sign-up can
 * land on either of them and the flow has to be able to go there.
 */
enum class AuthStep { WELCOME, SIGN_IN, SIGN_UP, VERIFY, DETAILS, PROFILE }

/** Whether this build can offer accounts at all. */
enum class AccountAvailability { RESOLVING, AVAILABLE, UNAVAILABLE }

data class AuthUiState(
    val step: AuthStep = AuthStep.WELCOME,
    val availability: AccountAvailability = AccountAvailability.RESOLVING,
    val email: String = "",
    val password: String = "",
    val code: String = "",
    /** Clerk's own field names, in the order it listed them. */
    val missingFields: List<String> = emptyList(),
    val fieldValues: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    /** The Google redirect is in flight. Tracked apart from [busy] so the button that
     *  started it is the one that shows the spinner. */
    val googleBusy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val signedIn: Boolean = false,
    /**
     * Whether this flow still owes the user a profile step.
     *
     * Set BEFORE the call that can complete a sign-up, not after it. `signedIn` is derived
     * from Clerk's own user flow and flips the moment the session lands - which is inside
     * the verify call, before its result has been applied - so a flag set afterwards would
     * always lose the race and the screen would leave before the profile page drew.
     *
     * Only the email sign-up sets it. A Google account arrives with a name and a picture
     * already, and asking for them again would be asking for what we have.
     */
    val collectingProfile: Boolean = false,
    /** Where this screen hands over to: onboarding on a fresh install, Today after a
     *  sign-out. */
    val needsOnboarding: Boolean = true,
) {
    /** True once there is a session AND nothing left for this flow to ask. */
    val finished: Boolean get() = signedIn && !collectingProfile

    /** True while either route is mid-flight; every control on the page goes quiet. */
    val working: Boolean get() = busy || googleBusy

    /**
     * Google is offered once, on the front door.
     *
     * It covers both halves - a Google account that has never been seen here is created on
     * the way through - so repeating it over the email form would be the same button asking
     * the same question a second time. Choosing "sign up with email" is a choice; the pages
     * after it are that choice being carried out.
     */
    val googleOffered: Boolean
        get() = availability == AccountAvailability.AVAILABLE && step == AuthStep.WELCOME

    val canSubmit: Boolean
        get() = !working && when (step) {
            AuthStep.WELCOME -> true
            AuthStep.SIGN_IN -> email.isNotBlank() && password.isNotBlank()
            AuthStep.SIGN_UP -> email.contains('@') && password.length >= MIN_PASSWORD_LENGTH
            AuthStep.VERIFY -> code.trim().length >= MIN_CODE_LENGTH
            // Clerk asked for these, so all of them: a half-filled update just comes
            // straight back as the same missing-requirements answer.
            AuthStep.DETAILS -> missingFields.all { fieldValues[it].orEmpty().isNotBlank() }
            // The profile page saves through its own view model and has its own buttons.
            AuthStep.PROFILE -> true
        }

    /** The one thing standing between this step and its button, or null. */
    val blockedReason: String?
        get() = when {
            working || canSubmit -> null
            step == AuthStep.SIGN_UP && email.isNotBlank() && !email.contains('@') ->
                "That does not look like an email address."
            step == AuthStep.SIGN_UP && password.isNotEmpty() &&
                password.length < MIN_PASSWORD_LENGTH ->
                "Passwords need at least $MIN_PASSWORD_LENGTH characters."
            else -> null
        }
}

/** Clerk's own floor, and rejecting a short password here saves a round trip to hear it. */
const val MIN_PASSWORD_LENGTH = 8

/** Clerk's email codes are six digits; shorter is a half-typed one, not an attempt. */
const val MIN_CODE_LENGTH = 6
