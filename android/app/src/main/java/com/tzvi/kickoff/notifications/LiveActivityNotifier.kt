package com.tzvi.kickoff.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.core.model.LiveCardStyle
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.data.local.dao.TrackedActivityDao
import com.tzvi.kickoff.data.local.entity.TrackedActivityEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    private val crestLoader: CrestLoader,
    private val capability: LiveUpdateCapability,
    private val trackedActivityDao: TrackedActivityDao,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val postLock = Mutex()

    /**
     * Crest fetches and the reposts they trigger run here rather than on a caller's
     * scope. The poller cancels its coroutine the moment a match ends and a preview is
     * a one-shot call, so a warm-up hung off either of those would be cancelled before
     * it could deliver the crests it was started for.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        postLocked(activity, style, alertingEvent, userRequested, crestRefresh = false)
    }

    /**
     * @param crestRefresh set only by [crestsFor]'s warm-up, for the repost that puts the
     *   crests onto a card that was already sent without them. It is not a new update -
     *   it is the same one finishing - so the rate limit does not apply to it.
     */
    private suspend fun postLocked(
        activity: LiveActivity.MatchActivity,
        style: LiveCardStyle,
        alertingEvent: MatchEvent?,
        userRequested: Boolean,
        crestRefresh: Boolean,
    ): Posted? {
        if (!canPost()) return null
        if (!userRequested && isDismissed(activity.key)) return null

        val id = activity.notificationId
        // A genuine event always gets through; only silent refreshes are throttled.
        if (alertingEvent == null && !userRequested && !crestRefresh && isRateLimited(id)) {
            return null
        }

        // Crests are only decoded when the renderer can actually use them: the promoted
        // path shows them as the progress bar's start and end icons and as the composed
        // pair in the header, the rich path in the scoreboard, and the plain path not at
        // all.
        val crests = crestsFor(activity, style)
        val result = matchBuilder.build(activity, crests, style)

        notify(id, result)
        lastRendering[activity.key] = result.rendering

        if (alertingEvent != null) {
            postEventAlert(activity, alertingEvent, crests)
        }
        track(activity)
        return Posted(id, result.notification, result.rendering)
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

    /**
     * The crests for this post, without ever waiting on the network for longer than a
     * live card can afford to be late.
     *
     * Every update after the first is a cache hit and costs nothing, so the only post
     * that can miss is the first one of a match - and a crest CDN that hangs must not
     * hold that card back. It gets [CREST_DEADLINE_MS] and then the card goes out
     * without the bitmaps: the bar keeps its ends bare and the header its composed pair,
     * both of which the renderers already treat as optional. The fetch itself runs on
     * this class's own scope, so the deadline expiring cancels the *wait* and not the
     * download, and the repost below drops the crests into the card in place the moment
     * they land.
     */
    private suspend fun crestsFor(
        activity: LiveActivity.MatchActivity,
        style: LiveCardStyle,
    ): MatchNotificationBuilder.Crests? {
        if (style == LiveCardStyle.PLAIN) return null
        cachedCrests(activity.match)?.let { return it }

        val fetch = scope.async { loadCrests(activity.match) }
        withTimeoutOrNull(CREST_DEADLINE_MS) { fetch.await() }?.let { return it }

        scope.launch {
            fetch.await()
            postLock.withLock {
                // Only a card we still believe is on screen is worth reposting: an
                // untracked or cancelled one has had its rendering cleared, and putting
                // it back would resurrect a card the user has already seen the end of.
                if (lastRendering[activity.key] == null) return@withLock
                postLocked(activity, style, null, userRequested = false, crestRefresh = true)
            }
        }
        return null
    }

    /** The crests already decoded, or null while either one still has to be fetched. */
    private fun cachedCrests(match: Match): MatchNotificationBuilder.Crests? {
        val home = crestLoader.cached(match.home.crestUrl, match.home.code) ?: return null
        val away = crestLoader.cached(match.away.crestUrl, match.away.code) ?: return null
        return MatchNotificationBuilder.Crests(
            home = home,
            away = away,
            // The league logo decorates nothing that has a fallback problem, so a card is
            // never held back for it.
            league = match.leagueLogoUrl?.let { crestLoader.cached(it, "L") },
        )
    }

    private suspend fun loadCrests(match: Match): MatchNotificationBuilder.Crests =
        MatchNotificationBuilder.Crests(
            home = crestLoader.load(match.home.crestUrl, match.home.code),
            away = crestLoader.load(match.away.crestUrl, match.away.code),
            league = match.leagueLogoUrl?.let { crestLoader.load(it, "L") },
        )

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

    private suspend fun track(activity: LiveActivity.MatchActivity) {
        val existing = trackedActivityDao.get(activity.key)
        trackedActivityDao.upsert(
            TrackedActivityEntity(
                key = activity.key,
                kind = KIND_MATCH,
                matchId = activity.match.id,
                startsAt = activity.startsAt.toEpochMilli(),
                endsAt = activity.endsAt?.toEpochMilli(),
                dismissed = existing?.dismissed ?: false,
                lastSequence = activity.sequence,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        const val KIND_MATCH = "match"

        /** Two seconds between silent refreshes of the same card. */
        private const val MIN_UPDATE_INTERVAL_MS = 2_000L

        /** How long a first post will wait for crests before going out without them. */
        private const val CREST_DEADLINE_MS = 750L
    }
}
