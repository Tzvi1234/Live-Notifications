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

    /**
     * Where the football comes from, and the one input the answer might need.
     *
     * It used to be two pages, the second of which asked for the backend's address. The
     * address is not a question any more - every build ships pointed at one - so the only
     * thing left to type is an API-Football key, and that belongs under the tile that asks
     * for it rather than on a page of its own.
     */
    SOURCE,

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

/**
 * Every club once, whichever competitions it turned up in, in name order.
 *
 * A club in a league and in that league's cup came back once per competition - and that
 * is every club in England and Israel, the two pyramids the catalogue carries down to the
 * domestic cups, plus every Champions League side. The picker keys its rows on the team
 * id, and Compose refuses a duplicate key outright rather than drawing the row twice, so
 * choosing the Premier League together with the FA Cup, or with the Champions League,
 * crashed the step every single time. The first competition a club was seen in is the
 * one it is labelled with.
 */
internal fun Collection<TeamOption>.onceEach(): List<TeamOption> =
    distinctBy { option -> option.team.id }.sortedBy { option -> option.team.name }

data class OnboardingUiState(
    val apiKeyInput: String = "",
    val apiKeySaved: Boolean = false,
    val backendUrlInput: String = "",
    val backendUrlError: String? = null,
    val backendSaved: Boolean = false,
    /** True while a key or URL is being tried against the real thing. */
    val checkingSource: Boolean = false,
    /** What the probe said, good or bad, so the answer lands where the value was typed. */
    val sourceCheck: String? = null,
    val sourceCheckFailed: Boolean = false,
    val demoEnabled: Boolean = false,
    /**
     * Whether a session exists.
     *
     * The server tile is only offered to somebody who has one: it is a shared deployment
     * on one API key, and the predictions it exists for cannot work anonymously either.
     */
    val hasAccount: Boolean = false,
    /** What was tapped on the SOURCE step - which is not yet what is configured. */
    val chosenSource: ConfiguredSource? = null,

    val leaguesLoading: Boolean = false,
    val leagues: List<League> = emptyList(),
    val leaguesFailure: CatalogueError? = null,
    val selectedLeagueIds: Set<Int> = emptySet(),

    val teamsLoading: Boolean = false,

    /** Leagues still to answer, for the "2 of 4" line while the rest arrive. */

    val teamsRemaining: Int = 0,
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

    /**
     * Whether to hold a blocking spinner, as opposed to showing what has arrived.
     *
     * Only true while there is genuinely nothing yet. Once one league has answered its
     * clubs are on screen and the rest arrive underneath them, because four leagues is
     * four round trips and a spinner held for the sum of them reads as a hang.
     */
    val teamsBlocked: Boolean
        get() = teamsPending && teams.isEmpty()

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
        OnboardingStep.SOURCE -> when (source) {
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
        OnboardingStep.SOURCE -> when {
            chosenSource == null -> "Pick one of the three"
            // The server and the demo need nothing typed, so this can only ever be the key.
            chosenSource == ConfiguredSource.API_FOOTBALL && !apiKeySaved ->
                "Save your key to continue"
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
        // The server is already configured and the demo configures itself the moment it
        // is picked, so this only ever blocks on a key that has not been saved yet.
        OnboardingStep.SOURCE ->
            chosenSource != null &&
                (chosenSource != ConfiguredSource.API_FOOTBALL || apiKeySaved)

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
