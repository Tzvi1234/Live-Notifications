package com.tzvi.kickoff.feature.onboarding

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Team
import java.net.URI

/**
 * The pages of the flow, in the order they are swiped through.
 *
 * Each step carries its own heading. The page bodies used to print their own, which meant
 * a scrolling page could push its title off the top and leave the user looking at controls
 * with nothing naming them; now one fixed header above the pager reads this instead.
 */
enum class OnboardingStep {
    /** The splash. Owns the whole screen and is the one step the frame folds away for. */
    WELCOME,

    /** Three tiles and nothing else: which of the three ways in do you want. */
    SOURCE,

    /** Only the chosen way's one input. Its heading changes with the choice. */
    SETUP,

    LEAGUES,
    TEAMS,
    ALERTS,

    /** The closing beat: what was picked, and what happens from here. */
    READY,
    ;

    /** 1-based position among the steps that ask for something; welcome is 0. */
    val number: Int get() = ordinal

    companion object {
        /** Everything after the welcome splash: the steps the header and progress count. */
        val counted: List<OnboardingStep> = entries.drop(1)

        fun at(index: Int): OnboardingStep = entries[index.coerceIn(entries.indices)]
    }
}

/** Which source will actually serve requests, given what has been saved. */
enum class ConfiguredSource { NONE, API_FOOTBALL, BACKEND, DEMO }

/** Why a catalogue fetch produced nothing. Each case gets its own copy on screen. */
enum class CatalogueFailure { NO_SOURCE, UNREACHABLE, EMPTY }

/**
 * A failure plus what the source actually said.
 *
 * The kind alone drove the copy for a long time, and it hid the one sentence that made
 * a problem fixable: "leagues load, teams do not" reads as a broken app until you can
 * see the provider answering "your plan does not include this season".
 */
data class CatalogueError(
    val kind: CatalogueFailure,
    /** Verbatim from the provider or the transport. Null when there was nothing to say. */
    val detail: String? = null,
)

/** A selectable team, carrying the league it came from so the favourite keeps that link. */
data class TeamOption(val team: Team, val league: League?)

data class OnboardingUiState(
    val apiKeyInput: String = "",
    val apiKeySaved: Boolean = false,
    val backendUrlInput: String = "",
    val backendUrlError: String? = null,
    val backendSaved: Boolean = false,
    val demoEnabled: Boolean = false,
    /** What was tapped on the SOURCE step - which is not yet what is configured. */
    val chosenSource: ConfiguredSource? = null,

    val leaguesLoading: Boolean = false,
    val leagues: List<League> = emptyList(),
    val leaguesFailure: CatalogueError? = null,
    val selectedLeagueIds: Set<Int> = emptySet(),

    val teamsLoading: Boolean = false,
    val teams: List<TeamOption> = emptyList(),
    val teamsFailure: CatalogueError? = null,
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
            demoEnabled -> ConfiguredSource.DEMO
            backendSaved -> ConfiguredSource.BACKEND
            apiKeySaved -> ConfiguredSource.API_FOOTBALL
            else -> ConfiguredSource.NONE
        }

    val hasSource: Boolean get() = source != ConfiguredSource.NONE

    /**
     * True until a fetch has actually answered.
     *
     * The pager composes the page after the current one, so these lists are read while
     * still untouched: an empty list there means "not asked yet", not "nothing to show",
     * and the two need completely different copy.
     */
    val leaguesPending: Boolean
        get() = leaguesLoading || (leagues.isEmpty() && leaguesFailure == null)

    val teamsPending: Boolean
        get() = teamsLoading || (teams.isEmpty() && teamsFailure == null)

    fun matchingTeams(): List<TeamOption> {
        val query = teamQuery.trim()
        if (query.isEmpty()) return teams
        return teams.filter { option ->
            option.team.name.contains(query, ignoreCase = true) ||
                option.team.shortName.contains(query, ignoreCase = true) ||
                option.league?.name?.contains(query, ignoreCase = true) == true
        }
    }

    /** The chip in the step header: what this step has to show for itself so far. */
    fun statusFor(step: OnboardingStep): String? = when (step) {
        OnboardingStep.WELCOME, OnboardingStep.READY -> null
        OnboardingStep.SOURCE, OnboardingStep.SETUP -> when (source) {
            ConfiguredSource.NONE -> null
            ConfiguredSource.API_FOOTBALL -> "API KEY"
            ConfiguredSource.BACKEND -> "BACKEND"
            ConfiguredSource.DEMO -> "DEMO DATA"
        }

        OnboardingStep.LEAGUES -> selectedLeagueIds.size.takeIf { it > 0 }?.let { "$it PICKED" }
        OnboardingStep.TEAMS -> selected.size.takeIf { it > 0 }?.let { "$it PICKED" }
        OnboardingStep.ALERTS -> if (notificationsGranted) "ALLOWED" else null
    }

    /**
     * Why Next is greyed out, in the footer beside it. A disabled button with no reason
     * next to it is the single most confusing thing a wizard can do.
     */
    fun blockedReason(step: OnboardingStep): String? = when (step) {
        OnboardingStep.SOURCE -> "Pick one of the three".takeIf { chosenSource == null }
        OnboardingStep.SETUP -> when (chosenSource) {
            ConfiguredSource.API_FOOTBALL -> "Save your key to continue".takeIf { !hasSource }
            ConfiguredSource.BACKEND -> "Save the URL to continue".takeIf { !hasSource }
            else -> null
        }

        OnboardingStep.LEAGUES ->
            "Pick at least one competition".takeIf { selectedLeagueIds.isEmpty() }

        OnboardingStep.TEAMS -> "Pick at least one team".takeIf { selected.isEmpty() }
        OnboardingStep.READY -> when {
            saving -> "Saving your choices"
            selected.isEmpty() -> "Go back and pick a team"
            else -> null
        }

        else -> null
    }

    fun canAdvanceFrom(step: OnboardingStep): Boolean = when (step) {
        OnboardingStep.WELCOME -> true
        OnboardingStep.SOURCE -> chosenSource != null
        // Demo configures itself the moment it is picked, so this only ever blocks on a
        // key or a URL that has not been saved yet.
        OnboardingStep.SETUP -> hasSource
        OnboardingStep.LEAGUES -> selectedLeagueIds.isNotEmpty()
        OnboardingStep.TEAMS -> selected.isNotEmpty()
        OnboardingStep.ALERTS -> true
        OnboardingStep.READY -> selected.isNotEmpty() && !saving
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
