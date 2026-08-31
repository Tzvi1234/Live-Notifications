package com.tzvi.kickoff.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tzvi.kickoff.data.repository.DeviceRegistrationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives the backend's live-match pushes.
 *
 * Messages are data-only on purpose: the notification is built on-device so the same
 * renderer, the same crest cache and the same dedupe gate serve both push and polling.
 * That also means the app must post something visible for essentially every
 * high-priority message it receives - FCM audits that over a rolling window and demotes
 * an app instance whose high-priority messages do not produce notifications.
 */
@AndroidEntryPoint
class KickoffMessagingService : FirebaseMessagingService() {

    @Inject lateinit var pushHandler: PushMessageHandler
    @Inject lateinit var registration: DeviceRegistrationRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // onNewToken and getToken() carry a deprecation marker in firebase-messaging 25.x
    // with no announced replacement; they remain the documented way to obtain and
    // refresh a registration token.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        scope.launch { registration.onTokenRefreshed(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) return
        scope.launch { pushHandler.handle(data) }
    }
}
