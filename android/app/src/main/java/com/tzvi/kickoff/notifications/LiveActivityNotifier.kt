package com.tzvi.kickoff.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.core.model.LiveCardStyle
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.data.local.dao.TrackedActivityDao
import com.tzvi.kickoff.data.local.entity.TrackedActivityEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single door every live card goes through.
 *
 * Polling, FCM pushes and the foreground service all converge here so that dismissal
 * state, the notify() rate limit and the crest cache are enforced in exactly one place.
 */
@Singleton
class LiveActivityNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val matchBuilder: MatchNotificationBuilder,
    private val calendarBuilder: CalendarNotificationBuilder,
    private val crestLoader: CrestLoader,
    private val capability: LiveUpdateCapability,
    private val trackedActivityDao: TrackedActivityDao,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val postLock = Mutex()

    /** Wall-clock of the last post per notification id, for the rate limit below. */
    private val lastPostAt = mutableMapOf<Int, Long>()

    /** What the last successful post of each activity actually rendered as. */
    private val lastRendering = mutableMapOf<String, MatchNotificationBuilder.Rendering>()

    fun lastRenderingFor(key: String): MatchNotificationBuilder.Rendering? = lastRendering[key]

    /**
     * Post or update a match card.
     *
     * @param alertingEvent set when a goal, red card or full-time whistle should
     *   actually interrupt: it routes the post to the high-importance channel instead
     *   of the silent scoreboard channel.
     * @return true when something was posted.
     */
    suspend fun postMatch(
        activity: LiveActivity.MatchActivity,
        style: LiveCardStyle,
        alertingEvent: MatchEvent? = null,
    ): Boolean = postLock.withLock {
        if (!canPost()) return false
        if (isDismissed(activity.key)) return false

        val id = activity.notificationId
        val alerting = alertingEvent != null
        if (!alerting && isRateLimited(id)) return false

        // Crests are only decoded when the renderer can actually use them; the promoted
        // path shows them as the progress bar's start/end icons, the rich path in the
        // scoreboard, and the plain path not at all.
        val crests = loadCrests(activity, style)
        val result = matchBuilder.build(activity, crests, style, alerting)

        notify(id, result)
        lastRendering[activity.key] = result.rendering
        track(activity)
        true
    }

    suspend fun postCalendar(activity: LiveActivity.CalendarActivity): Boolean =
        postLock.withLock {
            if (!canPost()) return false
            if (isDismissed(activity.key)) return false
            val id = activity.notificationId
            if (isRateLimited(id)) return false

            @SuppressLint("MissingPermission")
            val notification = calendarBuilder.build(activity)
            manager.notify(id, notification)
            lastPostAt[id] = System.currentTimeMillis()
            track(activity)
            true
        }

    fun cancel(key: String) {
        manager.cancel(key.hashCode() and 0x7FFFFFFF)
        lastRendering.remove(key)
    }

    /** True when the system actually promoted the last posted card for [key]. */
    fun isPromoted(key: String): Boolean =
        lastRendering[key] == MatchNotificationBuilder.Rendering.PROMOTED &&
            capability.canPostPromoted()

    // ---- internals -----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun notify(id: Int, result: MatchNotificationBuilder.Result) {
        manager.notify(id, result.notification)
        lastPostAt[id] = System.currentTimeMillis()
    }

    private suspend fun loadCrests(
        activity: LiveActivity.MatchActivity,
        style: LiveCardStyle,
    ): MatchNotificationBuilder.Crests? {
        if (style == LiveCardStyle.PLAIN) return null
        val match = activity.match
        return MatchNotificationBuilder.Crests(
            home = crestLoader.load(match.home.crestUrl, match.home.code),
            away = crestLoader.load(match.away.crestUrl, match.away.code),
            league = match.leagueLogoUrl?.let { crestLoader.load(it, "L") },
        )
    }

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled()

    private suspend fun isDismissed(key: String): Boolean =
        trackedActivityDao.get(key)?.dismissed == true

    /**
     * The platform drops notifications above five enqueues per second per package and
     * simply logs "over rate". Silent score refreshes are throttled well under that;
     * a genuine event bypasses this entirely.
     */
    private fun isRateLimited(id: Int): Boolean {
        val last = lastPostAt[id] ?: return false
        return System.currentTimeMillis() - last < MIN_UPDATE_INTERVAL_MS
    }

    private suspend fun track(activity: LiveActivity) {
        val existing = trackedActivityDao.get(activity.key)
        trackedActivityDao.upsert(
            TrackedActivityEntity(
                key = activity.key,
                kind = when (activity) {
                    is LiveActivity.MatchActivity -> KIND_MATCH
                    is LiveActivity.CalendarActivity -> KIND_CALENDAR
                },
                matchId = (activity as? LiveActivity.MatchActivity)?.match?.id,
                calendarEventId = (activity as? LiveActivity.CalendarActivity)?.event?.eventId,
                calendarInstanceStart = (activity as? LiveActivity.CalendarActivity)
                    ?.event?.instanceStart?.toEpochMilli(),
                startsAt = activity.startsAt.toEpochMilli(),
                endsAt = activity.endsAt?.toEpochMilli(),
                dismissed = existing?.dismissed ?: false,
                lastSequence = (activity as? LiveActivity.MatchActivity)?.sequence
                    ?: existing?.lastSequence ?: 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        const val KIND_MATCH = "match"
        const val KIND_CALENDAR = "calendar"

        /** Two seconds between silent refreshes of the same card. */
        private const val MIN_UPDATE_INTERVAL_MS = 2_000L
    }
}
