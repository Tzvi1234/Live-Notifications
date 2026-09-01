package com.tzvi.kickoff.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.tzvi.kickoff.work.MatchAlarmScheduler
import com.tzvi.kickoff.core.model.mayFollowAutomatically
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
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
    @Inject lateinit var alarms: MatchAlarmScheduler

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val tracked = ConcurrentHashMap<Long, Job>()

    /**
     * The number of matches the anchor notification currently claims to be following.
     *
     * Kept so the anchor is only re-posted when its text would actually change: re-posting
     * the same id is harmless, but doing it on every poll tick of every match is noise the
     * notification shelf animates.
     */
    @Volatile private var anchoredCount: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationChannels.ensureCreated(this)
        // Within a few seconds of the service starting, before any network round trip.
        postAnchor()

        when (intent?.action) {
            ACTION_TRACK -> intent.getLongExtra(EXTRA_MATCH_ID, -1L)
                .takeIf { it > 0 }
                ?.let { track(it, manual = intent.getBooleanExtra(EXTRA_MANUAL, false)) }

            ACTION_UNTRACK -> {
                val id = intent.getLongExtra(EXTRA_MATCH_ID, -1L)
                tracked.remove(id)?.cancel()
                // The card is an ordinary notification now, so cancelling it works. While
                // it held the foreground-service role the platform refused this call and
                // the card stayed on screen after the user asked for it to go.
                notifier.cancel(LiveActivity.MatchActivity.matchKey(id))
                postAnchor()
                stopIfIdle()
            }

            ACTION_STOP -> stopEverything()
        }

        // Unconditionally, on every path. A start command that tracks nothing - a malformed
        // intent, an id already being followed, a null intent after a process restart - used
        // to leave the service foreground with an anchor and nothing driving it, which is
        // what put a context-free "Starting soon" on the shelf at random moments. The anchor
        // is only ever allowed to exist while something is actually being followed.
        stopIfIdle()

        // NOT sticky, for the same reason: a restart with a null intent would run with an
        // empty tracked map. The alarms and the sweep worker are what bring tracking back
        // after a process death.
        return START_NOT_STICKY
    }

    /**
     * The service's own notification, on a FIXED id, for as long as the service lives.
     *
     * It used to hand this role over to a real match card as soon as one existed, to avoid
     * a second "matchUP is running" notification sitting beside the scoreboard. That was a
     * mistake, and an expensive one: attaching the foreground-service role to a card gives
     * the PLATFORM ownership of it, and the platform enforces one notification per service.
     * `startForeground` with a different id makes it cancel whatever held the role before
     * (`ActiveServices`: `if (r.foregroundId != id) { cancelForegroundNotificationLocked(r) }`).
     * So with two matches tracked, each one's poll tick tore down the other's card, twenty
     * seconds apart, for as long as both were live - cards appearing and vanishing on a
     * rolling cadence, which is exactly what was reported. Worse, an app cannot cancel its
     * own foreground-service notification, so unfollowing a match left its card stuck.
     *
     * One fixed anchor, never moved, is the boring correct shape: the match cards are then
     * ordinary notifications the app fully owns, and can update and cancel freely.
     */
    private fun postAnchor() {
        val count = tracked.size
        if (anchoredCount == count) return

        // Count zero is a few hundred milliseconds between startForeground - which Android
        // requires immediately - and the first match being added. It is never a resting
        // state: stopIfIdle() ends the service if nothing follows. The wording still has to
        // mean something for the moment it is visible.
        val text = if (count == 0) {
            getString(com.tzvi.kickoff.R.string.live_preparing)
        } else {
            resources.getQuantityString(com.tzvi.kickoff.R.plurals.live_following, count, count)
        }

        val notification = androidx.core.app.NotificationCompat
            .Builder(this, NotificationChannels.LIVE_MATCH)
            .setSmallIcon(com.tzvi.kickoff.R.drawable.ic_stat_kickoff)
            .setContentTitle(getString(com.tzvi.kickoff.R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            // Below the match cards it sits beside: this one is bookkeeping, they are the
            // thing the user came for.
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setGroup(ANCHOR_GROUP)
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
        anchoredCount = count
    }

    private fun track(matchId: Long, manual: Boolean) {
        if (tracked.containsKey(matchId)) return
        tracked[matchId] = scope.launch {
            try {
                pollLoop(matchId, manual)
            } finally {
                tracked.remove(matchId)
                postAnchor()
                stopIfIdle()
            }
        }
        postAnchor()
    }

    /**
     * The per-match loop.
     *
     * Cadence follows the match rather than the clock: slow while counting down, fast
     * once the ball is rolling, and a single final pass after the whistle. Every refresh
     * returns only the events this device has not seen, so an event alerts exactly once
     * regardless of whether polling or a push delivered it first.
     */
    private suspend fun pollLoop(matchId: Long, manual: Boolean) {
        var finishedAt: Instant? = null
        var lastPhase: MatchPhase? = null
        var failures = 0
        // Set once a card exists, so the exits below take it down rather than leaving a
        // frozen scoreline on the shelf. Every break used to leak one.
        var cardKey: String? = null

        while (currentCoroutineContext().isActive) {
            val config = settings.settings.first()
            val refresh = runCatching { repository.refreshMatch(matchId) }.getOrNull()

            if (refresh != null) {
                val match = refresh.detail.match

                // Both of these run BEFORE anything is posted, which is the whole point.
                // The stand-down check used to sit after the card had already gone out, so
                // a tick that stood down posted a notification and cancelled it in the
                // same breath - and FixtureSyncWorker arms an alarm for every upcoming
                // match, firing five seconds later for any match already inside its
                // window, which turned one sync into a screenful of cards appearing and
                // vanishing.
                if (!manual && !followsAFavourite(match)) {
                    cardKey?.let(notifier::cancel)
                    break
                }

                // Nothing is drawn for a match that is still an hour away. The alarm is
                // re-armed for the moment it is worth watching instead.
                // A postponed or abandoned match is neither live nor finished and its
                // kick-off is in the past, so it slipped past the stand-down below and sat
                // on "Starting soon" for ever, polling once a minute. It is never going to
                // start; there is nothing to watch.
                if (match.phase == MatchPhase.OFF) {
                    cardKey?.let(notifier::cancel)
                    break
                }

                val untilKickoff = Duration.between(Instant.now(), match.kickoffAt)
                if (!match.isLive && !match.phase.isFinished && untilKickoff > STAND_DOWN_BEFORE) {
                    alarms.schedule(match, STAND_DOWN_BEFORE.toMinutes().toInt())
                    cardKey?.let(notifier::cancel)
                    break
                }

                val stage = stageOf(match)
                val whistle = whistleEvent(lastPhase, match)
                lastPhase = match.phase
                val alerting = (refresh.newEvents + listOfNotNull(whistle))
                    .filter { it.shouldAlert(config) }

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

                cardKey = activity.key
                failures = 0

                if (match.phase.isFinished) {
                    if (finishedAt == null) finishedAt = Instant.now()
                    if (Duration.between(finishedAt, Instant.now()) > FULL_TIME_LINGER) {
                        notifier.cancel(activity.key)
                        break
                    }
                } else {
                    finishedAt = null
                }
                delay(intervalFor(match))
            } else {
                // A refresh that never succeeds used to retry every forty-five seconds for
                // ever, holding the service in the foreground with the anchor on screen and
                // nothing under it - the context-free "matchUP" notification that would not
                // go away. Give up rather than sit there; the sweep and the alarms will
                // bring it back when there is something to show.
                failures += 1
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    cardKey?.let(notifier::cancel)
                    break
                }
                delay(ERROR_BACKOFF_MS)
            }
        }

        tracked.remove(matchId)
        stopIfIdle()
    }

    /**
     * Kick-off, half time and full time, derived rather than received.
     *
     * API-Football's `fixtures/events` only ever carries goals, cards, subs and VAR - the
     * three whistles are not events there, they are status changes on the fixture. So the
     * "Kick-off & full time" alert had nothing to fire on and was silently dead on the
     * direct-API path. Watching `phase` change across two polls is where that information
     * actually lives.
     *
     * [previous] being null means this is the loop's first pass and we have not seen a
     * transition, only a state - joining a match already in play must not announce a
     * kick-off that happened an hour ago.
     */
    private fun whistleEvent(previous: MatchPhase?, match: Match): MatchEvent? {
        if (previous == null || previous == match.phase) return null
        val type = when {
            match.phase.isFinished && previous.isLive -> MatchEventType.FULL_TIME
            match.phase == MatchPhase.HALF_TIME -> MatchEventType.HALF_TIME
            match.phase.isLive && !previous.isLive && previous != MatchPhase.HALF_TIME ->
                MatchEventType.KICK_OFF
            else -> return null
        }
        return MatchEvent(
            // Keyed off the phase it announces, so the same whistle observed twice - a
            // service restart, a status that flickers back and forth - stays one event.
            id = "${match.id}:whistle:${type.name}",
            matchId = match.id,
            type = type,
            side = MatchSide.NEUTRAL,
            teamId = null,
            teamName = null,
            minute = match.elapsedMinutes,
            extraMinute = null,
            playerName = null,
            assistName = null,
            detail = null,
            comment = null,
            scoreAfter = match.score,
        )
    }

    /**
     * The last gate before a card is drawn: is this a match the user actually follows?
     *
     * Checked here as well as in the sweep worker because tracking can start from three
     * places - the sweep, a pre-match alarm, a tap on a match screen - and only two of
     * them know anything about favourites. A match that is not one of theirs must never
     * reach the notification shelf, whichever door it came through.
     */
    private suspend fun followsAFavourite(match: Match): Boolean =
        mayFollowAutomatically(match, repository.favouriteIdsNow())


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
        type == MatchEventType.FULL_TIME || type == MatchEventType.KICK_OFF ||
            type == MatchEventType.HALF_TIME -> config.notifyKickoffAndFullTime
        else -> false
    }

    private fun MatchEvent.priority(): Int = when (type) {
        MatchEventType.GOAL, MatchEventType.PENALTY_GOAL, MatchEventType.OWN_GOAL -> 100
        MatchEventType.RED_CARD, MatchEventType.SECOND_YELLOW -> 80
        MatchEventType.PENALTY_MISSED -> 70
        MatchEventType.FULL_TIME -> 60
        MatchEventType.KICK_OFF, MatchEventType.HALF_TIME -> 50
        MatchEventType.YELLOW_CARD -> 40
        else -> 10
    }

    private fun stopIfIdle() {
        if (tracked.isEmpty()) stopEverything()
    }

    private fun stopEverything() {
        tracked.values.forEach(Job::cancel)
        tracked.clear()
        anchoredCount = -1
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

        /** Keeps the anchor out of the match cards' shelf position. */
        private const val ANCHOR_GROUP = "com.tzvi.kickoff.live.anchor"
        private const val ACTION_TRACK = "com.tzvi.kickoff.action.TRACK_MATCH"
        private const val ACTION_UNTRACK = "com.tzvi.kickoff.action.UNTRACK_MATCH"
        private const val ACTION_STOP = "com.tzvi.kickoff.action.STOP_TRACKING"
        private const val EXTRA_MATCH_ID = "match_id"
        private const val EXTRA_MANUAL = "manual"

        private const val LIVE_INTERVAL_MS = 20_000L
        private const val IMMINENT_INTERVAL_MS = 60_000L
        private const val PRE_MATCH_INTERVAL_MS = 5 * 60_000L
        private const val FULL_TIME_INTERVAL_MS = 60_000L
        private const val ERROR_BACKOFF_MS = 45_000L

        /** Roughly four minutes of failing before the service stands down. */
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private val FULL_TIME_LINGER: Duration = Duration.ofMinutes(10)

        /**
         * How close to kick-off the service is willing to stay up.
         *
         * Beyond this the alarm is the right mechanism and a running foreground service is
         * the wrong one: it costs battery and shows a notification about a match that is
         * still an hour away.
         */
        private val STAND_DOWN_BEFORE: Duration = Duration.ofMinutes(12)

        /**
         * Starts following a match.
         *
         * [manual] separates the two reasons this is ever called, and they need different
         * rules. Automatic tracking - the sweep worker, a pre-match alarm - must only ever
         * touch a followed club: that is the user's standing instruction and the thing
         * they asked for after being notified about strangers. A manual follow is the user
         * tapping "follow this match" on a screen they navigated to on purpose, and
         * refusing that because the club is not a favourite would break the feature.
         */
        fun track(context: Context, matchId: Long, manual: Boolean = false) {
            val intent = Intent(context, LiveMatchService::class.java)
                .setAction(ACTION_TRACK)
                .putExtra(EXTRA_MATCH_ID, matchId)
                .putExtra(EXTRA_MANUAL, manual)
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
