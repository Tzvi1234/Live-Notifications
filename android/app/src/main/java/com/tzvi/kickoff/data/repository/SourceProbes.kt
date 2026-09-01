package com.tzvi.kickoff.data.repository

import com.tzvi.kickoff.data.remote.api.ApiFootballService
import com.tzvi.kickoff.di.ProbeHttp
import com.tzvi.kickoff.feature.onboarding.normaliseBackendUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The answer to "is this thing you just typed actually going to work". */
sealed interface SourceProbe {
    data class Ok(val message: String) : SourceProbe
    data class Failed(val message: String) : SourceProbe
}

/**
 * Checks a key or a backend address before it is saved.
 *
 * Everything here builds its own request against the value under test rather than going
 * through the configured client, because the whole point is to try something that is not
 * configured yet. That is also why it is raw OkHttp: a Retrofit instance is bound to one
 * base URL, and this needs an arbitrary one per call.
 *
 * The reason this exists at all: a hostname that resolves but serves something else - a
 * Render service that never finished deploying, one that was suspended, a typo landing on
 * a parked domain - answers 404 to every path. Accepted blindly, the first sign of that
 * was "could not reach the source" two onboarding steps later, blaming the wrong thing.
 */
@Singleton
class SourceProbes @Inject constructor(
    @ProbeHttp private val client: OkHttpClient,
) {
    suspend fun backend(rawUrl: String): SourceProbe = withContext(Dispatchers.IO) {
        val base = normaliseBackendUrl(rawUrl)
            ?: return@withContext SourceProbe.Failed(
                "That does not look like an address. Try https://your-app.onrender.com",
            )
        val url = "$base/v1/health"
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 404 -> SourceProbe.Failed(
                        "$base answered, but there is no Kickoff backend on it (404). Check " +
                            "the address against the one Render shows for your service, and " +
                            "that the deploy actually succeeded.",
                    )

                    response.code in 500..599 -> SourceProbe.Failed(
                        "$base is up but returned ${response.code}. Check the service logs " +
                            "on Render - a missing API_FOOTBALL_KEY stops it booting.",
                    )

                    !response.isSuccessful ->
                        SourceProbe.Failed("$base answered HTTP ${response.code}.")

                    // A 200 from something that is not Kickoff is still the wrong address.
                    !body.contains("\"status\"") -> SourceProbe.Failed(
                        "Something answered at $base, but it is not a Kickoff backend.",
                    )

                    else -> SourceProbe.Ok("Reached the backend.")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            SourceProbe.Failed(
                "Could not reach $base at all - ${error.message ?: "no route"}. Check the " +
                    "address and your connection.",
            )
        }
    }

    /**
     * One request against /leagues, which is the cheapest call that proves a key works.
     *
     * API-Football answers a bad key with HTTP 200 and an `errors` object, so the body has
     * to be inspected structurally; the status code proves nothing on its own.
     */
    suspend fun apiKey(key: String): SourceProbe = withContext(Dispatchers.IO) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return@withContext SourceProbe.Failed("The key is empty.")
        val request = Request.Builder()
            .url("${ApiFootballService.BASE_URL}status")
            .header(ApiFootballService.API_KEY_HEADER, trimmed)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use SourceProbe.Failed("The provider answered HTTP ${response.code}.")
                }
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@use SourceProbe.Failed("The provider's answer could not be read.")

                describeErrors(json)?.let { return@use SourceProbe.Failed(it) }

                // /status is free and uncounted, and it reports the plan and what is left.
                val response0 = json.optJSONObject("response")
                val plan = response0?.optJSONObject("subscription")?.optString("plan")
                val requests = response0?.optJSONObject("requests")
                val used = requests?.optInt("current", -1) ?: -1
                val limit = requests?.optInt("limit_day", -1) ?: -1
                val quota = if (used >= 0 && limit >= 0) " $used of $limit requests used today." else ""
                SourceProbe.Ok("Key accepted${plan?.takeIf { it.isNotBlank() }?.let { " on the $it plan" }.orEmpty()}.$quota")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            SourceProbe.Failed("Could not reach API-Football - ${error.message ?: "no route"}.")
        }
    }

    /** `errors` is `[]` when empty and an object when not, so it is read structurally. */
    private fun describeErrors(json: JSONObject): String? {
        val errors = json.opt("errors") ?: return null
        if (errors !is JSONObject || errors.length() == 0) return null
        return errors.keys().asSequence()
            .joinToString("; ") { key -> "$key: ${errors.optString(key)}" }
            .takeIf { it.isNotBlank() }
    }
}
