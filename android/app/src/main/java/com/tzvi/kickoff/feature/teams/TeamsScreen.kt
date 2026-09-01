package com.tzvi.kickoff.feature.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.LoadingState
import com.tzvi.kickoff.ui.component.SectionHeader
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffTheme

@Composable
fun TeamsScreen(onOpenMatch: (Long) -> Unit) {
    val viewModel: TeamsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    TeamsContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onToggleLeague = viewModel::onToggleLeague,
        onRetryLeague = viewModel::onRetryLeague,
        onLoadCompetitions = viewModel::onLoadCompetitions,
        onOpenTeam = viewModel::onOpenTeam,
        onCloseSheet = viewModel::onCloseSheet,
        onToggleFavourite = viewModel::onToggleFavourite,
        onRemoveFavourite = viewModel::onRemoveFavourite,
        onDismissError = viewModel::dismissError,
        onOpenMatch = onOpenMatch,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamsContent(
    state: TeamsUiState,
    onQueryChange: (String) -> Unit,
    onToggleLeague: (Int) -> Unit,
    onRetryLeague: (Int) -> Unit,
    onLoadCompetitions: () -> Unit,
    onOpenTeam: (Team, Int?, String?) -> Unit,
    onCloseSheet: () -> Unit,
    onToggleFavourite: (Team, Int?, String?) -> Unit,
    onRemoveFavourite: (Int) -> Unit,
    onDismissError: () -> Unit,
    onOpenMatch: (Long) -> Unit,
    initiallySearching: Boolean = false,
) {
    var searching by rememberSaveable { mutableStateOf(initiallySearching) }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (state.isLoading) {
            LoadingState(label = "Loading your teams")
        } else {
            TeamsList(
                state = state,
                topPadding = topInset + SearchBarSlot,
                onToggleLeague = onToggleLeague,
                onRetryLeague = onRetryLeague,
                onLoadCompetitions = onLoadCompetitions,
                onOpenTeam = onOpenTeam,
                onToggleFavourite = onToggleFavourite,
                onRemoveFavourite = onRemoveFavourite,
                onDismissError = onDismissError,
            )
        }

        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    // The debounced flow in the view model has already run this exact
                    // query; submitting the IME action only puts the keyboard away.
                    onSearch = {},
                    expanded = searching,
                    onExpandedChange = { searching = it },
                    placeholder = { Text("Search clubs") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    },
                )
            },
            expanded = searching,
            onExpandedChange = { searching = it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            SearchResults(
                search = state.search,
                favouriteIds = state.favouriteIds,
                onOpenTeam = { team ->
                    searching = false
                    onOpenTeam(team, null, null)
                },
                onToggleFavourite = { team -> onToggleFavourite(team, null, null) },
                onClearQuery = { onQueryChange("") },
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
    }

    val sheet = state.sheet
    if (sheet != null) {
        TeamSheet(
            state = sheet,
            onDismiss = onCloseSheet,
            onToggleFavourite = { onToggleFavourite(sheet.team, sheet.leagueId, sheet.leagueName) },
            onOpenMatch = onOpenMatch,
        )
    }
}

@Composable
private fun TeamsList(
    state: TeamsUiState,
    topPadding: Dp,
    onToggleLeague: (Int) -> Unit,
    onRetryLeague: (Int) -> Unit,
    onLoadCompetitions: () -> Unit,
    onOpenTeam: (Team, Int?, String?) -> Unit,
    onToggleFavourite: (Team, Int?, String?) -> Unit,
    onRemoveFavourite: (Int) -> Unit,
    onDismissError: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = topPadding,
            bottom = BottomPadding,
        ),
    ) {
        if (state.sourceMissing) {
            item(key = "no-source") {
                SourceMissingBanner(Modifier.padding(bottom = ItemGap))
            }
        }

        val error = state.errorMessage
        if (error != null) {
            item(key = "error") {
                ErrorBanner(
                    message = error,
                    onDismiss = onDismissError,
                    modifier = Modifier.padding(bottom = ItemGap),
                )
            }
        }

        item(key = "favourites-header") { SectionHeader(title = "My teams") }

        if (state.favourites.isEmpty()) {
            item(key = "favourites-empty") {
                EmptyState(
                    title = "No teams yet",
                    body = "Search for a club at the top, or open a competition below, " +
                        "and tap its star. matchUP only tracks matches for the teams " +
                        "you follow.",
                    icon = Icons.Outlined.Groups,
                )
            }
        } else {
            items(items = state.favourites, key = { "favourite-${it.id}" }) { team ->
                FavouriteTeamCard(
                    team = team,
                    onOpen = { onOpenTeam(team, null, null) },
                    onRemove = { onRemoveFavourite(team.id) },
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = Motion.effects(Motion.Duration.SHORT),
                            placementSpec = Motion.offsetSpring(),
                            fadeOutSpec = Motion.effects(Motion.Duration.MEDIUM),
                        )
                        .padding(bottom = ItemGap),
                )
            }
        }

        item(key = "browse-header") {
            SectionHeader(
                title = "Browse by competition",
                modifier = Modifier.padding(top = SectionGap),
            )
        }

        if (state.leagues.isEmpty()) {
            item(key = "browse-empty") {
                EmptyState(
                    title = "No competitions saved",
                    body = "The competition list is written the first time matchUP " +
                        "reaches its data source. Pull it in now, or search for a club " +
                        "by name instead.",
                    icon = Icons.Outlined.SportsSoccer,
                    actionLabel = "Load competitions",
                    onAction = onLoadCompetitions,
                )
            }
        } else {
            items(items = state.leagues, key = { "league-${it.league.id}" }) { section ->
                LeagueCard(
                    section = section,
                    favouriteIds = state.favouriteIds,
                    onToggleExpanded = { onToggleLeague(section.league.id) },
                    onRetry = { onRetryLeague(section.league.id) },
                    onOpenTeam = { team ->
                        onOpenTeam(team, section.league.id, section.league.name)
                    },
                    onToggleFavourite = { team ->
                        onToggleFavourite(team, section.league.id, section.league.name)
                    },
                    modifier = Modifier.padding(bottom = ItemGap),
                )
            }
        }
    }
}

private val ScreenPadding = 16.dp
private val BottomPadding = 40.dp
private val ItemGap = 10.dp
private val SectionGap = 14.dp

/**
 * How much of the top of the list the floating search bar covers: its input field, the
 * 8dp of padding the bar puts either side of it, and a gap before the first card. Taken
 * from the component rather than measured, so it cannot drift out of step with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
private val SearchBarSlot = SearchBarDefaults.InputFieldHeight + 24.dp

// ---- previews ---------------------------------------------------------------------

@Composable
private fun PreviewTeams(state: TeamsUiState, initiallySearching: Boolean = false) {
    KickoffTheme {
        TeamsContent(
            state = state,
            onQueryChange = {},
            onToggleLeague = {},
            onRetryLeague = {},
            onLoadCompetitions = {},
            onOpenTeam = { _, _, _ -> },
            onCloseSheet = {},
            onToggleFavourite = { _, _, _ -> },
            onRemoveFavourite = {},
            onDismissError = {},
            onOpenMatch = {},
            initiallySearching = initiallySearching,
        )
    }
}

@Preview(name = "Teams - content", heightDp = 900)
@Composable
private fun TeamsContentPreview() = PreviewTeams(TeamsSamples.state())

@Preview(name = "Teams - loading")
@Composable
private fun TeamsLoadingPreview() = PreviewTeams(TeamsUiState())

@Preview(name = "Teams - nothing followed", heightDp = 900)
@Composable
private fun TeamsEmptyPreview() = PreviewTeams(
    TeamsUiState(
        isLoading = false,
        leagues = listOf(
            LeagueSection(TeamsSamples.premierLeague),
            LeagueSection(TeamsSamples.laLiga),
        ),
    ),
)

@Preview(name = "Teams - search results", heightDp = 900)
@Composable
private fun TeamsSearchPreview() = PreviewTeams(
    state = TeamsSamples.state().copy(
        query = "tot",
        search = SearchState(
            query = "tot",
            results = listOf(TeamsSamples.spurs, TeamsSamples.chelsea),
        ),
    ),
    initiallySearching = true,
)

@Preview(name = "Teams - no data source", heightDp = 900)
@Composable
private fun TeamsNoSourcePreview() = PreviewTeams(
    TeamsUiState(isLoading = false, sourceMissing = true),
)
