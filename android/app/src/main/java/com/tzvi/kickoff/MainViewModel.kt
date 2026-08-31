package com.tzvi.kickoff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.AppSettings
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.data.repository.DeviceRegistrationRepository
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.SettingsRepository
import com.tzvi.kickoff.work.KickoffWorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the app shell needs before it can draw its first frame. */
data class AppUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val liveActivity: LiveActivity.MatchActivity? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val footballRepository: FootballRepository,
    private val registration: DeviceRegistrationRepository,
    private val workScheduler: KickoffWorkScheduler,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveActivity = footballRepository.liveMatches
        .flatMapLatest { matches ->
            val match = matches.firstOrNull() ?: return@flatMapLatest flowOf(null)
            footballRepository.observeEvents(match.id).map { events ->
                LiveActivity.MatchActivity(
                    match = match,
                    stage = when {
                        match.phase.isFinished -> LiveActivity.MatchActivity.Stage.FULL_TIME
                        match.isLive -> LiveActivity.MatchActivity.Stage.LIVE
                        else -> LiveActivity.MatchActivity.Stage.PRE_MATCH
                    },
                    lineups = null,
                    recentEvents = events,
                    statistics = null,
                )
            }
        }

    val uiState: StateFlow<AppUiState> =
        combine(settingsRepository.settings, liveActivity) { settings, activity ->
            AppUiState(loading = false, settings = settings, liveActivity = activity)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppUiState(),
        )

    init {
        // Registration is best-effort on every launch: the FCM token can be rotated by
        // the system at any time, and the backend needs the current one to reach us.
        viewModelScope.launch { runCatching { registration.syncIfPossible() } }

        // A change to the followed teams has to reach two places before it means
        // anything: the backend, so its fan-out includes the new team, and the fixture
        // sync, which is what arms the pre-match alarms. `drop(1)` skips the replay of
        // what is already stored; the debounce collapses the burst that onboarding and
        // multi-select produce into one round trip.
        viewModelScope.launch {
            footballRepository.favouriteTeamIds
                .distinctUntilChanged()
                .drop(1)
                .debounce(FAVOURITE_SYNC_DEBOUNCE_MS)
                .collect {
                    runCatching { registration.syncSubscriptions() }
                    workScheduler.requestImmediateFixtureSync()
                }
        }
    }

    private companion object {
        const val FAVOURITE_SYNC_DEBOUNCE_MS = 1_500L
    }
}
