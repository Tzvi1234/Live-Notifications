package com.tzvi.kickoff.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.CalendarEvent
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.data.repository.CalendarRepository
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.NoFootballSourceException
import com.tzvi.kickoff.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** One day's worth of upcoming fixtures, with the label its sticky header shows. */
data class UpcomingDay(
    val date: LocalDate,
    val label: String,
    val matches: List<Match>,
)

/**
 * The calendar section has three distinct faces - switched off, switched on but not
 * permitted, and permitted with events - and the screen has to tell them apart.
 */
data class CalendarState(
    val syncEnabled: Boolean = false,
    val permissionGranted: Boolean = false,
    val events: List<CalendarEvent> = emptyList(),
)

data class TodayUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val liveMatches: List<Match> = emptyList(),
    val upcomingDays: List<UpcomingDay> = emptyList(),
    val favouriteTeams: List<Team> = emptyList(),
    val calendar: CalendarState = CalendarState(),
    val errorMessage: String? = null,
    /** No backend and no API key: nothing will ever load until Settings is visited. */
    val sourceMissing: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val footballRepository: FootballRepository,
    private val calendarRepository: CalendarRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val syncState = MutableStateFlow(SyncState())

    /** Bumped by the screen after a permission dialog, to force the gate below to re-read. */
    private val calendarPermissionChanges = MutableStateFlow(0)

    private var syncJob: Job? = null

    private val calendarState: Flow<CalendarState> = combine(
        settingsRepository.settings.map { it.calendarSyncEnabled }.distinctUntilChanged(),
        calendarPermissionChanges,
    ) { enabled, _ ->
        // Read the grant here rather than caching it: it can change in system Settings
        // while the app is backgrounded, with no callback of any kind.
        CalendarState(syncEnabled = enabled, permissionGranted = calendarRepository.hasPermission())
    }.flatMapLatest { gate ->
        if (!gate.syncEnabled || !gate.permissionGranted) {
            flowOf(gate)
        } else {
            calendarRepository.observeUpcoming(CALENDAR_WINDOW_DAYS)
                .map { events -> gate.copy(events = events.nextFew()) }
                .catch { emit(gate) }
        }
    }

    val uiState: StateFlow<TodayUiState> = combine(
        footballRepository.liveMatches,
        footballRepository.upcomingForFavourites,
        footballRepository.favouriteTeams,
        calendarState,
        syncState,
    ) { live, upcoming, teams, calendar, sync ->
        val now = Instant.now()
        TodayUiState(
            isLoading = false,
            isRefreshing = sync.isRefreshing,
            liveMatches = live.sortedByDescending { it.kickoffAt },
            upcomingDays = upcoming.toUpcomingDays(now),
            favouriteTeams = teams,
            calendar = calendar,
            errorMessage = sync.errorMessage,
            sourceMissing = sync.sourceMissing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        sync(userInitiated = false)
    }

    fun refresh() = sync(userInitiated = true)

    fun onCalendarPermissionChanged() {
        calendarPermissionChanges.update { it + 1 }
    }

    fun dismissError() {
        syncState.update { it.copy(errorMessage = null) }
    }

    /**
     * Fixtures then live, in that order: the live call overwrites the same rows with
     * fresher scores, so running it second is what keeps a goal from being rolled back.
     */
    private fun sync(userInitiated: Boolean) {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            if (userInitiated) syncState.update { it.copy(isRefreshing = true) }
            val outcome = runCatching {
                footballRepository.refreshFixtures()
                footballRepository.refreshLive()
            }
            syncState.value = outcome.fold(
                onSuccess = { SyncState() },
                onFailure = { error ->
                    SyncState(
                        errorMessage = if (error is NoFootballSourceException) null
                        else error.userMessage(),
                        sourceMissing = error is NoFootballSourceException,
                    )
                },
            )
        }
    }

    private fun List<Match>.toUpcomingDays(now: Instant): List<UpcomingDay> {
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        return asSequence()
            // Anything in play has its own section above, and a result is not "next up".
            .filter { !it.isLive && !it.phase.isFinished && it.kickoffAt.isAfter(now) }
            .sortedBy { it.kickoffAt }
            .groupBy { it.kickoffAt.atZone(zone).toLocalDate() }
            .map { (date, matches) -> UpcomingDay(date, dayLabel(date, today), matches) }
    }

    private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> DAY_FORMAT.format(date)
    }

    private fun List<CalendarEvent>.nextFew(): List<CalendarEvent> {
        val now = Instant.now()
        return filter { it.instanceEnd.isAfter(now) }
            .sortedBy { it.instanceStart }
            .take(CALENDAR_EVENT_LIMIT)
    }

    private fun Throwable.userMessage(): String = when (this) {
        is IOException -> "Couldn't reach the network. Showing the last saved fixtures."
        else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong refreshing."
    }

    private data class SyncState(
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null,
        val sourceMissing: Boolean = false,
    )

    private companion object {
        const val CALENDAR_WINDOW_DAYS = 7L
        const val CALENDAR_EVENT_LIMIT = 3
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
    }
}
