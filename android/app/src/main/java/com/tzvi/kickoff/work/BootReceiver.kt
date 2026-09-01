package com.tzvi.kickoff.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Alarms do not survive a reboot and neither does a running service, so a boot, a
 * package replacement or a clock change all re-derive the schedule from scratch.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: KickoffWorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                scheduler.ensureScheduled()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "kickoff_boot_resync",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<FixtureSyncWorker>().build(),
                )
            }
        }
    }
}
