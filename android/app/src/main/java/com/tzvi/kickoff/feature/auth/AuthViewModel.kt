package com.tzvi.kickoff.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.data.auth.AuthOutcome
import com.tzvi.kickoff.data.auth.AuthRepository
import com.tzvi.kickoff.data.auth.AuthState
import com.tzvi.kickoff.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AuthUiState())

    val uiState: StateFlow<AuthUiState> =
        combine(mutableState, auth.state, settings.settings) { local, account, config ->
            local.copy(
                availability = when (account) {
                    AuthState.Initialising -> AccountAvailability.RESOLVING
                    AuthState.NotConfigured -> AccountAvailability.UNAVAILABLE
                    else -> AccountAvailability.AVAILABLE
                },
                signedIn = account is AuthState.SignedIn,
                // Signing out comes back through this screen, and a user who has already
                // picked their teams is not made to pick them again.
                needsOnboarding = !config.onboardingComplete,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = AuthUiState(),
        )

    init {
        // The launch-time attempt may have run before there was any network. Arriving on
        // this screen is the moment it is worth another go, and it costs nothing once
        // the SDK is already up.
        auth.retry()

        // A session restored while this screen is open still has to clear the gate, or
        // the next launch asks a signed-in user to sign in again.
        viewModelScope.launch {
            auth.state.collect { if (it is AuthState.SignedIn) auth.clearAuthGate() }
        }
    }

    // ---- fields ----------------------------------------------------------------

    fun onEmailChange(value: String) =
        mutableState.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) =
        mutableState.update { it.copy(password = value, error = null) }

    fun onCodeChange(value: String) =
        mutableState.update { it.copy(code = value.filter(Char::isDigit), error = null) }

    fun onFieldChange(field: String, value: String) = mutableState.update {
        it.copy(fieldValues = it.fieldValues + (field to value), error = null)
    }

    // ---- moving between steps --------------------------------------------------

    /** Clears whatever the previous step was complaining about; keeps what was typed. */
    fun goTo(step: AuthStep) =
        mutableState.update { it.copy(step = step, error = null, notice = null) }

    fun back() = mutableState.update {
        val target = when (it.step) {
            AuthStep.WELCOME -> AuthStep.WELCOME
            AuthStep.SIGN_IN, AuthStep.SIGN_UP -> AuthStep.WELCOME
            // Verification and details belong to a sign-up that is already open, so back
            // out of them lands on the form that started it rather than on the splash.
            AuthStep.VERIFY, AuthStep.DETAILS -> AuthStep.SIGN_UP
        }
        it.copy(step = target, error = null, notice = null)
    }

    // ---- the calls -------------------------------------------------------------

    fun submit() {
        val state = mutableState.value
        if (!state.canSubmit) return
        when (state.step) {
            AuthStep.WELCOME -> Unit
            AuthStep.SIGN_IN -> signIn(state)
            AuthStep.SIGN_UP -> signUp(state)
            AuthStep.VERIFY -> verify(state)
            AuthStep.DETAILS -> submitDetails(state)
        }
    }

    private fun signIn(state: AuthUiState) = working {
        apply(auth.signIn(state.email, state.password))
    }

    private fun signUp(state: AuthUiState) = working {
        apply(auth.signUp(state.email, state.password))
    }

    private fun verify(state: AuthUiState) = working {
        apply(auth.submitEmailCode(state.code))
    }

    private fun submitDetails(state: AuthUiState) = working {
        apply(auth.submitMissingFields(state.fieldValues))
    }

    fun resendCode() = working {
        when (val outcome = auth.resendEmailCode()) {
            is AuthOutcome.NeedsEmailCode -> mutableState.update {
                it.copy(code = "", notice = "A new code is on its way to ${outcome.email}.")
            }

            else -> apply(outcome)
        }
    }

    /** The escape hatch. The app is worth using signed out and has to stay that way. */
    fun continueWithoutAccount(onDone: () -> Unit) {
        viewModelScope.launch {
            auth.clearAuthGate()
            onDone()
        }
    }

    private fun apply(outcome: AuthOutcome) = mutableState.update { state ->
        when (outcome) {
            AuthOutcome.Complete -> state.copy(error = null, notice = null)

            is AuthOutcome.NeedsEmailCode -> state.copy(
                step = AuthStep.VERIFY,
                email = outcome.email.ifBlank { state.email },
                code = "",
                error = null,
                notice = null,
            )

            is AuthOutcome.NeedsFields -> state.copy(
                step = AuthStep.DETAILS,
                missingFields = outcome.fields,
                error = null,
                notice = null,
            )

            is AuthOutcome.Failed -> state.copy(error = outcome.message, notice = null)
        }
    }

    private fun working(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null, notice = null) }
            try {
                block()
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
