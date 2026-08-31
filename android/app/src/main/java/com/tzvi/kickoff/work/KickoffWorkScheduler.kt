package com.tzvi.kickoff.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
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
 * exists for the ninety minutes a match is actually in play, and the calendar
 * ContentObserver only exists while the process does.
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

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(FIXTURE_SYNC)
        WorkManager.getInstance(context).cancelUniqueWork(ACTIVITY_SWEEP)
    }

    companion object {
        const val FIXTURE_SYNC = "kickoff_fixture_sync"
        const val ACTIVITY_SWEEP = "kickoff_activity_sweep"
    }
}
