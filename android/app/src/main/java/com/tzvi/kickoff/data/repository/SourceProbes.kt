package com.tzvi.kickoff.data.repository

import com.tzvi.kickoff.data.remote.api.ApiFootballService
import com.tzvi.kickoff.di.ProbeHttp
import com.tzvi.kickoff.feature.onboarding.normaliseBackendUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                        "$base answered, but there is no matchUP backend on it (404). Check " +
                            "the address against the one Render shows for your service, and " +
                            "that the deploy actually succeeded.",
                    )

                    response.code in 500..599 -> SourceProbe.Failed(
                        "$base is up but returned ${response.code}. Check the service logs " +
                            "on Render - a missing API_FOOTBALL_KEY stops it booting.",
                    )

                    !response.isSuccessful ->
                        SourceProbe.Failed("$base answered HTTP ${response.code}.")

                    // A 200 from something that is not matchUP is still the wrong address:
                    // a parked domain, a proxy, somebody else's service. The health route's
                    // contract is `ok` plus the provider it is fronting, and both have to be
                    // there - `ok` alone is the commonest shape on the whole internet.
                    !looksLikeBackendHealth(body) -> SourceProbe.Failed(
                        "Something answered at $base, but it is not a matchUP backend.",
                    )

                    // Reached, and answering - but `ok` is liveness, not usefulness. A
                    // deployment whose provider key has been revoked answers ok:true to
                    // every health check while every football screen in the app fails, and
                    // onboarding used to wave it through with "Reached the backend." The
                    // server now says which of the two it is; say it here too, rather than
                    // letting the user find out three screens later.
                    else -> when (val fault = backendDataFault(body)) {
                        null -> SourceProbe.Ok("Reached the backend.")
                        else -> SourceProbe.Failed(
                            "Reached the backend at $base, but it cannot fetch football " +
                                "data right now: $fault",
                        )
                    }
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

/**
 * Does this body look like our own `/v1/health`?
 *
 * Checked structurally rather than by substring. The first version looked for a `"status"`
 * key that the route has never sent, so every correctly deployed backend was rejected as
 * "not a matchUP backend" - a check that can only pass by accident is worse than no check.
 * The contract is `ok: true` plus the name of the provider being fronted; `ok` on its own
 * is the commonest JSON shape on the internet and proves nothing.
 *
 * Top-level and internal so it can be tested without a device: this is exactly the kind of
 * check that is only ever exercised against a real deployment, and it was wrong for weeks.
 */
internal fun looksLikeBackendHealth(body: String): Boolean {
    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return false
    val ok = json["ok"]?.jsonPrimitive?.booleanOrNull ?: return false
    return ok && json["provider"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
}

/**
 * The reason a reachable backend still cannot serve football, or null when it can.
 *
 * Reads `dataOk` and `providerFault.reason`, which the server added precisely so a client
 * could tell a live deployment from a data-dead one. An older backend sends neither; that
 * is not a fault, it is a backend from before the distinction existed, and it is treated
 * as fine rather than as broken.
 */
internal fun backendDataFault(body: String): String? {
    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val dataOk = json["dataOk"]?.jsonPrimitive?.booleanOrNull ?: return null
    if (dataOk) return null
    val reason = json["providerFault"]
        ?.jsonObject
        ?.get("reason")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    return reason ?: "the server did not say why."
}
