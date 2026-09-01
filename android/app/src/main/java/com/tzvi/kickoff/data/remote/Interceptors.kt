package com.tzvi.kickoff.data.remote

import com.tzvi.kickoff.data.remote.api.ApiFootballService
import com.tzvi.kickoff.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the user's API-Football key.
 *
 * The key lives in DataStore rather than in the build, so it is read per request.
 * That read is blocking, which is fine: interceptors already run on OkHttp's own
 * background dispatcher, never on the main thread.
 */
@Singleton
class ApiFootballKeyInterceptor @Inject constructor(
    private val settings: SettingsRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val key = runBlocking { settings.apiFootballKey.first() }
        if (key.isBlank()) {
            throw IOException("No API-Football key configured. Add one in Settings.")
        }
        val request = chain.request().newBuilder()
            .addHeader(ApiFootballService.API_KEY_HEADER, key)
            .build()
        return chain.proceed(request)
    }
}

/**
 * Points backend calls at whatever URL the user configured.
 *
 * Retrofit needs a base URL at construction time but the deployment address is only
 * known at runtime, so the client is built against a placeholder host and every
 * request is rewritten here.
 */
@Singleton
class BackendUrlInterceptor @Inject constructor(
    private val settings: SettingsRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val configured = runBlocking { settings.backendUrl.first() }.trim()
        if (configured.isBlank()) {
            throw IOException("No Kickoff backend configured. Add its URL in Settings.")
        }
        val base = normalise(configured).toHttpUrlOrNull()
            ?: throw IOException("Backend URL is not a valid http(s) URL: $configured")

        val original = chain.request()
        val rewritten = original.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
        return chain.proceed(original.newBuilder().url(rewritten).build())
    }

    private fun normalise(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> "https://$url"
    }.trimEnd('/')

    companion object {
        /** Never contacted: every request is rewritten before it leaves. */
        const val PLACEHOLDER_BASE_URL = "https://kickoff.invalid/"
    }
}
