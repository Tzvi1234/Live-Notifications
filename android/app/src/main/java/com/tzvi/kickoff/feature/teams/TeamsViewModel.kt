package com.tzvi.kickoff.feature.teams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.NoFootballSourceException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class TeamsViewModel @Inject constructor(
    private val footballRepository: FootballRepository,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val leagueJobs = mutableMapOf<Int, Job>()
    private var fixtureJob: Job? = null
    private var refreshQueued = false
    private var leagueCatalogueJob: Job? = null

    /**
     * The query has to stop moving before anything reaches the network. A request per
     * keystroke would spend the 100 calls a day a free API-Football key gets on a single
     * club name, and the provider ignores terms under [MIN_SEARCH_LENGTH] anyway.
     */
    private val search: Flow<SearchState> = local
        .map { it.query.trim() }
        .distinctUntilChanged()
        .debounce { query -> if (query.length < MIN_SEARCH_LENGTH) 0L else SEARCH_DEBOUNCE_MS }
        .flatMapLatest { query -> searchFlow(query) }

    /**
     * Only a followed team's fixtures are in the cache, so this is a filter rather than a
     * fetch. It is keyed on the open sheet so the query is not running behind a closed one.
     */
    private val sheetFixtures: Flow<List<Match>> = local
        .map { it.sheet?.team?.id }
        .distinctUntilChanged()
        .flatMapLatest { teamId ->
            if (teamId == null) {
                flowOf(emptyList())
            } else {
                footballRepository.upcomingForFavourites.map { matches ->
                    matches
                        .filter { it.home.id == teamId || it.away.id == teamId }
                        .sortedBy { it.kickoffAt }
                        .take(SHEET_FIXTURE_LIMIT)
                }
            }
        }

    val uiState: StateFlow<TeamsUiState> = combine(
        footballRepository.favouriteTeams,
        footballRepository.followedLeagues,
        local,
        search,
        sheetFixtures,
    ) { favourites, leagues, state, searchState, fixtures ->
        val favouriteIds = favourites.mapTo(mutableSetOf()) { it.id }
        TeamsUiState(
            isLoading = false,
            query = state.query,
            search = searchState,
            favourites = favourites,
            favouriteIds = favouriteIds,
            leagues = leagues.map { league ->
                val browse = state.browse[league.id] ?: LeagueBrowse()
                LeagueSection(
                    league = league,
                    expanded = browse.expanded,
                    isLoading = browse.loading,
                    teams = browse.teams,
                    failure = browse.failure,
                )
            },
            errorMessage = state.errorMessage,
            sourceMissing = state.sourceMissing,
            sheet = state.sheet?.let { target ->
                val isFavourite = target.team.id in favouriteIds
                TeamSheetState(
                    team = target.team,
                    leagueId = target.leagueId,
                    leagueName = target.leagueName,
                    isFavourite = isFavourite,
                    fixtures = fixtures,
                    fixturesLoading = isFavourite && state.fixturesRefreshing,
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = TeamsUiState(),
    )

    // ---- search ---------------------------------------------------------------

    fun onQueryChange(value: String) = local.update { it.copy(query = value) }

    // ---- browse by competition ------------------------------------------------

    /**
     * Expanding a competition for the first time is what fetches its squads; every
     * expansion after that reads the list already held in [LocalState.browse].
     */
    fun onToggleLeague(leagueId: Int) {
        val browse = local.value.browse[leagueId] ?: LeagueBrowse()
        val expanded = !browse.expanded
        local.update { it.withBrowse(leagueId) { current -> current.copy(expanded = expanded) } }
        if (expanded && browse.teams.isEmpty()) loadLeague(leagueId)
    }

    fun onRetryLeague(leagueId: Int) = loadLeague(leagueId)

    /** Refills the competition list when onboarding never managed to. */
    fun onLoadCompetitions() {
        if (leagueCatalogueJob?.isActive == true) return
        leagueCatalogueJob = viewModelScope.launch {
            try {
                footballRepository.featuredLeagues()
                report(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                report(error)
            }
        }
    }

    // ---- favourites -----------------------------------------------------------

    fun onToggleFavourite(team: Team, leagueId: Int?, leagueName: String?) {
        viewModelScope.launch {
            if (team.id in footballRepository.favouriteIdsNow()) {
                footballRepository.removeFavourite(team.id)
            } else {
                footballRepository.addFavourite(team, leagueId, leagueName)
                // A team nobody followed until now has no fixtures in the cache, so the
                // sheet and Today would both stay empty without this.
                refreshFixtures()
            }
        }
    }

    fun onRemoveFavourite(teamId: Int) {
        viewModelScope.launch { footballRepository.removeFavourite(teamId) }
    }

    // ---- team sheet -----------------------------------------------------------

    fun onOpenTeam(team: Team, leagueId: Int?, leagueName: String?) {
        local.update { it.copy(sheet = SheetTarget(team, leagueId, leagueName)) }
        viewModelScope.launch {
            if (team.id in footballRepository.favouriteIdsNow()) refreshFixtures()
        }
    }

    fun onCloseSheet() = local.update { it.copy(sheet = null) }

    fun dismissError() = local.update { it.copy(errorMessage = null) }

    // ---- internals ------------------------------------------------------------

    private fun loadLeague(leagueId: Int) {
        if (leagueJobs[leagueId]?.isActive == true) return
        leagueJobs[leagueId] = viewModelScope.launch {
            local.update {
                it.withBrowse(leagueId) { current -> current.copy(loading = true, failure = null) }
            }
            try {
                val teams = footballRepository.teamsInLeague(leagueId).sortedBy { it.name }
                local.update {
                    it.withBrowse(leagueId) { current ->
                        current.copy(
                            loading = false,
                            teams = teams,
                            failure = if (teams.isEmpty()) TeamsFailure.EMPTY else null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                local.update {
                    it.withBrowse(leagueId) { current ->
                        current.copy(loading = false, failure = error.asFailure())
                    }
                }
            }
        }
    }

    /**
     * One request covers every favourite at once, so a second caller normally just rides
     * on the refresh already in flight.
     *
     * [forNewFavourite] is the exception: that refresh read the favourite list *before*
     * the new club was written to it, so it cannot contain the club, and one follow-up
     * run is queued behind it. Starring several clubs in a row still costs a single
     * follow-up rather than a request each.
     */
    private fun refreshFixtures(forNewFavourite: Boolean = false) {
        if (fixtureJob?.isActive == true) {
            refreshQueued = refreshQueued || forNewFavourite
            return
        }
        fixtureJob = viewModelScope.launch {
            local.update { it.copy(fixturesRefreshing = true) }
            try {
                do {
                    refreshQueued = false
                    footballRepository.refreshFixtures()
                } while (refreshQueued)
                report(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                report(error)
            } finally {
                local.update { it.copy(fixturesRefreshing = false) }
            }
        }
    }

    private fun searchFlow(query: String): Flow<SearchState> =
        if (query.length < MIN_SEARCH_LENGTH) {
            flowOf(SearchState(query = query))
        } else {
            flow {
                emit(SearchState(query = query, isSearching = true))
                val outcome = try {
                    val results = footballRepository.searchTeams(query).sortedBy { it.name }
                    SearchState(
                        query = query,
                        results = results,
                        failure = if (results.isEmpty()) TeamsFailure.EMPTY else null,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    SearchState(query = query, failure = error.asFailure())
                }
                emit(outcome)
            }
        }

    /** A success clears both banners; a source that is missing is not a passing error. */
    private fun report(error: Throwable?) = local.update { state ->
        when {
            error == null -> state.copy(sourceMissing = false, errorMessage = null)
            error is NoFootballSourceException -> state.copy(sourceMissing = true)
            else -> state.copy(errorMessage = error.userMessage())
        }
    }

    private fun Throwable.asFailure(): TeamsFailure =
        if (this is NoFootballSourceException) TeamsFailure.NO_SOURCE else TeamsFailure.UNREACHABLE

    private fun Throwable.userMessage(): String = when (this) {
        is IOException -> "Couldn't reach the network. Showing what is already saved."
        else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong loading teams."
    }

    private data class LocalState(
        val query: String = "",
        val browse: Map<Int, LeagueBrowse> = emptyMap(),
        val sheet: SheetTarget? = null,
        val fixturesRefreshing: Boolean = false,
        val errorMessage: String? = null,
        val sourceMissing: Boolean = false,
    ) {
        fun withBrowse(leagueId: Int, block: (LeagueBrowse) -> LeagueBrowse): LocalState =
            copy(browse = browse + (leagueId to block(browse[leagueId] ?: LeagueBrowse())))
    }

    private data class LeagueBrowse(
        val expanded: Boolean = false,
        val loading: Boolean = false,
        val teams: List<Team> = emptyList(),
        val failure: TeamsFailure? = null,
    )

    private data class SheetTarget(val team: Team, val leagueId: Int?, val leagueName: String?)

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        const val SEARCH_DEBOUNCE_MS = 350L
        const val SHEET_FIXTURE_LIMIT = 5
    }
}
