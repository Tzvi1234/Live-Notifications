package com.tzvi.kickoff

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tzvi.kickoff.data.auth.AuthRepository
import com.tzvi.kickoff.notifications.NotificationChannels
import com.tzvi.kickoff.work.KickoffWorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KickoffApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: KickoffWorkScheduler
    @Inject lateinit var auth: AuthRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        workScheduler.ensureScheduled()
        // Not Clerk.initialize itself: the publishable key may still have to be fetched
        // from the backend, so this only starts the resolution and returns.
        auth.start()
    }
}
