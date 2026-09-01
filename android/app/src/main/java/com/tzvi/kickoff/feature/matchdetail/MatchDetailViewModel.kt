package com.tzvi.kickoff.feature.matchdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.LeagueCoverage
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchPrediction
import com.tzvi.kickoff.core.model.MatchLineups
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.MatchStatistics
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.data.repository.MatchAbsences
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.notifications.MatchTracker
import com.tzvi.kickoff.data.repository.NoFootballSourceException
import com.tzvi.kickoff.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.io.IOException
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MatchDetailViewModel @Inject constructor(
    private val footballRepository: FootballRepository,
    private val matchTracker: MatchTracker,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val matchId: Long = checkNotNull(savedStateHandle.get<Long>(Routes.ARG_MATCH_ID)) {
        "${Routes.MATCH_DETAIL} was opened without a ${Routes.ARG_MATCH_ID} argument"
    }

    private val requests = MutableStateFlow(RefreshRequest())
    /**
     * Opens on the timeline once there is one, and on the preview before there is.
     *
     * Set from the first match state rather than fixed, because landing on an empty
     * timeline is the commonest way into this screen - from a fixture list, hours early.
     */
    private val selectedTab = MutableStateFlow(MatchDetailTab.TIMELINE)
    private var tabChosenByUser = false

    init {
        // One-shot: the first time we learn the match has not started, move to the tab
        // that actually has something on it. Never again after that, and never over a
        // choice the user has already made.
        viewModelScope.launch {
            val first = footballRepository.observeMatch(matchId).filterNotNull().first()
            if (!tabChosenByUser && first.phase == MatchPhase.SCHEDULED) {
                selectedTab.value = MatchDetailTab.PREVIEW
            }
        }
    }


    /**
     * Line-ups and statistics are the one part of a match that Room never holds: only
     * [FootballRepository.refreshMatch] returns them, so they live here rather than in a
     * DAO flow.
     *
     * They are held outside [pollDetail] because a pull-to-refresh restarts that flow:
     * starting it from a blank state would empty the pitch and the stat bars for the
     * length of the call the user pulled to make.
     */
    private val retained = MutableStateFlow(DetailState(isLoading = true))

    private val details: Flow<DetailState> =
        requests.flatMapLatest { request -> pollDetail(request) }

    /**
     * The pre-match read, fetched once per match rather than polled.
     *
     * The provider recomputes predictions hourly and head-to-head never changes, so this
     * deliberately does not ride [requests]: a pull-to-refresh is asking for a fresher
     * score, not for the same percentages again. Keyed on the league so it also picks up
     * that competition's coverage flags, which is what tells the line-ups tab whether an
     * empty XI is "not yet" or "never".
     */
    private val preMatch: Flow<PreMatchState> = footballRepository.observeMatch(matchId)
        .map { it?.leagueId to (it?.home?.id to it?.away?.id) }
        .distinctUntilChanged()
        .flatMapLatest { (leagueId, teams) ->
            val (homeId, awayId) = teams
            flow {
                if (leagueId == null) {
                    emit(PreMatchState())
                    return@flow
                }
                val coverage = footballRepository.leagueCoverage(leagueId)
                emit(PreMatchState(coverage = coverage, isLoading = coverage?.predictions != false))
                if (coverage?.predictions == false) return@flow
                val prediction = footballRepository.predictions(matchId)
                val h2h = if (homeId != null && awayId != null) {
                    footballRepository.headToHead(homeId, awayId)
                } else {
                    emptyList()
                }
                emit(PreMatchState(coverage = coverage, prediction = prediction, headToHead = h2h))
            }
        }

    // Six sources against combine's five typed slots, so the two per-match fetches travel
    // together rather than dropping the lambda to Array<Any>.
    private val detailAndPreview = combine(details, preMatch, ::Pair)

    val uiState: StateFlow<MatchDetailUiState> = combine(
        footballRepository.observeMatch(matchId),
        footballRepository.observeEvents(matchId),
        detailAndPreview,
        selectedTab,
        matchTracker.isTracking(matchId),
    ) { match, events, (detail, preview), tab, following ->
        MatchDetailUiState(
            matchId = matchId,
            match = match,
            timeline = timelineOf(events),
            lineups = detail.lineups,
            absences = detail.absences,
            stats = detail.statistics.toComparisons(),
            prediction = preview.prediction,
            predictionLoading = preview.isLoading,
            headToHead = preview.headToHead,
            coverage = preview.coverage,
            selectedTab = tab,
            // Coming in from a fixture list the match is already cached, so the screen
            // only ever shows the full-page loader on a genuinely cold open.
            isLoading = detail.isLoading && match == null,
            isRefreshing = detail.isRefreshing,
            errorMessage = detail.errorMessage,
            sourceMissing = detail.sourceMissing,
            following = following,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MatchDetailUiState(matchId = matchId),
    )

    /**
     * Starts or stops the live card for this match by hand.
     *
     * The card normally arrives on its own an hour before kick-off. This is the way in
     * for a match nobody was following when that alarm would have been set, and the way
     * back for one the user swiped away - a dismissal is sticky by design, so re-following
     * has to lift it explicitly.
     */
    fun toggleFollowing() {
        viewModelScope.launch {
            if (uiState.value.following) matchTracker.unfollow(matchId)
            else matchTracker.follow(matchId)
        }
    }

    fun selectTab(tab: MatchDetailTab) {
        tabChosenByUser = true
        selectedTab.value = tab
    }

    fun refresh() {
        requests.update { RefreshRequest(attempt = it.attempt + 1, fromUser = true) }
    }

    /**
     * One fetch when the screen opens, then - only while the match is actually in play -
     * another every [LIVE_POLL_MILLIS].
     *
     * 30s is the provider's own live-feed cadence, and every call is billed against a
     * daily allowance: a full 90 minutes costs about 180 requests at 30s against roughly
     * 540 at 10s, for updates that would not exist yet. The loop is part of the state
     * flow's upstream rather than a separate `viewModelScope.launch`, so WhileSubscribed
     * tears it down the moment the screen stops being collected instead of polling on
     * behalf of a match nobody is looking at.
     */
    private fun pollDetail(request: RefreshRequest): Flow<DetailState> = flow {
        emit(retained.updateAndGet { it.copy(isLoading = true, isRefreshing = request.fromUser) })
        var live = false
        while (true) {
            val state = try {
                val detail = footballRepository.refreshMatch(matchId).detail
                live = detail.match.isLive
                retained.updateAndGet {
                    it.copy(
                        // A poll that came back without line-ups has not withdrawn the
                        // ones already published, it simply did not carry them again.
                        lineups = detail.lineups ?: it.lineups,
                        absences = detail.absences ?: it.absences,
                        statistics = detail.statistics ?: it.statistics,
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = null,
                        sourceMissing = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                // Left to a blanket catch this would surface the screen's own teardown as
                // a failed refresh, and swallow the cancellation the caller is waiting on.
                throw cancellation
            } catch (error: Throwable) {
                // A blip mid-match keeps the loop alive - `live` still holds the last
                // phase actually seen - but a missing source never will, so that one
                // stops immediately rather than failing every 30 seconds forever.
                val missingSource = error is NoFootballSourceException
                if (missingSource) live = false
                retained.updateAndGet {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (missingSource) null else error.userMessage(),
                        sourceMissing = missingSource,
                    )
                }
            }
            emit(state)
            if (!live) break
            delay(LIVE_POLL_MILLIS)
        }
    }

    /**
     * Newest first, each incident carrying the scoreline as it stood after it.
     *
     * The total is walked forward from kick-off because the provider fills
     * [MatchEvent.scoreAfter] in only for some events; where it is set it wins, since a
     * VAR correction lands there before it lands anywhere else.
     */
    private fun timelineOf(events: List<MatchEvent>): List<TimelineEntry> {
        var home = 0
        var away = 0
        return events
            .sortedWith(compareBy({ it.minute ?: 0 }, { it.extraMinute ?: 0 }))
            .map { event ->
                val stated = event.scoreAfter
                when {
                    stated != null -> {
                        home = stated.home
                        away = stated.away
                    }
                    event.type.isGoal -> {
                        // An own goal is credited to the other side.
                        val scoredByHome = (event.side == MatchSide.HOME) !=
                            (event.type == MatchEventType.OWN_GOAL)
                        if (scoredByHome) home++ else away++
                    }
                }
                TimelineEntry(event = event, runningScore = Score(home, away))
            }
            .reversed()
    }

    private fun MatchStatistics?.toComparisons(): List<StatComparison> {
        val statistics = this ?: return emptyList()
        return MatchStatistics.HIGHLIGHTS.mapNotNull { key ->
            val (rawHome, rawAway) = statistics.pair(key) ?: return@mapNotNull null
            val homeValue = rawHome.toStatValue() ?: return@mapNotNull null
            val awayValue = rawAway.toStatValue() ?: return@mapNotNull null
            val total = homeValue + awayValue
            StatComparison(
                label = STAT_LABELS[key] ?: key,
                homeLabel = rawHome.trim(),
                awayLabel = rawAway.trim(),
                homeFraction = if (total > 0f) homeValue / total else 0f,
                awayFraction = if (total > 0f) awayValue / total else 0f,
            )
        }
    }

    /** "55%", "1.42" and "12" all parse; "-", "" and null do not, and drop the row. */
    private fun String.toStatValue(): Float? =
        trim().removeSuffix("%").replace(',', '.').toFloatOrNull()?.takeIf { it >= 0f }

    private fun Throwable.userMessage(): String = when (this) {
        is IOException -> "Couldn't reach the network."
        else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong loading this match."
    }

    private data class RefreshRequest(
        /** Makes a repeated pull-to-refresh a new value, so the flow actually restarts. */
        val attempt: Int = 0,
        val fromUser: Boolean = false,
    )

    private data class PreMatchState(
        val coverage: LeagueCoverage? = null,
        val prediction: MatchPrediction? = null,
        val headToHead: List<Match> = emptyList(),
        val isLoading: Boolean = false,
    )

    private data class DetailState(
        val lineups: MatchLineups? = null,
        val absences: MatchAbsences? = null,
        val statistics: MatchStatistics? = null,
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null,
        val sourceMissing: Boolean = false,
    )

    private companion object {
        const val LIVE_POLL_MILLIS = 30_000L

        val STAT_LABELS = mapOf(
            MatchStatistics.POSSESSION to "Possession",
            MatchStatistics.SHOTS to "Shots",
            MatchStatistics.SHOTS_ON_GOAL to "Shots on target",
            MatchStatistics.FOULS to "Fouls",
            MatchStatistics.CORNERS to "Corners",
            MatchStatistics.OFFSIDES to "Offsides",
            MatchStatistics.XG to "Expected goals",
        )
    }
}
