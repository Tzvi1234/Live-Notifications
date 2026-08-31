package com.tzvi.kickoff.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.NoFootballSourceException
import com.tzvi.kickoff.data.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/**
 * Keeps the fixture cache warm and re-arms the pre-match alarms.
 *
 * Alarms are set only for the next day's matches: a phone reschedules far more often
 * than a season's worth of fixtures is worth pinning, and this worker runs again long
 * before the horizon closes.
 */
@HiltWorker
class FixtureSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: FootballRepository,
    private val settings: SettingsRepository,
    private val alarmScheduler: MatchAlarmScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val matches = repository.refreshFixtures()
            repository.pruneOldData()

            val lead = settings.settings.first().preMatchLeadMinutes
            val horizon = Instant.now().plus(Duration.ofHours(ALARM_HORIZON_HOURS))
            matches
                .filter { it.kickoffAt.isBefore(horizon) && !it.phase.isFinished }
                .forEach { alarmScheduler.schedule(it, lead) }

            Result.success()
        } catch (_: NoFootballSourceException) {
            // Nothing configured yet - this is a normal state before onboarding, not a
            // failure worth retrying with backoff.
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private companion object {
        const val ALARM_HORIZON_HOURS = 30L
    }
}
