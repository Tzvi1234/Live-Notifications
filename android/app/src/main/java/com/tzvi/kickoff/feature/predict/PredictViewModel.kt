package com.tzvi.kickoff.feature.predict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.data.predict.PredictRepository
import com.tzvi.kickoff.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class PredictViewModel @Inject constructor(
    private val repository: PredictRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(PredictUiState())
    val uiState: StateFlow<PredictUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = !it.isLoading, errorMessage = null) }

            // The game only exists over a backend. Going direct to API-Football is a
            // perfectly good way to use the rest of the app and simply has no server to
            // hold anybody else's guesses.
            if (settings.backendUrl.first().isBlank()) {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        blocker = PredictBlocker.NEEDS_SERVER,
                    )
                }
                return@launch
            }

            val outcome = runCatching { repository.groups() }
            outcome.fold(
                onSuccess = { groups ->
                    val keep = mutableState.value.selected?.id
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

    fun createGroup(name: String, leagueIds: List<Int>, teamIds: List<Int>) {
        if (name.isBlank()) return
        mutableState.update { it.copy(creating = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = runCatching { repository.createGroup(name.trim(), leagueIds, teamIds) }
            mutableState.update { it.copy(creating = false) }
            outcome.fold(
                onSuccess = { group ->
                    mutableState.update { it.copy(selected = group, blocker = null) }
                    refresh()
                },
                onFailure = { error -> fail(error) },
            )
        }
    }

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

    fun leaveGroup() {
        val groupId = mutableState.value.selected?.id ?: return
        viewModelScope.launch {
            runCatching { repository.leaveGroup(groupId) }
            mutableState.update { it.copy(selected = null) }
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
                members = board.getOrDefault(it.members),
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
        val unauthorised = error is HttpException && error.code() in UNAUTHORISED
        mutableState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                blocker = if (unauthorised) PredictBlocker.NEEDS_ACCOUNT else it.blocker,
                errorMessage = if (unauthorised) null else error.userMessage(),
            )
        }
    }

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
        val UNAUTHORISED = setOf(401, 403)
    }
}
