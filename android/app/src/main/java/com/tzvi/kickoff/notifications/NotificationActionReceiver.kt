package com.tzvi.kickoff.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.tzvi.kickoff.data.local.dao.FavouriteTeamDao
import com.tzvi.kickoff.data.local.dao.TrackedActivityDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the two ways a user can get rid of a live card.
 *
 * Both are recorded, not just acted on: reposting a Live Update the user has dismissed
 * is what pushes people to revoke the promotion permission, and that revocation is
 * app-wide and sticky.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var trackedActivityDao: TrackedActivityDao
    @Inject lateinit var favouriteTeamDao: FavouriteTeamDao

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_DISMISSED -> trackedActivityDao.markDismissed(key)
                    ACTION_STOP_FOLLOWING -> {
                        trackedActivityDao.markDismissed(key)
                        NotificationManagerCompat.from(context)
                            .cancel(key.hashCode() and 0x7FFFFFFF)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DISMISSED = "com.tzvi.kickoff.action.DISMISSED"
        const val ACTION_STOP_FOLLOWING = "com.tzvi.kickoff.action.STOP_FOLLOWING"
        private const val EXTRA_KEY = "activity_key"
        private const val EXTRA_MATCH_ID = "match_id"

        fun pendingIntent(
            context: Context,
            action: String,
            key: String,
            matchId: Long?,
        ): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_KEY, key)
            matchId?.let { intent.putExtra(EXTRA_MATCH_ID, it) }
            return PendingIntent.getBroadcast(
                context,
                (action + key).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
