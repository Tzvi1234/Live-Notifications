package com.tzvi.kickoff.feature.matches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.NoFootballSourceException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MatchesViewModel @Inject constructor(
    private val footballRepository: FootballRepository,
) : ViewModel() {

    private val today = LocalDate.now()

    private val days: List<DayChip> = (-DAYS_BEHIND..DAYS_AHEAD)
        .map { offset -> today.plusDays(offset).toChip() }

    private val request = MutableStateFlow(DayRequest(today))
    private val selectedFilter = MutableStateFlow(MatchFilter.ALL)

    /**
     * Dates already pulled over the network in this session. API-Football bills per call
     * against a daily allowance, so returning to a date the user already visited is served
     * from Room until they explicitly pull to refresh.
     */
    private val fetchedDates = mutableSetOf<LocalDate>()

    private val dayStates: Flow<DayState> = request.flatMapLatest { loadDay(it) }

    val uiState: StateFlow<MatchesUiState> = combine(
        request.map { it.date }.distinctUntilChanged(),
        dayStates,
        selectedFilter,
        footballRepository.favouriteTeamIds,
    ) { date, day, filter, favouriteIds ->
        // The selected date moves the instant a chip is tapped, while the day's fixtures
        // arrive a beat later; until they match, the previous date's list is not shown.
        val settled = day.date == date
        val matches = if (settled) day.matches else emptyList()
        val favourites = favouriteIds.toSet()
        MatchesUiState(
            days = days,
            selectedDate = date,
            isOnToday = date == today,
            monthLabel = monthLabel(date),
            dateLabel = dateLabel(date),
            filter = filter,
            groups = matches.applyFilter(filter, favourites).toGroups(),
            isLoading = !settled || day.isLoading,
            isRefreshing = settled && day.isRefreshing,
            errorMessage = if (settled) day.errorMessage else null,
            dayMatchCount = matches.size,
            followedTeamCount = favourites.size,
            sourceMissing = settled && day.sourceMissing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    fun selectDate(date: LocalDate) {
        request.value = DayRequest(date)
    }

    fun jumpToToday() = selectDate(today)

    fun selectFilter(filter: MatchFilter) {
        selectedFilter.value = filter
    }

    fun refresh() {
        request.update { DayRequest(it.date, force = true, attempt = it.attempt + 1) }
    }

    /**
     * Cache first, network second, cache again.
     *
     * The final re-read is not redundant: `refreshDay` returns the provider's own calendar
     * day, whereas the list has to hold exactly the fixtures that fall inside the local
     * day the user tapped, which is what `matchesOn` windows on.
     */
    private fun loadDay(dayRequest: DayRequest): Flow<DayState> = flow {
        val date = dayRequest.date
        val cached = runCatching { footballRepository.matchesOn(date) }.getOrDefault(emptyList())
        if (!dayRequest.force && date in fetchedDates) {
            emit(DayState(date, cached))
            return@flow
        }

        emit(
            DayState(
                date = date,
                matches = cached,
                isLoading = cached.isEmpty(),
                isRefreshing = cached.isNotEmpty(),
            ),
        )

        // Dragging along the week strip cancels this flow before the delay elapses, so a
        // fast scrub through ten dates costs one request rather than ten.
        delay(FETCH_DEBOUNCE_MILLIS)

        val outcome = runCatching { footballRepository.refreshDay(date) }
        emit(
            outcome.fold(
                onSuccess = {
                    fetchedDates += date
                    val fresh = runCatching { footballRepository.matchesOn(date) }
                    DayState(date, fresh.getOrDefault(cached))
                },
                onFailure = { error ->
                    DayState(
                        date = date,
                        matches = cached,
                        errorMessage = if (error is NoFootballSourceException) null
                        else error.userMessage(),
                        sourceMissing = error is NoFootballSourceException,
                    )
                },
            ),
        )
    }

    private fun List<Match>.applyFilter(mode: MatchFilter, favourites: Set<Int>): List<Match> =
        when (mode) {
            MatchFilter.ALL -> this
            MatchFilter.MY_TEAMS -> filter { it.home.id in favourites || it.away.id in favourites }
            MatchFilter.LIVE -> filter { it.phase.isLive }
        }

    private fun List<Match>.toGroups(): List<CompetitionGroup> = groupBy { it.leagueId }
        .map { (leagueId, matches) ->
            val ordered = matches.sortedWith(compareBy({ it.kickoffAt }, { it.id }))
            CompetitionGroup(
                leagueId = leagueId,
                leagueName = ordered.first().leagueName,
                leagueLogoUrl = ordered.firstNotNullOfOrNull { it.leagueLogoUrl },
                round = ordered.mapTo(mutableSetOf()) { it.round }.singleOrNull(),
                matches = ordered,
            )
        }
        .sortedWith(compareBy({ it.matches.first().kickoffAt }, { it.leagueName }))

    private fun LocalDate.toChip() = DayChip(
        date = this,
        weekday = WEEKDAY_FORMAT.format(this),
        dayOfMonth = dayOfMonth.toString(),
        isToday = this == today,
    )

    private fun monthLabel(date: LocalDate): String =
        if (date.year == today.year) MONTH_FORMAT.format(date) else MONTH_YEAR_FORMAT.format(date)

    private fun dateLabel(date: LocalDate): String = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        today.minusDays(1) -> "yesterday"
        else -> "on ${DATE_FORMAT.format(date)}"
    }

    private fun initialState() = MatchesUiState(
        days = days,
        selectedDate = today,
        isOnToday = true,
        monthLabel = monthLabel(today),
        dateLabel = dateLabel(today),
    )

    private fun Throwable.userMessage(): String = when (this) {
        is IOException -> "Couldn't reach the network."
        else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong loading fixtures."
    }

    private data class DayRequest(
        val date: LocalDate,
        val force: Boolean = false,
        /** Makes a repeated pull-to-refresh a new value, so the flow actually restarts. */
        val attempt: Int = 0,
    )

    private data class DayState(
        val date: LocalDate,
        val matches: List<Match> = emptyList(),
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null,
        val sourceMissing: Boolean = false,
    )

    private companion object {
        const val DAYS_BEHIND = 7L
        const val DAYS_AHEAD = 21L
        const val FETCH_DEBOUNCE_MILLIS = 250L

        val WEEKDAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
        val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)
        val MONTH_YEAR_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }
}
