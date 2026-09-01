package com.tzvi.kickoff.data.auth

import com.tzvi.kickoff.data.repository.SettingsRepository
import com.tzvi.kickoff.di.IoDispatcher
import com.tzvi.kickoff.di.ProbeHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What `/v1/config` tells a client about the instance it is talking to. Other fields of
 * the payload belong to other features and are ignored here.
 */
@Serializable
private data class ClerkConfigJson(val clerkPublishableKey: String? = null)

/**
 * Asks the backend which Clerk instance to talk to.
 *
 * A raw call on the plain probe client, not the `@BackendApi` Retrofit service: that
 * service's client carries the interceptor that attaches a Clerk token, and a token
 * cannot exist before this answer arrives. Going through it would be a cycle both in
 * Dagger and at runtime.
 */
@Singleton
class ClerkConfigClient @Inject constructor(
    @param:ProbeHttp private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val json: Json,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** The instance's publishable key, or null if the backend has none or cannot be reached. */
    suspend fun publishableKey(): String? {
        val base = settings.backendUrl.first().trim().takeIf { it.isNotBlank() } ?: return null
        val url = normalise(base).toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments(CONFIG_PATH)
            ?.build()
            ?: return null

        return withContext(io) {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        json.decodeFromString<ClerkConfigJson>(response.body.string())
                            .clerkPublishableKey
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun normalise(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> "https://$url"
    }.trimEnd('/')

    private companion object {
        const val CONFIG_PATH = "v1/config"

        /**
         * The auth screen shows a loader for exactly this long before it gives up and
         * says accounts are unavailable. The probe client's own read timeout is longer
         * because it was written for a user who pressed a button and is watching.
         */
        const val REQUEST_TIMEOUT_MS = 8_000L
    }
}
