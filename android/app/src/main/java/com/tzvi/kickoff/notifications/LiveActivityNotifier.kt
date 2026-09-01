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
     * What a post produced, so the foreground service can adopt the card as its own
     * service notification rather than showing a second, redundant one beside it.
     */
    data class Posted(
        val notificationId: Int,
        val notification: android.app.Notification,
        val rendering: MatchNotificationBuilder.Rendering,
    )

    /**
     * Post or update the ongoing match card.
     *
     * @param alertingEvent when set, an additional interrupting notification is posted
     *   alongside the card. It is deliberately a *separate* notification: a
     *   notification's channel is fixed at post time, so making the card itself loud
     *   would shuttle it between channels on every goal.
     * @return what was posted, or null when nothing was.
     */
    suspend fun postMatch(
        activity: LiveActivity.MatchActivity,
        style: LiveCardStyle,
        alertingEvent: MatchEvent? = null,
        /**
         * Set when a person asked for this card by name - the Preview button.
         *
         * Swiping a card away means "not this match, not today", which is why a dismissed
         * key is never posted again on its own. It cannot also mean "never show me a
         * preview again": the user is standing in Settings pressing a button, and the
         * only honest answer to that press is the card.
         */
        userRequested: Boolean = false,
    ): Posted? = postLock.withLock {
        if (!canPost()) return null
        if (!userRequested && isDismissed(activity.key)) return null

        val id = activity.notificationId
        // A genuine event always gets through; only silent refreshes are throttled.
        if (alertingEvent == null && !userRequested && isRateLimited(id)) return null

        // Crests are only decoded when the renderer can actually use them: the promoted
        // path shows them as the progress bar's start and end icons, the rich path in the
        // scoreboard, and the plain path not at all.
        val crests = loadCrests(activity, style)
        val result = matchBuilder.build(activity, crests, style)

        notify(id, result)
        lastRendering[activity.key] = result.rendering

        if (alertingEvent != null) {
            postEventAlert(activity, alertingEvent, crests)
        }
        track(activity)
        Posted(id, result.notification, result.rendering)
    }

    @SuppressLint("MissingPermission")
    private fun postEventAlert(
        activity: LiveActivity.MatchActivity,
        event: MatchEvent,
        crests: MatchNotificationBuilder.Crests?,
    ) {
        manager.notify(
            matchBuilder.alertNotificationId(event),
            matchBuilder.buildEventAlert(activity, event, crests),
        )
    }

    // canPost() checks POST_NOTIFICATIONS before anything is built; lint cannot see
    // through the helper.
    @SuppressLint("MissingPermission")
    suspend fun postCalendar(activity: LiveActivity.CalendarActivity): Boolean =
        postLock.withLock {
            if (!canPost()) return false
            if (isDismissed(activity.key)) return false
            val id = activity.notificationId
            if (isRateLimited(id)) return false

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

    /**
     * True when the system actually promoted the last posted card for [key].
     *
     * Both non-plain renderings ask for promotion now, so what decides this is the
     * device, not which of the two was chosen.
     */
    fun isPromoted(key: String): Boolean =
        when (lastRendering[key]) {
            MatchNotificationBuilder.Rendering.CLOCK,
            MatchNotificationBuilder.Rendering.COMMENTARY,
            -> capability.canPostPromoted()

            else -> false
        }

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

    /** Un-dismisses a card, so a deliberate repost is not silently swallowed. */
    suspend fun clearDismissal(key: String) {
        val existing = trackedActivityDao.get(key) ?: return
        if (existing.dismissed) trackedActivityDao.upsert(existing.copy(dismissed = false))
    }

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
