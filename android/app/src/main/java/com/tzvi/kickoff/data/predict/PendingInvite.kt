package com.tzvi.kickoff.data.predict

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An invite code that arrived from outside the app and has not been used yet.
 *
 * A link is opened long before the screen that can act on it exists - the app may be cold,
 * the user may not be signed in, and joining is a network call that needs an account. So
 * the code is parked here rather than passed as a navigation argument: navigation state is
 * rebuilt on rotation and cleared by a sign-in that pops the whole graph, and either one
 * would silently drop the invitation the user just tapped.
 *
 * It is deliberately in memory only. An invite is a thing that just happened; one still
 * sitting here a week after a reinstall would be a surprise, not a convenience.
 */
@Singleton
class PendingInvite @Inject constructor() {

    private val mutableCode = MutableStateFlow<String?>(null)

    /** The code waiting to be redeemed, or null. */
    val code: StateFlow<String?> = mutableCode.asStateFlow()

    fun offer(raw: String) {
        val cleaned = raw.filterNot { it.isWhitespace() }.uppercase()
        if (cleaned.isNotEmpty()) mutableCode.value = cleaned
    }

    /** Takes the code, leaving nothing behind: redeeming twice would be a second join. */
    fun consume(): String? = mutableCode.value?.also { mutableCode.value = null }

    companion object {
        /**
         * `matchup://join/3YJK2CYK`.
         *
         * A custom scheme rather than an https App Link because an App Link needs a
         * signed assetlinks.json served from the backend and pinned to the release
         * signing certificate; until this app is signed with a real key that would break
         * on every rebuild. A friend without the app installed sees the code in the
         * message text and can type it, which is why the share sheet sends both.
         */
        const val SCHEME = "matchup"
        const val HOST = "join"

        fun linkFor(code: String): String = "$SCHEME://$HOST/$code"

        /** The code in a `matchup://join/CODE` link, or null if this is not one. */
        fun codeIn(scheme: String?, host: String?, lastSegment: String?): String? {
            if (!scheme.equals(SCHEME, ignoreCase = true)) return null
            if (!host.equals(HOST, ignoreCase = true)) return null
            return lastSegment?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }
        }
    }
}
