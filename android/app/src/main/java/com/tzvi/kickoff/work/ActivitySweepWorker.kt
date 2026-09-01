package com.tzvi.kickoff.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.data.local.dao.TrackedActivityDao
import com.tzvi.kickoff.data.repository.CalendarRepository
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.NoFootballSourceException
import com.tzvi.kickoff.data.repository.SettingsRepository
import com.tzvi.kickoff.notifications.LiveActivityNotifier
import com.tzvi.kickoff.notifications.LiveMatchService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/**
 * The slow heartbeat: retires finished cards and keeps the calendar card current.
 *
 * Matches are driven by the foreground service while they are in play; this worker only
 * has to catch what a killed process left behind.
 */
@HiltWorker
class ActivitySweepWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val calendarRepository: CalendarRepository,
    private val footballRepository: FootballRepository,
    private val settings: SettingsRepository,
    private val notifier: LiveActivityNotifier,
    private val trackedActivityDao: TrackedActivityDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = settings.settings.first()

        // Retire anything that has ended, so a stale card cannot outlive its event.
        val now = Instant.now()
        trackedActivityDao.active()
            .filter { it.endsAt != null && it.endsAt < now.toEpochMilli() - GRACE_MS }
            .forEach {
                notifier.cancel(it.key)
                trackedActivityDao.delete(it.key)
            }
        trackedActivityDao.deleteEndedBefore(now.minus(Duration.ofDays(2)).toEpochMilli())

        adoptOrphanedLiveMatches()

        if (config.calendarSyncEnabled && calendarRepository.hasPermission()) {
            syncCalendarCard(config.calendarLeadMinutes)
        }
        return Result.success()
    }

    /**
     * Picks up matches that are already in play but that nothing is tracking.
     *
     * The normal path is an exact alarm fired an hour before kick-off, but that alarm can
     * never have existed - a fresh install mid-match, a team followed after kick-off, a
     * reboot that dropped the alarm. Without this, the live card would only appear for the
     * *next* match, which reads as the app being broken.
     */
    private suspend fun adoptOrphanedLiveMatches() {
        val live = try {
            footballRepository.refreshLive()
        } catch (_: NoFootballSourceException) {
            return
        } catch (_: Exception) {
            return
        }
        val tracked = trackedActivityDao.active().mapNotNull { it.matchId }.toSet()
        live.asSequence()
            .filter { it.id !in tracked }
            .take(MAX_ADOPTED)
            .forEach { LiveMatchService.track(applicationContext, it.id) }
    }

    private suspend fun syncCalendarCard(leadMinutes: Int) {
        val event = calendarRepository.nextEvent() ?: return
        val now = Instant.now()
        val leadWindow = event.instanceStart.minus(Duration.ofMinutes(leadMinutes.toLong()))
        if (now.isBefore(leadWindow)) return

        val stage = when {
            now.isBefore(event.instanceStart) -> LiveActivity.CalendarActivity.Stage.UPCOMING
            now.isBefore(event.instanceEnd) -> LiveActivity.CalendarActivity.Stage.IN_PROGRESS
            else -> LiveActivity.CalendarActivity.Stage.ENDED
        }
        if (stage == LiveActivity.CalendarActivity.Stage.ENDED) return
        notifier.postCalendar(LiveActivity.CalendarActivity(event, stage))
    }

    private companion object {
        const val GRACE_MS = 5 * 60_000L

        /** A cap on how many services one sweep can spin up; ten live matches at once
         *  is already an unusual matchday for one user's follow list. */
        const val MAX_ADOPTED = 5
    }
}
