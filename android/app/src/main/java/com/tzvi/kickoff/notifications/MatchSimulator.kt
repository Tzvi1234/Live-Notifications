package com.tzvi.kickoff.notifications

import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.core.model.LiveCardStyle
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.data.demo.DemoCatalogue
import com.tzvi.kickoff.data.local.dao.MatchDao
import com.tzvi.kickoff.data.local.dao.MatchEventDao
import com.tzvi.kickoff.data.local.dao.TrackedActivityDao
import com.tzvi.kickoff.data.local.toEntity
import com.tzvi.kickoff.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a whole match in about three minutes.
 *
 * This is not a mock of the notification: it drives the real pipeline. Each tick writes
 * the new scoreline into Room and pushes the events through the same insert-ignore gate a
 * live poll uses, then posts through [LiveActivityNotifier]. So the live card, the status
 * bar chip, the Dynamic Island, the Today screen and the match timeline all update from
 * one source exactly as they would during a real game — which is the only way a demo is
 * worth anything.
 *
 * Ninety match-minutes are compressed into [MATCH_DURATION_MS]; half-time is a real pause
 * because the card looks different during it.
 */
@Singleton
class MatchSimulator @Inject constructor(
    private val notifier: LiveActivityNotifier,
    private val settings: SettingsRepository,
    private val matchDao: MatchDao,
    private val eventDao: MatchEventDao,
    private val trackedActivityDao: TrackedActivityDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val running: Boolean = false,
        val minute: Int = 0,
        val score: Score = Score(0, 0),
        val lastEvent: String? = null,
    )

    /** One scripted incident. [minute] is a match minute, not a wall-clock offset. */
    private data class Beat(
        val minute: Int,
        val type: MatchEventType,
        val side: MatchSide,
        val player: String,
        val assist: String? = null,
    )

    /**
     * The script covers every symbol the live card can draw - goal, penalty scored,
     * penalty missed, yellow, red - on both sides, because the bar's marks and its
     * tracker icon are otherwise only visible during a real match with the right things
     * happening in it.
     *
     * No player repeats an incident type: the demo event key is (type, team, player),
     * so a second Saka goal would dedupe against the first and never reach the card.
     */
    private val script = listOf(
        Beat(9, MatchEventType.YELLOW_CARD, MatchSide.AWAY, "Cucurella"),
        Beat(18, MatchEventType.GOAL, MatchSide.HOME, "Saka", "Ødegaard"),
        Beat(31, MatchEventType.GOAL, MatchSide.AWAY, "Palmer", "Neto"),
        Beat(44, MatchEventType.YELLOW_CARD, MatchSide.HOME, "Rice"),
        Beat(52, MatchEventType.SUBSTITUTION, MatchSide.HOME, "Trossard", "Martinelli"),
        Beat(58, MatchEventType.PENALTY_GOAL, MatchSide.HOME, "Havertz"),
        Beat(71, MatchEventType.RED_CARD, MatchSide.AWAY, "Fofana"),
        Beat(79, MatchEventType.PENALTY_MISSED, MatchSide.AWAY, "Jackson"),
        Beat(84, MatchEventType.GOAL, MatchSide.HOME, "Martinelli", "Timber"),
    )

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { play() }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.launch { clear() }
        _state.value = State()
    }

    private suspend fun play() {
        val base = DemoCatalogue.match(DemoCatalogue.LIVE_MATCH_ID) ?: return
        // Kick-off sits in the near future so the pre-match card has a real countdown to
        // show. Once the match starts the clock comes from elapsedMinutes, so this only
        // ever affects the part of the run it is meant to.
        val kickoff = Instant.now().plusSeconds(PRE_MATCH_LEAD_SECONDS)
        val style = settings.settings.first().liveCardStyle
        val key = LiveActivity.MatchActivity.matchKey(SIM_MATCH_ID)

        // A card the user dismissed during an earlier run would never repost, and the
        // simulator would look broken. Starting a run is an explicit request for it back.
        trackedActivityDao.delete(key)
        eventDao.clearForMatch(SIM_MATCH_ID)

        val fired = mutableListOf<MatchEvent>()
        var home = 0
        var away = 0
        var halfTime: Score? = null

        suspend fun render(minute: Int, phase: MatchPhase, alerting: MatchEvent?) {
            val match = base.copy(
                id = SIM_MATCH_ID,
                kickoffAt = kickoff,
                phase = phase,
                elapsedMinutes = minute,
                score = Score(home, away),
                halfTimeScore = halfTime,
            )
            matchDao.upsert(match.toEntity())
            notifier.postMatch(activityFor(match, phase, fired.toList()), style, alerting)
            _state.value = State(
                running = true, minute = minute, score = Score(home, away),
                lastEvent = fired.lastOrNull()?.headline(),
            )
        }

        // Pre-match: the team sheet, held long enough to actually read. This is half the
        // feature and it only exists in the hour before a match, so a run that skipped
        // past it in a couple of seconds never showed it at all.
        repeat(PRE_MATCH_BEATS) {
            if (!currentCoroutineContext().isActive) return
            render(0, MatchPhase.SCHEDULED, null)
            delay(PRE_MATCH_MS / PRE_MATCH_BEATS)
        }

        var minute = 0
        while (currentCoroutineContext().isActive && minute <= REGULATION) {
            val phase = when {
                minute == HALF -> MatchPhase.HALF_TIME
                minute < HALF -> MatchPhase.FIRST_HALF
                else -> MatchPhase.SECOND_HALF
            }

            val beat = script.firstOrNull { it.minute == minute }
            var alerting: MatchEvent? = null
            if (beat != null) {
                if (beat.type.isGoal) {
                    if (beat.side == MatchSide.HOME) home++ else away++
                }
                val event = DemoCatalogue.event(
                    matchId = SIM_MATCH_ID, type = beat.type, side = beat.side,
                    minute = beat.minute, player = beat.player, assist = beat.assist,
                    score = Score(home, away),
                )
                // The same gate the poller uses, so a restart mid-run cannot double-alert.
                if (eventDao.insertNew(listOf(event.toEntity())).isNotEmpty()) {
                    fired += event
                    if (beat.type.isGoal || beat.type == MatchEventType.RED_CARD) alerting = event
                }
            }

            if (minute == HALF) halfTime = Score(home, away)
            render(minute, phase, alerting)
            delay(if (minute == HALF) HALF_TIME_MS else MINUTE_MS)
            minute++
        }

        if (!currentCoroutineContext().isActive) return
        render(REGULATION, MatchPhase.FINISHED, null)
        _state.value = _state.value.copy(running = false)
    }

    private fun activityFor(match: Match, phase: MatchPhase, events: List<MatchEvent>) =
        LiveActivity.MatchActivity(
            match = match,
            stage = when {
                phase.isFinished -> LiveActivity.MatchActivity.Stage.FULL_TIME
                phase.isLive -> LiveActivity.MatchActivity.Stage.LIVE
                else -> LiveActivity.MatchActivity.Stage.PRE_MATCH
            },
            lineups = if (phase == MatchPhase.SCHEDULED) {
                DemoCatalogue.lineups(DemoCatalogue.LIVE_MATCH_ID)
            } else {
                null
            },
            recentEvents = events,
            statistics = if (phase.isLive) DemoCatalogue.statistics(SIM_MATCH_ID) else null,
        )

    private suspend fun clear() {
        val key = LiveActivity.MatchActivity.matchKey(SIM_MATCH_ID)
        notifier.cancel(key)
        trackedActivityDao.delete(key)
        eventDao.clearForMatch(SIM_MATCH_ID)
        matchDao.delete(SIM_MATCH_ID)
    }

    companion object {
        /** Its own negative id, so a run never collides with the static demo fixture. */
        const val SIM_MATCH_ID = -900L

        private const val REGULATION = Match.REGULATION_MINUTES
        private const val HALF = Match.HALF_MINUTES

        /** ~3 minutes of wall clock for 90 match minutes, plus the two pauses. */
        private const val MINUTE_MS = 1_800L
        private const val HALF_TIME_MS = 6_000L
        private const val PRE_MATCH_MS = 15_000L
        private const val PRE_MATCH_BEATS = 3
        private const val PRE_MATCH_LEAD_SECONDS = 45L * 60L
        const val MATCH_DURATION_MS = REGULATION * MINUTE_MS + HALF_TIME_MS + PRE_MATCH_MS
    }
}
