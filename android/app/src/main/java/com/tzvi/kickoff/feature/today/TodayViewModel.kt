package com.tzvi.kickoff.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.data.auth.AuthRepository
import com.tzvi.kickoff.data.auth.AuthState
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.NoFootballSourceException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Duration
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

data class TodayUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val liveMatches: List<Match> = emptyList(),
    val upcomingDays: List<UpcomingDay> = emptyList(),
    val favouriteTeams: List<Team> = emptyList(),
    val errorMessage: String? = null,
    /** No backend and no API key: nothing will ever load until Settings is visited. */
    val sourceMissing: Boolean = false,
    /** Who is signed in, for the greeting. Blank when nobody is. */
    val displayName: String = "",
    val avatarUrl: String? = null,
) {
    /** Whether to greet by name at all. Signed out, the screen is just "Today". */
    val hasProfile: Boolean get() = displayName.isNotBlank() || avatarUrl != null
}

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val footballRepository: FootballRepository,
    auth: AuthRepository,
) : ViewModel() {

    private val syncState = MutableStateFlow(SyncState())

    private var syncJob: Job? = null

    val uiState: StateFlow<TodayUiState> = combine(
        footballRepository.liveForFavourites,
        footballRepository.upcomingForFavourites,
        footballRepository.favouriteTeams,
        syncState,
        auth.state,
    ) { live, upcoming, teams, sync, account ->
        val user = (account as? AuthState.SignedIn)?.user
        val now = Instant.now()
        TodayUiState(
            isLoading = false,
            isRefreshing = sync.isRefreshing,
            liveMatches = live.sortedByDescending { it.kickoffAt },
            upcomingDays = upcoming.toUpcomingDays(now),
            favouriteTeams = teams,
            errorMessage = sync.errorMessage,
            sourceMissing = sync.sourceMissing,
            // First name only for the greeting: "Hello Tzvi" is a greeting, "Hello Tzvi
            // Weiss" is a summons.
            displayName = user?.firstName?.trim().orEmpty().ifBlank {
                user?.username?.trim().orEmpty()
            },
            avatarUrl = user?.imageUrl?.takeIf { it.isNotBlank() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        sync(userInitiated = false)
    }

    fun refresh() = sync(userInitiated = true)

    fun dismissError() {
        syncState.update { it.copy(errorMessage = null) }
    }

    /**
     * Fixtures then live, in that order: the live call overwrites the same rows with
     * fresher scores, so running it second is what keeps a goal from being rolled back.
     */
    private fun sync(userInitiated: Boolean) {
        // Marked before the de-duplication guard: a pull that lands on top of the silent
        // startup sync still has to move the indicator, and whichever job is in flight
        // clears the flag when it finishes.
        if (userInitiated) syncState.update { it.copy(isRefreshing = true) }
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
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
        // Anything in play has its own section above and a result is not "next up", but
        // the cut-off reaches back past kick-off: a fixture the provider has not yet
        // flipped to in-play is still the thing the user opened the app to look at, and
        // dropping it at kick-off time would make it vanish from the screen entirely.
        val earliest = now.minus(KICKOFF_GRACE)
        return asSequence()
            .filter { !it.isLive && !it.phase.isFinished && it.kickoffAt.isAfter(earliest) }
            .sortedBy { it.kickoffAt }
            .groupBy { it.kickoffAt.atZone(zone).toLocalDate() }
            .map { (date, matches) -> UpcomingDay(date, dayLabel(date, today), matches) }
    }

    private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> DAY_FORMAT.format(date)
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
        val KICKOFF_GRACE: Duration = Duration.ofHours(2)
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
    }
}
