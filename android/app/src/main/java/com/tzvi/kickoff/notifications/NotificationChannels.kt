package com.tzvi.kickoff.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.tzvi.kickoff.R

/**
 * Channel definitions.
 *
 * Two channels, deliberately: the scoreboard ticks for ninety minutes and must be
 * silent, while goals and red cards must be able to interrupt. Channel importance is
 * immutable once created, so these are the settings the app is stuck with - the only
 * way to change them later is a new channel id.
 */
object NotificationChannels {

    /**
     * The ongoing match card. IMPORTANCE_LOW: visible in the shade, never a heads-up,
     * never a sound. It must not be IMPORTANCE_MIN, which would disqualify the
     * notification from being promoted to a Live Update.
     */
    const val LIVE_MATCH = "live_match_v1"

    /** Goals, red cards, full time. Allowed to interrupt. */
    const val MATCH_EVENTS = "match_events_v1"

    /** The ongoing card for an upcoming calendar event. */
    const val CALENDAR = "calendar_live_v1"

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val live = NotificationChannel(
            LIVE_MATCH,
            context.getString(R.string.channel_live_match),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_live_match_desc)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val events = NotificationChannel(
            MATCH_EVENTS,
            context.getString(R.string.channel_match_events),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_match_events_desc)
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val calendar = NotificationChannel(
            CALENDAR,
            context.getString(R.string.channel_calendar),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_calendar_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannels(listOf(live, events, calendar))
    }

    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}
