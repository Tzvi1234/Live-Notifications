package com.tzvi.kickoff.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tzvi.kickoff.MainActivity
import com.tzvi.kickoff.R
import com.tzvi.kickoff.core.model.CalendarEvent
import com.tzvi.kickoff.core.model.LiveActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The calendar twin of [MatchNotificationBuilder].
 *
 * A calendar event has a genuine start and end, which is exactly the shape the Live
 * Update design guidance asks for: the progress bar is the event's own elapsed time,
 * counting down to the start and then through the meeting.
 */
@Singleton
class CalendarNotificationBuilder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capability: LiveUpdateCapability,
) {
    fun build(activity: LiveActivity.CalendarActivity): Notification {
        val event = activity.event
        val builder = NotificationCompat.Builder(context, NotificationChannels.CALENDAR)
            .setSmallIcon(R.drawable.ic_stat_kickoff)
            .setContentTitle(event.title)
            .setContentText(subtitle(activity))
            .setSubText(event.calendarName)
            .setContentIntent(openIntent(event))
            .setDeleteIntent(
                NotificationActionReceiver.pendingIntent(
                    context, NotificationActionReceiver.ACTION_DISMISSED, activity.key, null,
                ),
            )
            .setOngoing(activity.stage != LiveActivity.CalendarActivity.Stage.ENDED)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setColor(ContextCompat.getColor(context, R.color.brand_green))
            .setShowWhen(false)

        if (capability.supportsProgressStyle) {
            builder.setStyle(progressStyle(activity))
                .setShortCriticalText(chipText(activity))
                .setRequestPromotedOngoing(true)
            if (activity.stage == LiveActivity.CalendarActivity.Stage.UPCOMING) {
                builder.setWhen(event.instanceStart.toEpochMilli())
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
            }
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(longBody(event)))
                .setProgress(TOTAL, progressOf(activity), false)
        }
        return builder.build()
    }

    private fun progressStyle(
        activity: LiveActivity.CalendarActivity,
    ): NotificationCompat.ProgressStyle =
        NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(progressOf(activity))
            .setProgressSegments(
                listOf(
                    NotificationCompat.ProgressStyle.Segment(TOTAL)
                        .setId(1)
                        .setColor(ContextCompat.getColor(context, R.color.brand_green)),
                ),
            )

    /** 0 while waiting, then the fraction of the event that has elapsed. */
    private fun progressOf(activity: LiveActivity.CalendarActivity): Int {
        val event = activity.event
        val now = Instant.now()
        return when {
            now.isBefore(event.instanceStart) -> 0
            now.isAfter(event.instanceEnd) -> TOTAL
            else -> {
                val total = Duration.between(event.instanceStart, event.instanceEnd).toMillis()
                if (total <= 0) TOTAL
                else (Duration.between(event.instanceStart, now).toMillis() * TOTAL / total)
                    .toInt().coerceIn(0, TOTAL)
            }
        }
    }

    private fun subtitle(activity: LiveActivity.CalendarActivity): String {
        val event = activity.event
        val zone = ZoneId.systemDefault()
        return when (activity.stage) {
            LiveActivity.CalendarActivity.Stage.UPCOMING -> {
                val minutes = Duration.between(Instant.now(), event.instanceStart).toMinutes()
                val where = event.location?.let { " · $it" }.orEmpty()
                if (minutes in 0..90) "In $minutes min$where"
                else TIME_FORMAT.format(event.instanceStart.atZone(zone)) + where
            }
            LiveActivity.CalendarActivity.Stage.IN_PROGRESS -> {
                val left = Duration.between(Instant.now(), event.instanceEnd).toMinutes()
                "Ends in $left min" + event.location?.let { " · $it" }.orEmpty()
            }
            LiveActivity.CalendarActivity.Stage.ENDED -> "Finished"
        }
    }

    private fun chipText(activity: LiveActivity.CalendarActivity): String {
        val target = when (activity.stage) {
            LiveActivity.CalendarActivity.Stage.UPCOMING -> activity.event.instanceStart
            else -> activity.event.instanceEnd
        }
        val minutes = Duration.between(Instant.now(), target).toMinutes()
        return if (minutes in 0..99) "${minutes}m" else "Now"
    }

    private fun longBody(event: CalendarEvent): String = buildString {
        val zone = ZoneId.systemDefault()
        if (event.isAllDay) {
            append("All day")
        } else {
            append(TIME_FORMAT.format(event.instanceStart.atZone(zone)))
            append(" – ")
            append(TIME_FORMAT.format(event.instanceEnd.atZone(zone)))
        }
        event.location?.let { append("\n").append(it) }
        event.description?.takeIf { it.isNotBlank() }?.let { append("\n").append(it.take(300)) }
    }

    private fun openIntent(event: CalendarEvent): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            event.eventId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val TOTAL = 100
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
