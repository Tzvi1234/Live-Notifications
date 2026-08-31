package com.tzvi.kickoff.feature.onboarding

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Team
import java.net.URI

/** The pages of the flow, in the order they are swiped through. */
enum class OnboardingStep {
    WELCOME,
    CONNECT,
    LEAGUES,
    TEAMS,
    NOTIFICATIONS,
    ;

    companion object {
        fun at(index: Int): OnboardingStep = entries[index.coerceIn(entries.indices)]
    }
}

/** Which source will actually serve requests, given what has been saved. */
enum class ConfiguredSource { NONE, API_FOOTBALL, BACKEND }

/** Why a catalogue fetch produced nothing. Each case gets its own copy on screen. */
enum class CatalogueFailure { NO_SOURCE, UNREACHABLE, EMPTY }

/** A selectable team, carrying the league it came from so the favourite keeps that link. */
data class TeamOption(val team: Team, val league: League?)

data class OnboardingUiState(
    val apiKeyInput: String = "",
    val apiKeySaved: Boolean = false,
    val backendUrlInput: String = "",
    val backendUrlError: String? = null,
    val backendSaved: Boolean = false,

    val leaguesLoading: Boolean = false,
    val leagues: List<League> = emptyList(),
    val leaguesFailure: CatalogueFailure? = null,
    val selectedLeagueIds: Set<Int> = emptySet(),

    val teamsLoading: Boolean = false,
    val teams: List<TeamOption> = emptyList(),
    val teamsFailure: CatalogueFailure? = null,
    /** The league set [teams] was fetched for; re-entering the page must not refetch. */
    val teamsLoadedFor: Set<Int>? = null,
    val teamQuery: String = "",
    /** Insertion-ordered, so the chip row and the saved favourites follow pick order. */
    val selected: Map<Int, TeamOption> = emptyMap(),

    val notificationsGranted: Boolean = false,
    val notificationsDenied: Boolean = false,

    val saving: Boolean = false,
    val saveError: String? = null,
    val completed: Boolean = false,
) {
    /**
     * Backend first, mirroring FootballSourceProvider: when both are set it is the
     * backend that answers, so that is what the user must be told is in use.
     */
    val source: ConfiguredSource
        get() = when {
            backendSaved -> ConfiguredSource.BACKEND
            apiKeySaved -> ConfiguredSource.API_FOOTBALL
            else -> ConfiguredSource.NONE
        }

    val hasSource: Boolean get() = source != ConfiguredSource.NONE

    fun matchingTeams(): List<TeamOption> {
        val query = teamQuery.trim()
        if (query.isEmpty()) return teams
        return teams.filter { option ->
            option.team.name.contains(query, ignoreCase = true) ||
                option.team.shortName.contains(query, ignoreCase = true) ||
                option.league?.name?.contains(query, ignoreCase = true) == true
        }
    }

    fun canAdvanceFrom(step: OnboardingStep): Boolean = when (step) {
        OnboardingStep.WELCOME -> true
        // Deliberately skippable: the leagues page says plainly what skipping costs.
        OnboardingStep.CONNECT -> true
        OnboardingStep.LEAGUES -> selectedLeagueIds.isNotEmpty()
        OnboardingStep.TEAMS -> selected.isNotEmpty()
        OnboardingStep.NOTIFICATIONS -> selected.isNotEmpty() && !saving
    }

    /** The furthest page a swipe may settle on, given what has been filled in so far. */
    val furthestReachableIndex: Int
        get() = OnboardingStep.entries.indexOfFirst { !canAdvanceFrom(it) }
            .takeIf { it >= 0 }
            ?: OnboardingStep.entries.lastIndex
}

private val ALLOWED_SCHEMES = setOf("http", "https")

/**
 * Accepts what people actually paste - a bare host, a trailing slash, no scheme - and
 * returns the canonical URL, or null when the text is not a URL at all.
 */
fun normaliseBackendUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in ALLOWED_SCHEMES) return null
    val host = uri.host ?: return null
    // A single-label host is almost always a typo; localhost is the one real exception.
    if (!host.contains('.') && !host.equals("localhost", ignoreCase = true)) return null
    return withScheme
}
