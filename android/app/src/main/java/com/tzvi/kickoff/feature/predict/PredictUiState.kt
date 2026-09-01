package com.tzvi.kickoff.feature.predict

import com.tzvi.kickoff.core.model.Rulebook
import com.tzvi.kickoff.core.model.ChatMessage
import com.tzvi.kickoff.core.model.GroupFixture
import com.tzvi.kickoff.core.model.GroupMember
import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.PredictGroup
import com.tzvi.kickoff.core.model.Team

enum class PredictTab(val label: String) {
    FIXTURES("Guesses"),
    TABLE("Table"),
    CHAT("Chat"),
}

/**
 * Why the screen has nothing to show, which is three completely different problems.
 *
 * Signed out is not an error and must not read like one: the rest of the app works
 * perfectly well without an account and this is the one corner that cannot.
 */
enum class PredictBlocker {
    NEEDS_ACCOUNT,
    NEEDS_SERVER,
    NO_GROUPS,
}

/**
 * What the server will accept in a group.
 *
 * It answers anything over these with a 400 and stores nothing - it never trims the list
 * down to fit - so the setup screen holds the same numbers and refuses the tap itself.
 */
object GroupLimits {
    const val NAME = 60
    const val LEAGUES = 15
    const val TEAMS = 20
}

/** Why the last tap did not do what it looked like it was going to do. */
enum class SetupNoticeKind {
    LEAGUE_CAP,
    TEAM_CAP,

    /** A competition came out of the group, and its clubs went with it. */
    TEAMS_DROPPED,
}

data class SetupNotice(val kind: SetupNoticeKind, val message: String)

/** A club as the picker lists it, under the competition it was found in. */
data class SquadOption(val team: Team, val leagueName: String?)

/**
 * The group setup form: a name, the competitions, and the clubs inside them.
 *
 * The rules live on the state rather than in the view model because all of them are
 * arithmetic over the selection - the two caps, and clubs following their competition out
 * of the group - and a rule that is only checked on the wire comes back as a 400 the user
 * cannot act on.
 */
data class GroupSetup(
    /** Null while creating; the group being edited otherwise. */
    val groupId: Long? = null,
    val name: String = "",
    val leagues: List<League> = emptyList(),
    val leaguesLoading: Boolean = false,
    val leaguesError: String? = null,
    /** Insertion-ordered: squads are asked for in the order the competitions were picked. */
    val leagueIds: Set<Int> = emptySet(),
    /** Squads already fetched, keyed by competition, so a re-tick never asks again. */
    val teamsByLeague: Map<Int, List<Team>> = emptyMap(),
    val loadingLeagueId: Int? = null,
    val failedLeagueIds: Set<Int> = emptySet(),
    /** Insertion-ordered, so the chip row follows pick order. */
    val selectedTeams: Map<Int, Team> = emptyMap(),
    /**
     * Clubs an edited group already follows whose squad has not arrived yet.
     *
     * The group sends ids and the picker deals in clubs, so these are held until a squad
     * names them. They are saved as they are in the meantime: a competition that failed
     * to load must not be able to quietly empty the group.
     */
    val pendingTeamIds: Set<Int> = emptySet(),
    val teamQuery: String = "",
    /** The clubs pane, which is a drill-down rather than a step: the name stays behind it. */
    val pickingTeams: Boolean = false,
    val notice: SetupNotice? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
) {
    val isEditing: Boolean get() = groupId != null

    /** Everything that would be saved as a team, named or not yet named. */
    val pickedCount: Int get() = selectedTeams.size + pendingTeamIds.size

    val chosenLeagues: List<League> get() = leagues.filter { it.id in leagueIds }

    /** The next squad to ask for, or null when there is nothing left to ask for. */
    val nextSquadLeagueId: Int?
        get() = leagueIds.firstOrNull { it !in teamsByLeague && it !in failedLeagueIds }

    /** Competitions whose squad came back empty-handed, by name where there is one. */
    val failedLeagueNames: List<String>
        get() = failedLeagueIds.map { id -> leagueName(id) ?: "One competition" }

    val squadsLoading: Boolean get() = loadingLeagueId != null

    val loadingLeagueName: String? get() = loadingLeagueId?.let { leagueName(it) }

    /**
     * Every club in the chosen competitions, once each.
     *
     * Once each matters: a club in both a league and a cup is one club to pick, and
     * picking it twice would spend two of the twenty on the same team.
     */
    val squad: List<SquadOption>
        get() = leagueIds
            .flatMap { id -> teamsByLeague[id].orEmpty().map { SquadOption(it, leagueName(id)) } }
            .distinctBy { option -> option.team.id }
            .sortedBy { option -> option.team.name }

    val pickedTeams: List<Team> get() = selectedTeams.values.toList()

    val teamIdsToSave: List<Int> get() = selectedTeams.keys.toList() + pendingTeamIds

    val canSave: Boolean get() = blockedReason == null

    /**
     * Why the save button is grey, to be printed next to it. A disabled button with no
     * reason beside it is the most confusing thing a form can do.
     */
    val blockedReason: String?
        get() = when {
            saving -> "Saving your group"
            name.isBlank() -> "Give the group a name"
            pickedCount == 0 -> "Pick at least one team"
            else -> null
        }

    fun matchingSquad(): List<SquadOption> {
        val query = teamQuery.trim()
        if (query.isEmpty()) return squad
        return squad.filter { option ->
            option.team.name.contains(query, ignoreCase = true) ||
                option.team.shortName.contains(query, ignoreCase = true) ||
                option.leagueName?.contains(query, ignoreCase = true) == true
        }
    }

    fun isPicked(teamId: Int): Boolean = teamId in selectedTeams || teamId in pendingTeamIds

    fun rename(value: String): GroupSetup = copy(name = value.take(GroupLimits.NAME))

    fun toggleLeague(league: League): GroupSetup {
        if (league.id !in leagueIds) {
            if (leagueIds.size >= GroupLimits.LEAGUES) {
                return copy(
                    notice = SetupNotice(
                        SetupNoticeKind.LEAGUE_CAP,
                        "${GroupLimits.LEAGUES} competitions is as many as a group can " +
                            "follow. Take one out to add ${league.name}.",
                    ),
                )
            }
            return copy(leagueIds = leagueIds + league.id, notice = null)
        }
        val without = copy(leagueIds = leagueIds - league.id).reconciled()
        val dropped = pickedCount - without.pickedCount
        return without.copy(
            notice = if (dropped > 0) {
                SetupNotice(
                    SetupNoticeKind.TEAMS_DROPPED,
                    "${league.name} came out, and took " +
                        "$dropped ${if (dropped == 1) "team" else "teams"} with it.",
                )
            } else {
                null
            },
        )
    }

    fun toggleTeam(team: Team): GroupSetup {
        if (team.id in selectedTeams) {
            return copy(selectedTeams = selectedTeams - team.id, notice = null)
        }
        if (pickedCount >= GroupLimits.TEAMS) {
            return copy(
                notice = SetupNotice(
                    SetupNoticeKind.TEAM_CAP,
                    "${GroupLimits.TEAMS} teams is as many as a group can follow. Take " +
                        "one out to add ${team.name}.",
                ),
            )
        }
        return copy(selectedTeams = selectedTeams + (team.id to team), notice = null)
    }

    fun removeTeam(teamId: Int): GroupSetup = copy(
        selectedTeams = selectedTeams - teamId,
        pendingTeamIds = pendingTeamIds - teamId,
        notice = null,
    )

    fun withSquad(leagueId: Int, teams: List<Team>): GroupSetup =
        copy(teamsByLeague = teamsByLeague + (leagueId to teams), loadingLeagueId = null)
            .reconciled()

    fun leagueName(leagueId: Int): String? = leagues.firstOrNull { it.id == leagueId }?.name

    /**
     * Puts the clubs back in step with the competitions.
     *
     * A club stays picked only while some chosen competition still lists it, which is what
     * makes taking a competition out take its clubs with it. Ids waiting on a squad are
     * the exception until every chosen competition has answered: a list that has not
     * arrived is not evidence that a club is gone.
     */
    private fun reconciled(): GroupSetup {
        val listed = leagueIds
            .flatMap { id -> teamsByLeague[id].orEmpty() }
            .associateBy { team -> team.id }
        val resolved = pendingTeamIds.mapNotNull { id -> listed[id] }
        val everySquadIn = leagueIds.all { it in teamsByLeague }
        return copy(
            selectedTeams = selectedTeams.filterKeys { it in listed } +
                resolved.associateBy { team -> team.id },
            pendingTeamIds = when {
                everySquadIn -> emptySet()
                else -> pendingTeamIds - resolved.mapTo(mutableSetOf()) { team -> team.id }
            },
        )
    }
}

data class PredictUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val blocker: PredictBlocker? = null,
    val groups: List<PredictGroup> = emptyList(),
    val selected: PredictGroup? = null,
    val members: List<GroupMember> = emptyList(),
    /** Whoever created the group, marked on the table even with nothing settled yet. */
    val captainUserId: String? = null,
    /** The server's own scoring rules, or null on a backend too old to send them. */
    val rules: Rulebook? = null,
    val fixtures: List<GroupFixture> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val tab: PredictTab = PredictTab.FIXTURES,
    /** Guesses the user has moved but not yet sent, keyed by match. */
    val drafts: Map<Long, Pair<Int, Int>> = emptyMap(),
    /** Matches whose guess is in flight, so their card can show it. */
    val saving: Set<Long> = emptySet(),
    val errorMessage: String? = null,
    /** The setup form, which owns the whole screen while it is open. */
    val setup: GroupSetup? = null,
    val joining: Boolean = false,
) {
    /** The one match to put at the top of the table: whichever of the group's is in play. */
    val liveFixture: GroupFixture?
        get() = fixtures.firstOrNull { it.isLive }

    val openFixtures: List<GroupFixture> get() = fixtures.filter { it.isOpen }

    val settledFixtures: List<GroupFixture> get() = fixtures.filterNot { it.isOpen }

    /** Only the owner may change a group: the server refuses the PATCH from anyone else. */
    val canEditSelected: Boolean get() = selected?.isOwner == true

    /** What the stepper should show: the unsent draft if there is one, else what was sent. */
    fun draftFor(fixture: GroupFixture): Pair<Int, Int> =
        drafts[fixture.matchId]
            ?: fixture.myPrediction?.let { it.home to it.away }
            ?: (0 to 0)

    fun isDirty(fixture: GroupFixture): Boolean {
        val draft = drafts[fixture.matchId] ?: return false
        val sent = fixture.myPrediction ?: return true
        return draft != (sent.home to sent.away)
    }
}
