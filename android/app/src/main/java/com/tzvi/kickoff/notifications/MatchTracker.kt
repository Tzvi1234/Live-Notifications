package com.tzvi.kickoff.notifications

import android.content.Context
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.data.local.dao.TrackedActivityDao
import com.tzvi.kickoff.work.MatchAlarmScheduler
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
    private val alarms: MatchAlarmScheduler,
) {
    fun isTracking(matchId: Long): Flow<Boolean> =
        trackedActivityDao.observeAll().map { rows ->
            rows.any { it.matchId == matchId && !it.dismissed }
        }

    suspend fun follow(matchId: Long) {
        // A dismissed card is never reposted, so following again has to lift that first.
        trackedActivityDao.delete(LiveActivity.MatchActivity.matchKey(matchId))
        // manual: the user is on this match's screen and asked for it by name, so the
        // favourites rule that governs automatic tracking does not apply.
        LiveMatchService.track(context, matchId, manual = true)
    }

    suspend fun unfollow(matchId: Long) {
        val key = LiveActivity.MatchActivity.matchKey(matchId)
        trackedActivityDao.markDismissed(key)
        notifier.cancel(key)
        LiveMatchService.untrack(context, matchId)
        // And the pre-match alarm, or the card the user just dismissed comes straight back
        // at kick-off: the alarm hands the match to the service, the service sees a
        // favourite club and starts following it again. Stopping something has to stop the
        // thing that restarts it.
        alarms.cancel(matchId)
    }
}
