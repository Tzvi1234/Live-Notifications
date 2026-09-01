package com.tzvi.kickoff.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Keeps live cards ticking for the matches currently being followed.
 *
 * One service tracks every in-play match at once rather than one service per match, so
 * the foreground-service budget is spent once. It exits as soon as the last match ends,
 * which also keeps it far away from Android 15's six-hour `dataSync` ceiling - and
 * [onTimeout] handles the case where it does not.
 */
@AndroidEntryPoint
class LiveMatchService : Service() {

    @Inject lateinit var repository: FootballRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var notifier: LiveActivityNotifier
    @Inject lateinit var builder: MatchNotificationBuilder

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val tracked = ConcurrentHashMap<Long, Job>()

    /** The notification currently acting as the foreground-service notification. */
    @Volatile private var adoptedForegroundId: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationChannels.ensureCreated(this)
        promoteToForeground()

        when (intent?.action) {
            ACTION_TRACK -> intent.getLongExtra(EXTRA_MATCH_ID, -1L)
                .takeIf { it > 0 }
                ?.let(::track)

            ACTION_UNTRACK -> {
                val id = intent.getLongExtra(EXTRA_MATCH_ID, -1L)
                tracked.remove(id)?.cancel()
                val key = LiveActivity.MatchActivity.matchKey(id)
                releaseCard(key.hashCode() and 0x7FFFFFFF)
                notifier.cancel(key)
                stopIfIdle()
            }

            ACTION_STOP -> stopEverything()
        }
        return START_STICKY
    }

    /**
     * A placeholder is posted immediately: `startForeground` must be called within a few
     * seconds of the service starting, long before the first network round trip lands.
     * As soon as a real match card exists, [adoptAsForeground] replaces it, so the user
     * never ends up with a redundant "Kickoff is running" notification sitting beside
     * the scoreboard for ninety minutes.
     */
    private fun promoteToForeground() {
        val notification = androidx.core.app.NotificationCompat
            .Builder(this, NotificationChannels.LIVE_MATCH)
            .setSmallIcon(com.tzvi.kickoff.R.drawable.ic_stat_kickoff)
            .setContentTitle(getString(com.tzvi.kickoff.R.string.app_name))
            .setContentText(getString(com.tzvi.kickoff.R.string.live_starting_soon))
            .setOngoing(true)
            .setSilent(true)
            .build()

        ServiceCompat.startForeground(
            this,
            FOREGROUND_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Hands the foreground-service role to a real match card.
     *
     * Calling `startForeground` again with a different id moves the role rather than
     * adding a second notification, so the placeholder can then be cancelled.
     */
    private fun adoptAsForeground(posted: LiveActivityNotifier.Posted) {
        if (adoptedForegroundId == posted.notificationId) return
        runCatching {
            ServiceCompat.startForeground(
                this,
                posted.notificationId,
                posted.notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        }.onSuccess {
            adoptedForegroundId = posted.notificationId
            NotificationManagerCompat.from(this).cancel(FOREGROUND_ID)
        }
    }

    private fun track(matchId: Long) {
        if (tracked.containsKey(matchId)) return
        tracked[matchId] = scope.launch { pollLoop(matchId) }
    }

    /**
     * The per-match loop.
     *
     * Cadence follows the match rather than the clock: slow while counting down, fast
     * once the ball is rolling, and a single final pass after the whistle. Every refresh
     * returns only the events this device has not seen, so an event alerts exactly once
     * regardless of whether polling or a push delivered it first.
     */
    private suspend fun pollLoop(matchId: Long) {
        var finishedAt: Instant? = null

        while (scope.isActive) {
            val config = settings.settings.first()
            val refresh = runCatching { repository.refreshMatch(matchId) }.getOrNull()

            if (refresh != null) {
                val match = refresh.detail.match
                val stage = stageOf(match)
                val alerting = refresh.newEvents.filter { it.shouldAlert(config) }

                val activity = LiveActivity.MatchActivity(
                    match = match,
                    stage = stage,
                    lineups = refresh.detail.lineups,
                    recentEvents = refresh.allEvents,
                    statistics = refresh.detail.statistics,
                    sequence = refresh.detail.sequence,
                )

                val posted = if (alerting.isEmpty()) {
                    notifier.postMatch(activity, config.liveCardStyle)
                } else {
                    // The most significant new event interrupts once; the silent card
                    // keeps updating underneath it.
                    val headline = alerting.maxByOrNull { it.priority() } ?: alerting.first()
                    notifier.postMatch(activity, config.liveCardStyle, headline).also {
                        repository.markEventsNotified(alerting)
                    }
                }
                posted?.let(::adoptAsForeground)

                if (match.phase.isFinished) {
                    if (finishedAt == null) finishedAt = Instant.now()
                    if (Duration.between(finishedAt, Instant.now()) > FULL_TIME_LINGER) {
                        releaseCard(activity.notificationId)
                        notifier.cancel(activity.key)
                        break
                    }
                } else {
                    finishedAt = null
                }
                delay(intervalFor(match))
            } else {
                delay(ERROR_BACKOFF_MS)
            }
        }

        tracked.remove(matchId)
        stopIfIdle()
    }

    private fun stageOf(match: Match): LiveActivity.MatchActivity.Stage = when {
        match.phase.isFinished -> LiveActivity.MatchActivity.Stage.FULL_TIME
        match.isLive -> LiveActivity.MatchActivity.Stage.LIVE
        else -> LiveActivity.MatchActivity.Stage.PRE_MATCH
    }

    private fun intervalFor(match: Match): Long = when {
        match.isLive -> LIVE_INTERVAL_MS
        match.phase.isFinished -> FULL_TIME_INTERVAL_MS
        Duration.between(Instant.now(), match.kickoffAt) < Duration.ofMinutes(10) ->
            IMMINENT_INTERVAL_MS
        else -> PRE_MATCH_INTERVAL_MS
    }

    private fun MatchEvent.shouldAlert(config: com.tzvi.kickoff.core.model.AppSettings): Boolean = when {
        type.isGoal -> config.notifyGoals
        type.isCard -> config.notifyCards
        type == MatchEventType.SUBSTITUTION -> config.notifySubstitutions
        type == MatchEventType.FULL_TIME || type == MatchEventType.KICK_OFF ->
            config.notifyKickoffAndFullTime
        else -> false
    }

    private fun MatchEvent.priority(): Int = when (type) {
        MatchEventType.GOAL, MatchEventType.PENALTY_GOAL, MatchEventType.OWN_GOAL -> 100
        MatchEventType.RED_CARD, MatchEventType.SECOND_YELLOW -> 80
        MatchEventType.PENALTY_MISSED -> 70
        MatchEventType.FULL_TIME -> 60
        MatchEventType.YELLOW_CARD -> 40
        else -> 10
    }

    /**
     * Called before a card is cancelled.
     *
     * A foreground service must always have a notification. If the card being taken down
     * is the one currently carrying that role, the placeholder is put back so the service
     * is never left foreground with nothing attached; the next tick of whichever match is
     * still running re-adopts its own card.
     */
    private fun releaseCard(notificationId: Int) {
        if (adoptedForegroundId != notificationId) return
        adoptedForegroundId = null
        if (tracked.size > 1) promoteToForeground()
    }

    private fun stopIfIdle() {
        if (tracked.isEmpty()) stopEverything()
    }

    private fun stopEverything() {
        tracked.values.forEach(Job::cancel)
        tracked.clear()
        adoptedForegroundId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Android 15 gives `dataSync` services six hours per day in total and ANRs the app
     * if the callback does not stop the service promptly. Nothing here should ever run
     * that long, but missing the deadline is a crash, so it is handled unconditionally.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopEverything()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_ID = 20_001
        private const val ACTION_TRACK = "com.tzvi.kickoff.action.TRACK_MATCH"
        private const val ACTION_UNTRACK = "com.tzvi.kickoff.action.UNTRACK_MATCH"
        private const val ACTION_STOP = "com.tzvi.kickoff.action.STOP_TRACKING"
        private const val EXTRA_MATCH_ID = "match_id"

        private const val LIVE_INTERVAL_MS = 20_000L
        private const val IMMINENT_INTERVAL_MS = 60_000L
        private const val PRE_MATCH_INTERVAL_MS = 5 * 60_000L
        private const val FULL_TIME_INTERVAL_MS = 60_000L
        private const val ERROR_BACKOFF_MS = 45_000L
        private val FULL_TIME_LINGER: Duration = Duration.ofMinutes(10)

        fun track(context: Context, matchId: Long) {
            val intent = Intent(context, LiveMatchService::class.java)
                .setAction(ACTION_TRACK)
                .putExtra(EXTRA_MATCH_ID, matchId)
            context.startForegroundService(intent)
        }

        fun untrack(context: Context, matchId: Long) {
            val intent = Intent(context, LiveMatchService::class.java)
                .setAction(ACTION_UNTRACK)
                .putExtra(EXTRA_MATCH_ID, matchId)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveMatchService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
