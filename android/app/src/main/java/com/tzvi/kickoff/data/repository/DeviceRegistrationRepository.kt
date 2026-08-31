package com.tzvi.kickoff.data.repository

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.tzvi.kickoff.BuildConfig
import com.tzvi.kickoff.data.backend.KickoffBackendService
import com.tzvi.kickoff.data.backend.RegisterDeviceRequest
import com.tzvi.kickoff.data.backend.SubscriptionPreferencesJson
import com.tzvi.kickoff.data.backend.SubscriptionRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Registers this device with the backend and keeps its subscriptions in sync.
 *
 * Every step degrades quietly: with no Firebase config or no backend URL the app is
 * simply a polling client, which is a supported way to run it rather than an error.
 */
@Singleton
class DeviceRegistrationRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backend: KickoffBackendService,
    private val settings: SettingsRepository,
    private val football: FootballRepository,
) {
    suspend fun syncIfPossible() {
        if (!BuildConfig.HAS_FIREBASE) return
        if (settings.backendUrl.first().isBlank()) return
        val token = currentToken() ?: return
        register(token)
        pushSubscriptions(token)
    }

    suspend fun onTokenRefreshed(token: String) {
        settings.setFcmToken(token)
        if (settings.backendUrl.first().isBlank()) return
        runCatching { register(token) }
        runCatching { pushSubscriptions(token) }
    }

    /** Call after the favourites change so the server's fan-out list stays correct. */
    suspend fun syncSubscriptions() {
        if (settings.backendUrl.first().isBlank()) return
        val token = settings.fcmToken.first().ifBlank { currentToken().orEmpty() }
        if (token.isBlank()) return
        runCatching { pushSubscriptions(token) }
    }

    suspend fun unregister() {
        val token = settings.fcmToken.first()
        if (token.isBlank() || settings.backendUrl.first().isBlank()) return
        runCatching { backend.unregisterDevice(token) }
    }

    private suspend fun register(token: String) {
        backend.registerDevice(
            RegisterDeviceRequest(
                token = token,
                platform = "android",
                appVersion = BuildConfig.VERSION_NAME,
                timeZone = TimeZone.getDefault().id,
                locale = Locale.getDefault().toLanguageTag(),
            ),
        )
        settings.setFcmToken(token)
    }

    private suspend fun pushSubscriptions(token: String) {
        val config = settings.settings.first()
        val teamIds = football.favouriteIdsNow()
        val leagues = football.followedLeagues.first().map { it.id }
        backend.updateSubscriptions(
            SubscriptionRequest(
                token = token,
                teamIds = teamIds,
                leagueIds = leagues,
                matchIds = emptyList(),
                preferences = SubscriptionPreferencesJson(
                    goals = config.notifyGoals,
                    cards = config.notifyCards,
                    substitutions = config.notifySubstitutions,
                    kickoffAndFullTime = config.notifyKickoffAndFullTime,
                    lineups = config.notifyLineups,
                    preMatchLeadMinutes = config.preMatchLeadMinutes,
                ),
            ),
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun currentToken(): String? {
        if (!BuildConfig.HAS_FIREBASE) return null
        return runCatching {
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging.getInstance().token
                    .addOnCompleteListener { task ->
                        continuation.resume(task.result.takeIf { task.isSuccessful })
                    }
            }
        }.getOrNull()
    }
}
