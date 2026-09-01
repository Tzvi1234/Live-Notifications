package com.tzvi.kickoff.data.auth

import android.content.Context
import com.clerk.api.Clerk
import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.signin.SignIn
import com.clerk.api.signup.SignUp
import com.clerk.api.signup.attemptVerification
import com.clerk.api.signup.sendEmailCode
import com.clerk.api.signup.update
import com.clerk.api.sso.OAuthProvider
import com.clerk.api.sso.OAuthResult
import com.clerk.api.sso.SSOCancellationException
import com.clerk.api.user.User
import com.clerk.api.user.setProfileImage
import com.clerk.api.user.update
import com.tzvi.kickoff.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves Clerk's publishable key, initialises the SDK once, and owns every call that
 * touches an account.
 *
 * `Clerk.initialize` needs the key, and the key may only be knowable after a round trip
 * to our own backend, so initialisation cannot happen in `Application.onCreate` the way
 * an SDK's README always shows it. [start] kicks the resolution off there instead and
 * [state] is what the rest of the app waits on.
 *
 * The order is deliberate. The build's own key wins, because a build that shipped with
 * one should never depend on a server being up; then the last key the server gave us,
 * cached so a second launch is offline-clean; then the server. If all three come up
 * empty the app is [AuthState.NotConfigured] and every football feature carries on
 * exactly as it did before accounts existed.
 */
@Singleton
class AuthRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: AuthPreferences,
    private val configClient: ClerkConfigClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutableState = MutableStateFlow<AuthState>(AuthState.Initialising)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    /** Whether the auth screen has been dealt with, one way or the other. */
    val gateCleared: Flow<Boolean> = preferences.gateCleared

    @Volatile private var clerkStarted = false
    private val resolving = AtomicBoolean(false)

    /** Idempotent: the Application calls it, and the auth screen retries through it. */
    fun start() {
        if (clerkStarted || !resolving.compareAndSet(false, true)) return
        scope.launch {
            try {
                resolveKey()
            } finally {
                resolving.set(false)
            }
        }
    }

    /**
     * Another go at the key, for the case where the first attempt ran with no network.
     * A no-op once the SDK is up - the key cannot change under a live instance.
     */
    fun retry() {
        if (clerkStarted) return
        mutableState.value = AuthState.Initialising
        start()
    }

    private suspend fun resolveKey() {
        val compiledIn = BuildConfig.CLERK_PUBLISHABLE_KEY.trim()
        val local = compiledIn.ifBlank { preferences.publishableKey.first() }
        if (local.isNotBlank()) {
            initialise(local)
            return
        }

        val fetched = configClient.publishableKey()
        if (fetched.isNullOrBlank()) {
            // Not an error on the way to a real state: this is how the app runs on a
            // build nobody has given a Clerk instance to, and it has to run well.
            mutableState.value = AuthState.NotConfigured
            return
        }
        preferences.setPublishableKey(fetched)
        initialise(fetched)
    }

    private suspend fun initialise(key: String) {
        // initialize() registers activity lifecycle callbacks, which is main-thread work
        // even though everything either side of it is not.
        withContext(Dispatchers.Main.immediate) { Clerk.initialize(context, key) }
        clerkStarted = true

        scope.launch {
            combine(
                Clerk.isInitialized,
                Clerk.initializationError,
                Clerk.userFlow,
            ) { ready, error, user ->
                when {
                    // A key that Clerk itself rejects is indistinguishable, to the user,
                    // from no key at all: both mean accounts are not available here.
                    !ready && error != null -> AuthState.NotConfigured
                    !ready -> AuthState.Initialising
                    user != null -> AuthState.SignedIn(user)
                    else -> AuthState.SignedOut
                }
            }.collect { mutableState.value = it }
        }

        scope.launch {
            // Clerk reports readiness once it has a client, which on a first launch means
            // a round trip. If that never lands - no network, say - the screen waiting on
            // this must not hold a spinner for ever: drop through to the signed-out form,
            // where the next call fails with Clerk's own explanation instead of silence.
            delay(READY_TIMEOUT_MS)
            if (mutableState.value == AuthState.Initialising) {
                mutableState.value = AuthState.SignedOut
            }
        }
    }

    /**
     * Waits for the state to stop being [AuthState.Initialising], within reason.
     *
     * The bound matters: this is called from an OkHttp interceptor, and a football
     * request must not sit behind a stalled sign-in check. Timing out means no token,
     * which means an unauthenticated request - which every public endpoint accepts.
     */
    suspend fun awaitSettled(): AuthState =
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) { state.first { it.isSettled } }
            ?: mutableState.value

    /** The current session's JWT, or null when there is no session to speak for. */
    suspend fun sessionToken(): String? {
        if (awaitSettled() !is AuthState.SignedIn) return null
        return when (val result = Clerk.auth.getToken()) {
            is ClerkResult.Success -> result.value.takeIf { it.isNotBlank() }
            is ClerkResult.Failure -> null
        }
    }

    // ---- creating an account ---------------------------------------------------

    suspend fun signUp(email: String, password: String): AuthOutcome {
        val result = Clerk.auth.signUp {
            this.email = email.trim()
            this.password = password
        }
        return when (result) {
            is ClerkResult.Success -> advance(result.value)
            is ClerkResult.Failure -> AuthOutcome.Failed(result.readableMessage)
        }
    }

    /**
     * Fills in whatever this Clerk instance asked for that the first form did not carry.
     *
     * Which attributes those are is a dashboard setting, so the values arrive as a map
     * keyed by Clerk's own field names rather than as a fixed argument list.
     */
    suspend fun submitMissingFields(values: Map<String, String>): AuthOutcome {
        val signUp = Clerk.auth.currentSignUp ?: return AuthOutcome.Failed(NO_SIGN_UP_IN_PROGRESS)
        val result = signUp.update(
            SignUp.SignUpUpdateParams.Standard(
                firstName = values[FIELD_FIRST_NAME]?.trim()?.takeIf { it.isNotBlank() },
                lastName = values[FIELD_LAST_NAME]?.trim()?.takeIf { it.isNotBlank() },
                username = values[FIELD_USERNAME]?.trim()?.takeIf { it.isNotBlank() },
            ),
        )
        return when (result) {
            is ClerkResult.Success -> advance(result.value)
            is ClerkResult.Failure -> AuthOutcome.Failed(result.readableMessage)
        }
    }

    suspend fun submitEmailCode(code: String): AuthOutcome {
        val signUp = Clerk.auth.currentSignUp ?: return AuthOutcome.Failed(NO_SIGN_UP_IN_PROGRESS)
        val result = signUp.attemptVerification(
            SignUp.AttemptVerificationParams.EmailCode(code = code.trim()),
        )
        return when (result) {
            is ClerkResult.Success -> advance(result.value)
            is ClerkResult.Failure -> AuthOutcome.Failed(result.readableMessage)
        }
    }

    suspend fun resendEmailCode(): AuthOutcome {
        val signUp = Clerk.auth.currentSignUp ?: return AuthOutcome.Failed(NO_SIGN_UP_IN_PROGRESS)
        return when (val result = signUp.sendEmailCode()) {
            is ClerkResult.Success -> AuthOutcome.NeedsEmailCode(result.value.emailAddress.orEmpty())
            is ClerkResult.Failure -> AuthOutcome.Failed(result.readableMessage)
        }
    }

    /**
     * Reads a sign-up's status and says what the screen should ask for next.
     *
     * Missing requirements are asked for before verification: an unverified email is
     * worth nothing if the instance is going to reject the sign-up for want of a name.
     */
    private suspend fun advance(signUp: SignUp): AuthOutcome = when (signUp.status) {
        SignUp.Status.COMPLETE -> {
            clearAuthGate()
            AuthOutcome.Complete
        }

        SignUp.Status.MISSING_REQUIREMENTS -> {
            val collectable = signUp.missingFields.filter { it in COLLECTABLE_FIELDS }
            when {
                collectable.isNotEmpty() -> AuthOutcome.NeedsFields(collectable)
                EMAIL_FIELD in signUp.unverifiedFields -> sendCodeFor(signUp)
                // Clerk wants something no form here can produce - a phone code, an
                // enterprise connection. Say which, rather than looping on a blank page.
                else -> AuthOutcome.Failed(unsupportedRequirement(signUp))
            }
        }

        SignUp.Status.ABANDONED -> AuthOutcome.Failed(
            "This sign-up expired before it was finished. Start again.",
        )

        SignUp.Status.UNKNOWN -> AuthOutcome.Failed(
            "Clerk returned a sign-up state matchUP does not understand.",
        )
    }

    private suspend fun sendCodeFor(signUp: SignUp): AuthOutcome =
        when (val result = signUp.sendEmailCode()) {
            is ClerkResult.Success -> AuthOutcome.NeedsEmailCode(
                result.value.emailAddress ?: signUp.emailAddress.orEmpty(),
            )

            is ClerkResult.Failure -> AuthOutcome.Failed(result.readableMessage)
        }

    private fun unsupportedRequirement(signUp: SignUp): String {
        val outstanding = (signUp.missingFields + signUp.unverifiedFields).distinct()
        val listed = outstanding.joinToString { missingFieldLabel(it).lowercase() }
        return if (listed.isBlank()) {
            "This Clerk instance needs something matchUP cannot ask for yet."
        } else {
            "This account still needs $listed, which matchUP cannot ask for yet."
        }
    }

    // ---- Google --------------------------------------------------------------

    /**
     * One button for both halves of it: `signInWithOAuth` transfers to a sign-up itself
     * when the Google account has never been seen here before.
     *
     * That transfer is why there is no separate "sign up with Google". Clerk's SSO
     * service runs the redirect, notices that the identifier is new, and hands back a
     * [OAuthResult] carrying a sign-up instead of a sign-in - so the screen asks the
     * same follow-up questions it would have asked after an email sign-up, and the two
     * routes converge on [advance].
     */
    suspend fun continueWithGoogle(): AuthOutcome =
        when (val result = Clerk.auth.signInWithOAuth(OAuthProvider.GOOGLE)) {
            is ClerkResult.Success -> resolve(result.value)

            is ClerkResult.Failure ->
                if (result.throwable is SSOCancellationException) {
                    AuthOutcome.Cancelled
                } else {
                    AuthOutcome.Failed(result.readableMessage)
                }
        }

    /** A finished sign-in, a transferred sign-up, or neither - said plainly. */
    private suspend fun resolve(result: OAuthResult): AuthOutcome {
        result.signUp?.let { return advance(it) }

        val signIn = result.signIn ?: return AuthOutcome.Failed(
            "Google came back without a session. Try again, or use an email address.",
        )
        return when (signIn.status) {
            SignIn.Status.COMPLETE -> {
                clearAuthGate()
                AuthOutcome.Complete
            }

            SignIn.Status.NEEDS_SECOND_FACTOR -> AuthOutcome.Failed(
                "This account has two-factor authentication on, which matchUP cannot " +
                    "prompt for yet.",
            )

            else -> AuthOutcome.Failed(
                "Google signed you in but Clerk wants another step matchUP cannot show yet.",
            )
        }
    }

    // ---- signing in ------------------------------------------------------------

    suspend fun signIn(email: String, password: String): AuthOutcome {
        val result = Clerk.auth.signInWithPassword {
            identifier = email.trim()
            this.password = password
        }
        return when (result) {
            is ClerkResult.Success -> when (result.value.status) {
                SignIn.Status.COMPLETE -> {
                    clearAuthGate()
                    AuthOutcome.Complete
                }

                SignIn.Status.NEEDS_SECOND_FACTOR -> AuthOutcome.Failed(
                    "This account has two-factor authentication on, which matchUP cannot " +
                        "prompt for yet.",
                )

                SignIn.Status.NEEDS_NEW_PASSWORD -> AuthOutcome.Failed(
                    "Clerk wants a new password for this account. Reset it on the web first.",
                )

                else -> AuthOutcome.Failed(
                    "Clerk needs another step for this sign-in that matchUP cannot show yet.",
                )
            }

            is ClerkResult.Failure -> AuthOutcome.Failed(result.readableMessage)
        }
    }

    /** Null on success, otherwise the reason. */
    suspend fun signOut(): String? {
        val result = Clerk.auth.signOut()
        preferences.setGateCleared(false)
        return when (result) {
            is ClerkResult.Success -> null
            is ClerkResult.Failure -> result.readableMessage
        }
    }

    // ---- profile ---------------------------------------------------------------

    /** Null on success, otherwise the reason. */
    suspend fun updateName(firstName: String, lastName: String): String? {
        val user = currentUser() ?: return NOT_SIGNED_IN
        val result = user.update(
            User.UpdateParams(
                firstName = firstName.trim(),
                lastName = lastName.trim(),
            ),
        )
        return when (result) {
            is ClerkResult.Success -> null
            is ClerkResult.Failure -> result.readableMessage
        }
    }

    /** Null on success, otherwise the reason. */
    suspend fun setProfileImage(file: File): String? {
        val user = currentUser() ?: return NOT_SIGNED_IN
        return when (val result = user.setProfileImage(file)) {
            is ClerkResult.Success -> null
            is ClerkResult.Failure -> result.readableMessage
        }
    }

    private fun currentUser(): User? = (mutableState.value as? AuthState.SignedIn)?.user

    // ---- the launch gate -------------------------------------------------------

    /**
     * Records that the auth screen has been dealt with - by signing in, or by choosing
     * to carry on without an account. Signing out is what puts it back.
     */
    suspend fun clearAuthGate() = preferences.setGateCleared(true)

    private companion object {
        const val EMAIL_FIELD = "email_address"
        const val FIELD_FIRST_NAME = "first_name"
        const val FIELD_LAST_NAME = "last_name"
        const val FIELD_USERNAME = "username"

        /** The attributes this app has a field for; anything else is a dead end, said so. */
        val COLLECTABLE_FIELDS = setOf(FIELD_FIRST_NAME, FIELD_LAST_NAME, FIELD_USERNAME)

        const val NO_SIGN_UP_IN_PROGRESS =
            "That sign-up is no longer open. Start again from your email address."
        const val NOT_SIGNED_IN = "You are not signed in."

        const val SETTLE_TIMEOUT_MS = 2_500L
        const val READY_TIMEOUT_MS = 6_000L
    }
}
