package com.tzvi.kickoff.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tzvi.kickoff.core.model.Match
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wakes the app an hour before kick-off.
 *
 * Kick-off is a fixed, user-visible moment and the whole product promise is that the
 * card is already there when the line-ups land, so this is one of the cases exact
 * alarms exist for. It still degrades to an inexact alarm if the permission is absent.
 */
@Singleton
class MatchAlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    fun schedule(match: Match, leadMinutes: Int) {
        val manager = alarmManager ?: return
        val fireAt = match.kickoffAt.minus(Duration.ofMinutes(leadMinutes.toLong()))
        val now = Instant.now()

        // Already inside the window: start immediately rather than never.
        val triggerMillis = if (fireAt.isBefore(now)) {
            if (match.kickoffAt.plus(Duration.ofHours(3)).isBefore(now)) return
            now.plusSeconds(5).toEpochMilli()
        } else {
            fireAt.toEpochMilli()
        }

        val intent = pendingIntent(match.id)
        runCatching {
            if (canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerMillis, intent,
                )
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, intent)
            }
        }.onFailure {
            // SecurityException if the exact-alarm permission was revoked mid-flight.
            manager.set(AlarmManager.RTC_WAKEUP, triggerMillis, intent)
        }
    }

    fun cancel(matchId: Long) {
        alarmManager?.cancel(pendingIntent(matchId))
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager?.canScheduleExactAlarms() == true

    private fun pendingIntent(matchId: Long): PendingIntent {
        val intent = Intent(context, MatchAlarmReceiver::class.java)
            .setAction(MatchAlarmReceiver.ACTION_PRE_MATCH)
            .putExtra(MatchAlarmReceiver.EXTRA_MATCH_ID, matchId)
        return PendingIntent.getBroadcast(
            context,
            matchId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
