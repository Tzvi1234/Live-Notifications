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
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
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
    private val tab = MutableStateFlow(MatchesTab.MY_TEAMS)
    private val timelineFilter = MutableStateFlow(TimelineFilter.UPCOMING)
    private val timelineRefreshing = MutableStateFlow(false)

    /**
     * What the provider returned for each date visited in this session.
     *
     * It is not only a quota cache. [FootballRepository.matchesOn] windows its query on a team
     * filter, so it answers "which of the teams I passed play on this date" rather than "what is
     * on"; without this map a competition the user follows nobody in never reaches the screen.
     * Room still wins per fixture in [heldOn], because that is the row the live pipeline updates.
     *
     * API-Football also bills per call against a daily allowance, so a date the user has already
     * visited is served from here until they explicitly pull to refresh.
     */
    private val fetched = mutableMapOf<LocalDate, List<Match>>()

    private val dayStates: Flow<DayState> = request.flatMapLatest { loadDay(it) }

    /**
     * The followed teams' whole run, already cut and grouped.
     *
     * Kept separate from [dayStates] because it answers a different question and reloads
     * on a different trigger: the date browser refetches whenever you move the strip,
     * this only when the followed teams change or the user pulls.
     */
    private val timelineState: Flow<TimelineState> = combine(
        footballRepository.favouriteTimeline,
        timelineFilter,
        timelineRefreshing,
    ) { matches, filter, refreshing ->
        TimelineState(
            filter = filter,
            sections = matches.applyTimeline(filter),
            total = matches.size,
            isRefreshing = refreshing,
        )
    }

    private val dayFace: Flow<DayFace> = combine(
        request.map { it.date }.distinctUntilChanged(),
        dayStates,
        selectedFilter,
    ) { date, day, filter -> DayFace(date, day, filter) }

    val uiState: StateFlow<MatchesUiState> = combine(
        dayFace,
        timelineState,
        tab,
        footballRepository.favouriteTeamIds,
    ) { face, timeline, currentTab, favouriteIds ->
        val (date, day, filter) = face
        // The selected date moves the instant a chip is tapped, while the day's fixtures
        // arrive a beat later; until they match, the previous date's list is not shown.
        val settled = day.date == date
        val matches = if (settled) day.matches else emptyList()
        val favourites = favouriteIds.toSet()
        MatchesUiState(
            tab = currentTab,
            timelineFilter = timeline.filter,
            timeline = timeline.sections,
            // Nothing held and a fetch in flight is the only state that deserves a
            // spinner; nothing held and nothing running is an empty state with a reason.
            timelineLoading = timeline.total == 0 && timeline.isRefreshing,
            timelineRefreshing = timeline.isRefreshing,
            timelineTotal = timeline.total,
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

    fun selectTab(next: MatchesTab) {
        tab.value = next
        if (next == MatchesTab.MY_TEAMS) refreshTimeline()
    }

    fun selectTimelineFilter(filter: TimelineFilter) {
        timelineFilter.value = filter
        // Results only exist once the window has actually been fetched behind today, and
        // the routine refresh only reaches two days back.
        if (filter != TimelineFilter.UPCOMING) refreshTimeline()
    }

    /**
     * Pulls the followed teams' window, history included.
     *
     * Guarded rather than debounced: this is several provider calls (one per team), so a
     * second tap while the first is in flight has to be dropped outright, not queued.
     */
    fun refreshTimeline() {
        if (timelineRefreshing.value) return
        viewModelScope.launch {
            timelineRefreshing.value = true
            runCatching {
                footballRepository.refreshFixtures(
                    daysAhead = TIMELINE_DAYS_AHEAD,
                    daysBehind = TIMELINE_DAYS_BEHIND,
                )
            }
            timelineRefreshing.value = false
        }
    }

    fun refresh() {
        request.update { DayRequest(it.date, force = true, attempt = it.attempt + 1) }
    }

    /** Whatever is already held, then the network, then everything held again. */
    private fun loadDay(dayRequest: DayRequest): Flow<DayState> = flow {
        val date = dayRequest.date
        val held = heldOn(date)
        if (!dayRequest.force && date in fetched) {
            emit(DayState(date, held))
            return@flow
        }

        emit(
            DayState(
                date = date,
                matches = held,
                isLoading = held.isEmpty(),
                isRefreshing = held.isNotEmpty(),
            ),
        )

        // Dragging along the week strip cancels this flow before the delay elapses, so a
        // fast scrub through ten dates costs one request rather than ten.
        delay(FETCH_DEBOUNCE_MILLIS)

        val outcome = runCatching { footballRepository.refreshDay(date) }
        emit(
            outcome.fold(
                onSuccess = { provided ->
                    fetched[date] = provided.startingOn(date)
                    DayState(date, heldOn(date))
                },
                onFailure = { error ->
                    DayState(
                        date = date,
                        matches = held,
                        errorMessage = if (error is NoFootballSourceException) null
                        else error.userMessage(),
                        sourceMissing = error is NoFootballSourceException,
                    )
                },
            ),
        )
    }

    /** Room's row for a fixture wins over the fetched one: it is the copy that keeps moving. */
    private suspend fun heldOn(date: LocalDate): List<Match> {
        val cached = runCatching { footballRepository.matchesOn(date) }.getOrDefault(emptyList())
        val cachedIds = cached.mapTo(mutableSetOf()) { it.id }
        return cached + fetched[date].orEmpty().filterNot { it.id in cachedIds }
    }

    /**
     * The provider answers on its own calendar day, which is not the device's: a 20:00 kick-off
     * in Los Angeles belongs to the strip's next chip for a reader in Tel Aviv.
     */
    private fun List<Match>.startingOn(date: LocalDate): List<Match> {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant()
        return filter { it.kickoffAt >= from && it.kickoffAt < to }
    }

    private fun List<Match>.applyFilter(mode: MatchFilter, favourites: Set<Int>): List<Match> =
        when (mode) {
            MatchFilter.ALL -> this
            MatchFilter.MY_TEAMS -> filter { it.home.id in favourites || it.away.id in favourites }
            MatchFilter.LIVE -> filter { it.phase.isLive }
        }

    /**
     * Upcoming runs forward from now, history runs backward from it.
     *
     * A results list that reads oldest-first buries the match you actually want - the one
     * that just finished - at the bottom of a season's scroll.
     */
    private fun List<Match>.applyTimeline(filter: TimelineFilter): List<TimelineSection> {
        val now = Instant.now()
        val cut = when (filter) {
            TimelineFilter.UPCOMING -> filter { it.kickoffAt >= now || it.phase.isLive }
            TimelineFilter.RESULTS -> filter { it.kickoffAt < now && !it.phase.isLive }
            TimelineFilter.ALL -> this
        }
        val newestFirst = filter != TimelineFilter.UPCOMING
        val ordered = if (newestFirst) {
            cut.sortedWith(compareByDescending<Match> { it.kickoffAt }.thenBy { it.id })
        } else {
            cut.sortedWith(compareBy({ it.kickoffAt }, { it.id }))
        }
        val zone = ZoneId.systemDefault()
        return ordered
            .groupBy { it.kickoffAt.atZone(zone).toLocalDate() }
            .map { (date, matches) ->
                TimelineSection(
                    key = date.toString(),
                    header = sectionHeader(date),
                    matches = matches,
                )
            }
    }

    private fun sectionHeader(date: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> SECTION_FORMAT.format(date)
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

    private data class DayFace(
        val date: LocalDate,
        val day: DayState,
        val filter: MatchFilter,
    )

    private data class TimelineState(
        val filter: TimelineFilter,
        val sections: List<TimelineSection>,
        val total: Int,
        val isRefreshing: Boolean,
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
        const val TIMELINE_DAYS_AHEAD = 45L
        const val TIMELINE_DAYS_BEHIND = 150L

        val WEEKDAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
        val SECTION_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
        val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)
        val MONTH_YEAR_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }
}
