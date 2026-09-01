package com.tzvi.kickoff.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.NoFootballSourceException
import com.tzvi.kickoff.data.repository.SettingsRepository
import com.tzvi.kickoff.data.repository.SourceProbe
import com.tzvi.kickoff.data.auth.AuthRepository
import com.tzvi.kickoff.data.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val footballRepository: FootballRepository,
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(OnboardingUiState())

    val uiState: StateFlow<OnboardingUiState> = mutableState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = OnboardingUiState(),
    )

    private var leaguesJob: Job? = null
    private var teamsJob: Job? = null

    init {
        viewModelScope.launch {
            val key = settings.apiFootballKey.first()
            val url = settings.backendUrl.first()
            val demo = settings.demoMode.first()
            val signedIn = auth.state.value is AuthState.SignedIn
            mutableState.update {
                it.copy(
                    apiKeyInput = key,
                    apiKeySaved = key.isNotBlank(),
                    backendUrlInput = url,
                    backendSaved = url.isNotBlank(),
                    demoEnabled = demo,
                    hasAccount = signedIn,
                    // Arriving here having skipped the sign-in is itself an answer: the
                    // server is the shared one, on somebody else's quota, and an account
                    // is what it asks for. Somebody who declined that wants their own key,
                    // so the page opens on it rather than on a tile they cannot use.
                    chosenSource = when {
                        demo -> ConfiguredSource.DEMO
                        !signedIn -> ConfiguredSource.API_FOOTBALL
                        else -> ConfiguredSource.BACKEND
                    },
                )
            }
        }
    }

    // ---- step 2: which source --------------------------------------------------

    /**
     * Records the pick, and for the demo acts on it immediately.
     *
     * Demo is the one choice with nothing left to fill in, so choosing it configures it;
     * the setup page after it is then a confirmation rather than a form. The other two
     * only record the intent - a key or a URL still has to be saved.
     */
    fun chooseSource(source: ConfiguredSource) {
        mutableState.update { it.copy(chosenSource = source) }
        if (source == ConfiguredSource.DEMO) {
            useDemoData()
        } else if (mutableState.value.demoEnabled) {
            // Switching away from the demo has to actually switch away, or the demo keeps
            // outranking the key you are about to paste and nothing appears to change.
            stopUsingDemoData()
        }
    }

    // ---- step 3: setting that source up ----------------------------------------

    fun onApiKeyChange(value: String) = mutableState.update { it.copy(apiKeyInput = value) }

    /**
     * Tries the key against the provider before storing it.
     *
     * A key is accepted or refused in one free call to /status. Storing first and finding
     * out two steps later - at "pick your competitions", which is about competitions and
     * not about keys - is what made a wrong key look like a broken app.
     */
    fun saveApiKey() {
        val key = mutableState.value.apiKeyInput.trim()
        if (key.isBlank()) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(checkingSource = true, sourceCheck = null, sourceCheckFailed = false)
            }
            when (val probe = footballRepository.probeApiKey(key)) {
                is SourceProbe.Ok -> {
                    settings.setApiFootballKey(key)
                    invalidateCatalogue()
                    mutableState.update {
                        it.copy(
                            apiKeyInput = key,
                            apiKeySaved = true,
                            checkingSource = false,
                            sourceCheck = probe.message,
                            sourceCheckFailed = false,
                        )
                    }
                }

                is SourceProbe.Failed -> mutableState.update {
                    it.copy(
                        checkingSource = false,
                        sourceCheck = probe.message,
                        sourceCheckFailed = true,
                    )
                }
            }
        }
    }

    fun onBackendUrlChange(value: String) =
        mutableState.update { it.copy(backendUrlInput = value, backendUrlError = null) }

    fun saveBackendUrl() {
        val normalised = normaliseBackendUrl(mutableState.value.backendUrlInput)
        if (normalised == null) {
            mutableState.update { it.copy(backendUrlError = INVALID_URL_MESSAGE) }
            return
        }
        viewModelScope.launch {
            mutableState.update {
                it.copy(checkingSource = true, sourceCheck = null, sourceCheckFailed = false)
            }
            when (val probe = footballRepository.probeBackend(normalised)) {
                is SourceProbe.Ok -> {
                    settings.setBackendUrl(normalised)
                    invalidateCatalogue()
                    mutableState.update {
                        it.copy(
                            backendUrlInput = normalised,
                            backendUrlError = null,
                            backendSaved = true,
                            checkingSource = false,
                            sourceCheck = probe.message,
                            sourceCheckFailed = false,
                        )
                    }
                }

                is SourceProbe.Failed -> mutableState.update {
                    it.copy(
                        checkingSource = false,
                        sourceCheck = probe.message,
                        sourceCheckFailed = true,
                    )
                }
            }
        }
    }

    /**
     * Whatever is on screen was answered by the source that has just been replaced, so
     * it is thrown away rather than left to look current: the next page refetches.
     */
    private fun invalidateCatalogue() {
        leaguesJob?.cancel()
        teamsJob?.cancel()
        mutableState.update {
            it.copy(
                // The cancelled jobs will never clear their own loading flags.
                leaguesLoading = false,
                leagues = emptyList(),
                leaguesFailure = null,
                teamsLoading = false,
                teams = emptyList(),
                teamsFailure = null,
                teamsLoadedFor = null,
            )
        }
    }

    // ---- step 3: leagues ------------------------------------------------------

    fun loadLeagues(force: Boolean = false) {
        if (leaguesJob?.isActive == true) return
        if (!force && mutableState.value.leagues.isNotEmpty()) return
        leaguesJob = viewModelScope.launch {
            mutableState.update { it.copy(leaguesLoading = true, leaguesFailure = null) }
            try {
                val leagues = footballRepository.featuredLeagues()
                mutableState.update {
                    it.copy(
                        leaguesLoading = false,
                        leagues = leagues,
                        // A pick made against an earlier catalogue that this one does not
                        // list would enable Next and then fetch no squads at all.
                        selectedLeagueIds = it.selectedLeagueIds
                            .intersect(leagues.mapTo(mutableSetOf()) { league -> league.id }),
                        leaguesFailure = if (leagues.isEmpty()) {
                            CatalogueError(CatalogueFailure.EMPTY)
                        } else {
                            null
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(leaguesLoading = false, leaguesFailure = error.asCatalogueFailure())
                }
            }
        }
    }

    fun toggleLeague(league: League) = mutableState.update { state ->
        val ids = if (league.id in state.selectedLeagueIds) {
            state.selectedLeagueIds - league.id
        } else {
            state.selectedLeagueIds + league.id
        }
        state.copy(selectedLeagueIds = ids)
    }

    // ---- step 4: teams --------------------------------------------------------

    fun loadTeams(force: Boolean = false) {
        val leagueIds = mutableState.value.selectedLeagueIds
        // A fast swipe can settle here before the leagues step has been answered; there
        // is nothing to ask for yet, and "no squads" would be the wrong thing to say.
        if (leagueIds.isEmpty()) return
        if (!force && mutableState.value.teamsLoadedFor == leagueIds) return
        teamsJob?.cancel()
        teamsJob = viewModelScope.launch {
            mutableState.update {
                it.copy(teamsLoading = true, teamsFailure = null, teamsLoadedFor = leagueIds)
            }
            val chosen = mutableState.value.leagues.filter { it.id in leagueIds }
            val collected = mutableListOf<TeamOption>()
            val stubborn = mutableListOf<League>()
            var failure: CatalogueError? = null

            // Published after EVERY league, not after the last one. Four leagues is four
            // round trips, each of which the backend may turn into two upstream calls, so
            // the step used to hold one blocking spinner for the sum of all of them and
            // read as a hang. Now the first league's clubs are on screen while the fourth
            // is still in flight, and the header says what is still coming.
            suspend fun publish(remaining: Int) {
                mutableState.update { state ->
                    state.copy(
                        teamsLoading = remaining > 0,
                        teamsRemaining = remaining,
                        // onceEach, not sortedBy: a club in two chosen competitions is one
                        // row, or the list's keys collide and the screen dies.
                        teams = collected.onceEach(),
                        teamsFailure = null,
                    )
                }
                // A breath between requests. The provider counts a rate-limited answer
                // against the daily quota just the same, so pacing four calls costs less
                // than four calls plus four retries.
                if (remaining > 0) delay(BETWEEN_LEAGUES_MS)
            }

            for ((index, league) in chosen.withIndex()) {
                try {
                    // One request per league, and the result is held in state afterwards:
                    // a free API-Football key only has 100 requests a day to spend.
                    footballRepository.teamsInLeague(league.id)
                        .forEach { collected += TeamOption(it, league) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failure = error.asCatalogueFailure()
                    stubborn += league
                }
                publish(chosen.lastIndex - index)
            }

            // A second, slower pass over just the ones that did not answer. Most failures
            // here are the provider's per-minute window rather than anything permanent, and
            // it costs one more request per league to find out - far cheaper than an error
            // screen that throws away the three leagues that did work.
            if (stubborn.isNotEmpty() && collected.isNotEmpty()) {
                mutableState.update { it.copy(teamsLoading = true, teamsRemaining = stubborn.size) }
                delay(RETRY_PAUSE_MS)
                for ((index, league) in stubborn.withIndex()) {
                    try {
                        footballRepository.teamsInLeague(league.id)
                            .forEach { collected += TeamOption(it, league) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Twice is enough. What answered is on screen; the rest is findable
                        // from the team search, and the step is no longer blocked on it.
                    }
                    publish(stubborn.lastIndex - index)
                }
            }

            mutableState.update { state ->
                state.copy(
                    teamsLoading = false,
                    teamsRemaining = 0,
                    teams = collected.onceEach(),
                    // One league failing is not worth throwing away the ones that answered.
                    teamsFailure = if (collected.isEmpty()) {
                        failure ?: CatalogueError(CatalogueFailure.EMPTY)
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun onTeamQueryChange(value: String) = mutableState.update { it.copy(teamQuery = value) }

    fun toggleTeam(option: TeamOption) = mutableState.update { state ->
        val selected = state.selected.toMutableMap()
        if (selected.remove(option.team.id) == null) selected[option.team.id] = option
        state.copy(selected = selected.toMap())
    }

    fun removeTeam(teamId: Int) = mutableState.update { state ->
        state.copy(selected = state.selected - teamId)
    }

    // ---- step 5: notifications ------------------------------------------------

    fun setNotificationsGranted(granted: Boolean) =
        mutableState.update { it.copy(notificationsGranted = granted) }

    fun onNotificationPermissionResult(granted: Boolean) = mutableState.update {
        it.copy(notificationsGranted = granted, notificationsDenied = !granted)
    }

    // ---- finish ---------------------------------------------------------------

    /**
     * The third way in: no key, no deployment, real crests.
     *
     * It clears the catalogue as well as setting the flag, because the leagues and squads
     * already on screen came from whichever source was selected before, and leaving them
     * would show one source's competitions under another source's name.
     */
    fun useDemoData() {
        viewModelScope.launch {
            settings.setDemoMode(true)
            invalidateCatalogue()
            mutableState.update { it.copy(demoEnabled = true) }
            loadLeagues(force = true)
        }
    }

    fun stopUsingDemoData() {
        viewModelScope.launch {
            settings.setDemoMode(false)
            invalidateCatalogue()
            mutableState.update { it.copy(demoEnabled = false) }
        }
    }

    fun finish() {
        val current = mutableState.value
        if (current.saving || current.selected.isEmpty()) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, saveError = null) }
            try {
                footballRepository.setFavourites(
                    current.selected.values.map { option -> option.team to option.league },
                )
                settings.setOnboardingComplete(true)
                mutableState.update { it.copy(saving = false, completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(saving = false, saveError = SAVE_FAILED_MESSAGE) }
            }
        }
    }

    private fun Throwable.asCatalogueFailure(): CatalogueError = CatalogueError(
        kind = if (this is NoFootballSourceException) {
            CatalogueFailure.NO_SOURCE
        } else {
            CatalogueFailure.UNREACHABLE
        },
        // The class name alone ("HttpException") tells nobody anything, so it is only
        // used when the exception carried no message of its own.
        detail = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName,
    )

    private companion object {
        /** A breath between league requests, so four of them are not one burst. */
        const val BETWEEN_LEAGUES_MS = 250L

        /** Long enough for the provider's per-minute window to have moved on. */
        const val RETRY_PAUSE_MS = 2_000L

        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        const val INVALID_URL_MESSAGE =
            "That does not look like a URL. Try https://your-app.onrender.com"
        const val SAVE_FAILED_MESSAGE = "Could not save your teams. Please try again."
    }
}
