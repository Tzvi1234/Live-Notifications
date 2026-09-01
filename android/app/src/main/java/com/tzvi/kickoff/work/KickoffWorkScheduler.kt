package com.tzvi.kickoff.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The periodic backbone.
 *
 * WorkManager is what survives process death and reboots; the foreground service only
 * exists for the ninety minutes a match is actually in play.
 */
@Singleton
class KickoffWorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun ensureScheduled() {
        val manager = WorkManager.getInstance(context)

        manager.enqueueUniquePeriodicWork(
            FIXTURE_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FixtureSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )

        manager.enqueueUniquePeriodicWork(
            ACTIVITY_SWEEP,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ActivitySweepWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    /**
     * Runs a fixture sync now.
     *
     * Called when the followed teams change: the periodic worker would otherwise leave
     * the schedule - and the pre-match alarms derived from it - up to six hours stale,
     * which for a team followed on the morning of a match means missing that match.
     */
    fun requestImmediateFixtureSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_FIXTURE_SYNC,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FixtureSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(FIXTURE_SYNC)
        WorkManager.getInstance(context).cancelUniqueWork(ACTIVITY_SWEEP)
    }

    companion object {
        const val FIXTURE_SYNC = "kickoff_fixture_sync"
        const val ACTIVITY_SWEEP = "kickoff_activity_sweep"
        const val IMMEDIATE_FIXTURE_SYNC = "kickoff_fixture_sync_now"
    }
}
