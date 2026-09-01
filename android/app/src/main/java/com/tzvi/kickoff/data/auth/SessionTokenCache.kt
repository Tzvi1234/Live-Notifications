package com.tzvi.kickoff.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current session JWT so that a request does not cost a round trip through
 * Clerk before it can even start.
 *
 * Clerk's session tokens are short-lived by design - a minute, in a default instance -
 * so the cache is keyed on the token's own `exp` claim rather than on a guessed lifetime,
 * and it refreshes while there is still time on the clock rather than after the server
 * has already refused a request. A token that will not parse gets a deliberately short
 * fallback lifetime: guessing long is what produces a wave of 401s.
 */
@Singleton
class SessionTokenCache @Inject constructor(
    private val auth: AuthRepository,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var token: String? = null
    private var expiresAtMillis = 0L

    /** The token to send, or null when there is no session - which is not an error. */
    suspend fun current(): String? = mutex.withLock {
        if (auth.awaitSettled() !is AuthState.SignedIn) {
            forgetLocked()
            return@withLock null
        }

        val now = System.currentTimeMillis()
        val held = token
        if (held != null && now < expiresAtMillis - REFRESH_LEEWAY_MS) return@withLock held

        val fresh = auth.sessionToken()
        if (fresh == null) {
            forgetLocked()
            return@withLock null
        }
        token = fresh
        expiresAtMillis = expiryOf(fresh) ?: (now + UNPARSEABLE_LIFETIME_MS)
        fresh
    }

    /** Drops the held token, so the next request fetches a new one. */
    suspend fun forget() = mutex.withLock { forgetLocked() }

    private fun forgetLocked() {
        token = null
        expiresAtMillis = 0L
    }

    /** The `exp` claim, in milliseconds, or null if this is not a JWT we can read. */
    private fun expiryOf(jwt: String): Long? {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            json.parseToJsonElement(decoded).jsonObject["exp"]?.jsonPrimitive?.longOrNull
        }.getOrNull()?.times(1_000)
    }

    private companion object {
        /** Refresh with this much life left, so a request never races its own expiry. */
        const val REFRESH_LEEWAY_MS = 15_000L
        const val UNPARSEABLE_LIFETIME_MS = 30_000L
    }
}
