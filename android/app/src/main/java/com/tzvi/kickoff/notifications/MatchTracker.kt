package com.tzvi.kickoff.notifications

import android.content.Context
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.data.local.dao.TrackedActivityDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Start and stop the live card for a single match, from anywhere in the UI.
 *
 * Normally the card appears on its own, an hour before kick-off. This is the manual
 * override: "follow this one", and the way back out of a card the user dismissed —
 * dismissal is sticky by design, so re-following has to clear it explicitly.
 */
@Singleton
class MatchTracker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val trackedActivityDao: TrackedActivityDao,
    private val notifier: LiveActivityNotifier,
) {
    fun isTracking(matchId: Long): Flow<Boolean> =
        trackedActivityDao.observeAll().map { rows ->
            rows.any { it.matchId == matchId && !it.dismissed }
        }

    suspend fun follow(matchId: Long) {
        // A dismissed card is never reposted, so following again has to lift that first.
        trackedActivityDao.delete(LiveActivity.MatchActivity.matchKey(matchId))
        LiveMatchService.track(context, matchId)
    }

    suspend fun unfollow(matchId: Long) {
        val key = LiveActivity.MatchActivity.matchKey(matchId)
        trackedActivityDao.markDismissed(key)
        notifier.cancel(key)
        LiveMatchService.untrack(context, matchId)
    }
}
