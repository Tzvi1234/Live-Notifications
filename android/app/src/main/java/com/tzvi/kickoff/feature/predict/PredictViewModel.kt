package com.tzvi.kickoff.feature.predict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.core.model.PredictGroup
import com.tzvi.kickoff.data.auth.AuthRepository
import com.tzvi.kickoff.data.auth.AuthState
import com.tzvi.kickoff.data.predict.PendingInvite
import com.tzvi.kickoff.data.predict.PredictRepository
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * The game, and the form that decides what the game is played on.
 *
 * Setting a group up is part of this view model rather than one of its own: the form's
 * only outputs are a group to select and a fixture list to reload, both of which are this
 * screen's state, and a second view model would have to hand them back across a callback
 * anyway.
 */
@HiltViewModel
class PredictViewModel @Inject constructor(
    private val repository: PredictRepository,
    private val football: FootballRepository,
    private val pendingInvite: PendingInvite,
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(PredictUiState())
    val uiState: StateFlow<PredictUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var leaguesJob: Job? = null
    private var squadJob: Job? = null

    init {
        refresh()
        // Signing in happens on another destination and this view model outlives the trip,
        // so nothing else would ever re-ask. Without this the screen keeps saying "sign in
        // to play" to somebody who just did, and an invitation waiting on an account -
        // which is the whole point of the link a friend sends - is never spent.
        viewModelScope.launch {
            auth.state
                .map { it is AuthState.SignedIn }
                .distinctUntilChanged()
                .collect { signedIn ->
                    if (signedIn && mutableState.value.blocker == PredictBlocker.NEEDS_ACCOUNT) {
                        mutableState.update { it.copy(isLoading = true, blocker = null) }
                        refresh()
                    }
                }
        }
    }

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = !it.isLoading, errorMessage = null) }

            // The game only exists over a backend. Going direct to API-Football is a
            // perfectly good way to use the rest of the app and simply has no server to
            // hold anybody else's guesses - the backend URL is no longer the test for
            // that, since every install ships pointed at one.
            val direct = settings.useDirectApi.first() && settings.apiFootballKey.first().isNotBlank()
            if (direct || settings.backendUrl.first().isBlank()) {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        blocker = PredictBlocker.NEEDS_SERVER,
                    )
                }
                return@launch
            }

            // Before the list is asked for, so a group joined from a link is already in
            // the answer rather than arriving a round trip later.
            val joined = redeemInvite()

            val outcome = runCatching { repository.groups() }
            outcome.fold(
                onSuccess = { groups ->
                    val keep = joined?.id ?: mutableState.value.selected?.id
                    val selected = groups.firstOrNull { it.id == keep } ?: groups.firstOrNull()
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            groups = groups,
                            selected = selected,
                            blocker = if (groups.isEmpty()) PredictBlocker.NO_GROUPS else null,
                        )
                    }
                    if (selected != null) loadGroup(selected.id)
                },
                onFailure = { error -> fail(error) },
            )
        }
    }

    fun selectGroup(groupId: Long) {
        val group = mutableState.value.groups.firstOrNull { it.id == groupId } ?: return
        mutableState.update {
            it.copy(selected = group, fixtures = emptyList(), members = emptyList(), chat = emptyList())
        }
        viewModelScope.launch { loadGroup(groupId) }
    }

    fun selectTab(tab: PredictTab) {
        mutableState.update { it.copy(tab = tab) }
        val groupId = mutableState.value.selected?.id ?: return
        // The chat and the table both go stale the moment you look away from them, and
        // both are cheap; the guesses are refreshed by whatever brought you here.
        if (tab != PredictTab.FIXTURES) viewModelScope.launch { loadGroup(groupId) }
    }

    /**
     * Moves a guess without sending it.
     *
     * Sending on every tap of a stepper would put a write on the wire per goal typed, and
     * would make "2-1" arrive as 1-0, 2-0 then 2-1. The card commits explicitly.
     */
    fun adjust(matchId: Long, homeDelta: Int, awayDelta: Int) {
        mutableState.update { state ->
            val fixture = state.fixtures.firstOrNull { it.matchId == matchId } ?: return@update state
            if (!fixture.isOpen) return@update state
            val (home, away) = state.draftFor(fixture)
            val next = (home + homeDelta).coerceIn(0, MAX_GOALS) to
                (away + awayDelta).coerceIn(0, MAX_GOALS)
            state.copy(drafts = state.drafts + (matchId to next))
        }
    }

    fun submit(matchId: Long) {
        val state = mutableState.value
        val groupId = state.selected?.id ?: return
        val fixture = state.fixtures.firstOrNull { it.matchId == matchId } ?: return
        val (home, away) = state.draftFor(fixture)
        mutableState.update { it.copy(saving = it.saving + matchId) }
        viewModelScope.launch {
            val outcome = runCatching { repository.predict(groupId, matchId, home, away) }
            mutableState.update { it.copy(saving = it.saving - matchId) }
            outcome.fold(
                // Reload rather than patch the row locally: a 409 for a kick-off that has
                // already happened is the server's to decide, and the reload is what makes
                // the card go read-only at the same moment for everybody.
                onSuccess = { loadGroup(groupId) },
                onFailure = { error -> fail(error) },
            )
        }
    }

    // ---- the setup form -------------------------------------------------------

    fun newGroup() {
        mutableState.update { it.copy(setup = GroupSetup(), errorMessage = null) }
        loadSetupLeagues()
    }

    fun editGroup() {
        val group = mutableState.value.selected ?: return
        // The server refuses the PATCH from anyone but the owner, so a member must never
        // get as far as filling the form in.
        if (!group.isOwner) return
        mutableState.update {
            it.copy(
                setup = GroupSetup(
                    groupId = group.id,
                    name = group.name,
                    leagueIds = group.leagueIds.toSet(),
                    pendingTeamIds = group.teamIds.toSet(),
                ),
                errorMessage = null,
            )
        }
        loadSetupLeagues()
        loadSquads()
    }

    fun closeSetup() {
        leaguesJob?.cancel()
        squadJob?.cancel()
        mutableState.update { it.copy(setup = null) }
    }

    fun nameGroup(value: String) = updateSetup { it.rename(value) }

    fun toggleLeague(league: League) {
        updateSetup { it.toggleLeague(league) }
        loadSquads()
    }

    fun toggleTeam(team: Team) = updateSetup { it.toggleTeam(team) }

    fun removeTeam(teamId: Int) = updateSetup { it.removeTeam(teamId) }

    fun searchSquad(query: String) = updateSetup { it.copy(teamQuery = query) }

    /** The clubs pane. Leaving it drops the search, which belonged to that visit. */
    fun pickTeams(picking: Boolean) = updateSetup {
        it.copy(pickingTeams = picking, teamQuery = if (picking) it.teamQuery else "")
    }

    fun dismissNotice() = updateSetup { it.copy(notice = null) }

    fun retryLeagues() = loadSetupLeagues()

    fun retrySquads() {
        updateSetup { it.copy(failedLeagueIds = emptySet()) }
        loadSquads()
    }

    fun saveGroup() {
        val setup = mutableState.value.setup ?: return
        if (!setup.canSave) return
        updateSetup { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val name = setup.name.trim()
            val leagueIds = setup.leagueIds.toList()
            val teamIds = setup.teamIdsToSave
            val outcome = runCatching {
                val groupId = setup.groupId
                if (groupId == null) {
                    repository.createGroup(name, leagueIds, teamIds)
                } else {
                    repository.updateGroup(groupId, name, leagueIds, teamIds)
                }
            }
            outcome.fold(
                onSuccess = { group ->
                    squadJob?.cancel()
                    leaguesJob?.cancel()
                    // Selecting it here rather than letting the refresh choose keeps a
                    // freshly created group on screen instead of whichever came back first.
                    mutableState.update { it.copy(setup = null, selected = group, blocker = null) }
                    refresh()
                },
                onFailure = { error ->
                    if (error.isUnauthorised) {
                        mutableState.update { it.copy(setup = null) }
                        fail(error)
                    } else {
                        updateSetup { it.copy(saving = false, saveError = error.userMessage()) }
                    }
                },
            )
        }
    }

    private fun loadSetupLeagues() {
        if (leaguesJob?.isActive == true) return
        if (mutableState.value.setup?.leagues?.isNotEmpty() == true) return
        leaguesJob = viewModelScope.launch {
            updateSetup { it.copy(leaguesLoading = true, leaguesError = null) }
            try {
                val leagues = football.featuredLeagues()
                updateSetup {
                    it.copy(
                        leaguesLoading = false,
                        leagues = leagues,
                        leaguesError = if (leagues.isEmpty()) NO_LEAGUES_MESSAGE else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateSetup { it.copy(leaguesLoading = false, leaguesError = error.userMessage()) }
            }
        }
    }

    /**
     * Squads, one competition at a time, and only for competitions not already held.
     *
     * Fifteen requests fired at once is the shape that gets rate-limited - each one is a
     * provider call the backend has to make and pay for - and nothing on screen needs the
     * fifteenth list while the first is still being read. Serial also means the pane fills
     * in pick order, so the competition just ticked is the one that appears next.
     */
    private fun loadSquads() {
        if (squadJob?.isActive == true) return
        squadJob = viewModelScope.launch {
            while (true) {
                val leagueId = mutableState.value.setup?.nextSquadLeagueId ?: return@launch
                updateSetup { it.copy(loadingLeagueId = leagueId) }
                try {
                    val teams = football.teamsInLeague(leagueId)
                    updateSetup { it.withSquad(leagueId, teams) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Recorded rather than retried: the loop would otherwise spin on a
                    // competition the source has no squad for, and the other picks are
                    // still worth having.
                    updateSetup {
                        it.copy(
                            loadingLeagueId = null,
                            failedLeagueIds = it.failedLeagueIds + leagueId,
                        )
                    }
                }
            }
        }
    }

    private fun updateSetup(block: (GroupSetup) -> GroupSetup) = mutableState.update { state ->
        val setup = state.setup ?: return@update state
        state.copy(setup = block(setup))
    }

    // ---- joining, and the rest of the group -----------------------------------

    fun joinGroup(code: String) {
        if (code.isBlank()) return
        mutableState.update { it.copy(joining = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = runCatching { repository.joinGroup(code) }
            mutableState.update { it.copy(joining = false) }
            outcome.fold(
                onSuccess = { group ->
                    mutableState.update { it.copy(selected = group, blocker = null) }
                    refresh()
                },
                onFailure = { error -> fail(error) },
            )
        }
    }

    /**
     * Spends an invite that arrived as a link, if one is waiting.
     *
     * A code the server has ruled on is spent whichever way it ruled - retrying a code it
     * has already refused would refuse it again on every pull to refresh. A code that
     * failed on the network, or because there is no account yet, is left parked: signing
     * in is the whole point of the link, and it must survive that.
     */
    private suspend fun redeemInvite(): PredictGroup? {
        val code = pendingInvite.code.value ?: return null
        val outcome = runCatching { repository.joinGroup(code) }
        outcome.fold(
            onSuccess = { pendingInvite.consume() },
            onFailure = { error ->
                if (error is HttpException && !error.isUnauthorised) {
                    pendingInvite.consume()
                    mutableState.update { it.copy(errorMessage = INVITE_FAILED_MESSAGE) }
                }
            },
        )
        return outcome.getOrNull()
    }

    /**
     * Leaves the group, keeping the predictions already made.
     *
     * The server refuses this for the owner, whose only exit is [deleteGroup] - nothing
     * here can hand the captaincy on, and a group whose owner has left could never be
     * edited or deleted by anybody again.
     */
    fun leaveGroup() {
        val groupId = mutableState.value.selected?.id ?: return
        viewModelScope.launch {
            runCatching { repository.leaveGroup(groupId) }
                .onFailure { error -> fail(error) }
                .onSuccess { mutableState.update { it.copy(selected = null) } }
            refresh()
        }
    }

    /**
     * Deletes the group, for the captain only.
     *
     * Everything goes with it - members, picks, the table and the chat - which is why the
     * screen asks first. The server enforces the ownership check too; this is the copy of
     * it that stops the button being offered to somebody it would only refuse.
     */
    fun deleteGroup() {
        val groupId = mutableState.value.selected?.id ?: return
        viewModelScope.launch {
            runCatching { repository.deleteGroup(groupId) }
                .onFailure { error -> fail(error) }
                .onSuccess { mutableState.update { it.copy(selected = null) } }
            refresh()
        }
    }

    fun sendChat(text: String) {
        val groupId = mutableState.value.selected?.id ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { repository.sendChat(groupId, text) }
                .onSuccess { message ->
                    mutableState.update { it.copy(chat = it.chat + message) }
                }
                .onFailure { error -> fail(error) }
        }
    }

    fun dismissError() = mutableState.update { it.copy(errorMessage = null) }

    private suspend fun loadGroup(groupId: Long) {
        val fixtures = runCatching { repository.fixtures(groupId) }
        val board = runCatching { repository.leaderboard(groupId) }
        val chat = runCatching { repository.chat(groupId) }
        val failure = listOf(fixtures, board, chat).firstNotNullOfOrNull { it.exceptionOrNull() }
        if (failure != null && fixtures.isFailure) {
            fail(failure)
            return
        }
        mutableState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                fixtures = fixtures.getOrDefault(emptyList()),
                members = board.getOrNull()?.members ?: it.members,
                captainUserId = board.getOrNull()?.captainUserId ?: it.captainUserId,
                rules = board.getOrNull()?.rules ?: it.rules,
                chat = chat.getOrDefault(it.chat),
                // Anything still on the pad for a match that has since kicked off is gone;
                // keeping it would leave a number on screen that can no longer be sent.
                drafts = it.drafts.filterKeys { matchId ->
                    fixtures.getOrDefault(emptyList()).any { f -> f.matchId == matchId && f.isOpen }
                },
                blocker = null,
            )
        }
    }

    private fun fail(error: Throwable) {
        mutableState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                blocker = if (error.isUnauthorised) PredictBlocker.NEEDS_ACCOUNT else it.blocker,
                errorMessage = if (error.isUnauthorised) null else error.userMessage(),
            )
        }
    }

    private val Throwable.isUnauthorised: Boolean
        get() = this is HttpException && code() in UNAUTHORISED

    private fun Throwable.userMessage(): String = when {
        this is IOException -> "Couldn't reach the server."
        this is HttpException && code() == CONFLICT ->
            "That match has kicked off - guesses are closed."
        this is HttpException && code() == NOT_FOUND -> "That group no longer exists."
        this is HttpException && code() == SERVICE_UNAVAILABLE ->
            "The server has no accounts configured, so the game is switched off there."
        else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
    }

    private companion object {
        const val MAX_GOALS = 20
        const val CONFLICT = 409
        const val NOT_FOUND = 404
        const val SERVICE_UNAVAILABLE = 503
        const val NO_LEAGUES_MESSAGE = "The server listed no competitions to choose from."
        const val INVITE_FAILED_MESSAGE =
            "That invitation didn't work. Ask your friend to send the code again."
        val UNAUTHORISED = setOf(401, 403)
    }
}
