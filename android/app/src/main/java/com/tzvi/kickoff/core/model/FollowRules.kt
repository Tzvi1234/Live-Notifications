package com.tzvi.kickoff.core.model

/**
 * Whether a match may be followed automatically.
 *
 * The one rule behind every notification the user did not personally ask for, kept as a
 * pure function because it is the rule that has now gone wrong twice and it needs to be
 * testable without an Android runtime.
 *
 * The empty case is the important one. Both data sources treat an empty team list as "do
 * not filter" - the direct provider returns every in-play match on earth, the backend
 * omits the query parameter - so an empty favourites list used to mean the app adopted
 * arbitrary matches and posted cards for clubs the user had never heard of. Following
 * nothing means being notified about nothing.
 *
 * This governs AUTOMATIC tracking only: the sweep worker and the pre-match alarms. A user
 * who opens a match and taps "follow" has asked for that match by name, and this rule does
 * not apply to them.
 */
fun mayFollowAutomatically(match: Match, favouriteTeamIds: Collection<Int>): Boolean {
    if (favouriteTeamIds.isEmpty()) return false
    return match.home.id in favouriteTeamIds || match.away.id in favouriteTeamIds
}
