package com.tzvi.kickoff.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.NoFootballSourceException
import com.tzvi.kickoff.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
            mutableState.update {
                it.copy(
                    apiKeyInput = key,
                    apiKeySaved = key.isNotBlank(),
                    backendUrlInput = url,
                    backendSaved = url.isNotBlank(),
                    demoEnabled = demo,
                )
            }
        }
    }

    // ---- step 2: source -------------------------------------------------------

    fun onApiKeyChange(value: String) = mutableState.update { it.copy(apiKeyInput = value) }

    fun saveApiKey() {
        val key = mutableState.value.apiKeyInput.trim()
        if (key.isBlank()) return
        viewModelScope.launch {
            settings.setApiFootballKey(key)
            invalidateCatalogue()
            mutableState.update { it.copy(apiKeyInput = key, apiKeySaved = true) }
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
            settings.setBackendUrl(normalised)
            invalidateCatalogue()
            mutableState.update {
                it.copy(
                    backendUrlInput = normalised,
                    backendUrlError = null,
                    backendSaved = true,
                )
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
                        leaguesFailure = if (leagues.isEmpty()) CatalogueFailure.EMPTY else null,
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
            var failure: CatalogueFailure? = null
            for (league in chosen) {
                try {
                    // One request per league, and the result is held in state afterwards:
                    // a free API-Football key only has 100 requests a day to spend.
                    footballRepository.teamsInLeague(league.id)
                        .forEach { collected += TeamOption(it, league) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failure = error.asCatalogueFailure()
                }
            }
            mutableState.update { state ->
                state.copy(
                    teamsLoading = false,
                    teams = collected.sortedBy { option -> option.team.name },
                    // One league failing is not worth throwing away the ones that answered.
                    teamsFailure = if (collected.isEmpty()) {
                        failure ?: CatalogueFailure.EMPTY
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

    private fun Throwable.asCatalogueFailure(): CatalogueFailure =
        if (this is NoFootballSourceException) {
            CatalogueFailure.NO_SOURCE
        } else {
            CatalogueFailure.UNREACHABLE
        }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        const val INVALID_URL_MESSAGE =
            "That does not look like a URL. Try https://your-app.onrender.com"
        const val SAVE_FAILED_MESSAGE = "Could not save your teams. Please try again."
    }
}
