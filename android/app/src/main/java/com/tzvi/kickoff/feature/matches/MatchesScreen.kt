package com.tzvi.kickoff.feature.matches

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.ui.component.LoadingState
import com.tzvi.kickoff.ui.component.MatchCard
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffTheme
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun MatchesScreen(onOpenMatch: (Long) -> Unit) {
    val viewModel: MatchesViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MatchesContent(
        state = state,
        onOpenMatch = onOpenMatch,
        onSelectDate = viewModel::selectDate,
        onSelectFilter = viewModel::selectFilter,
        onJumpToToday = viewModel::jumpToToday,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatchesContent(
    state: MatchesUiState,
    onOpenMatch: (Long) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onSelectFilter: (MatchFilter) -> Unit,
    onJumpToToday: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(state.monthLabel) },
                actions = {
                    AnimatedVisibility(
                        visible = !state.isOnToday,
                        enter = fadeIn(Motion.effects(Motion.Duration.MEDIUM)),
                        exit = fadeOut(Motion.effects(Motion.Duration.SHORT)),
                    ) {
                        TextButton(onClick = onJumpToToday) { Text("Today") }
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            WeekStrip(
                days = state.days,
                selectedDate = state.selectedDate,
                onSelectDate = onSelectDate,
            )
            Spacer(Modifier.height(SectionGap))
            MatchFilterRow(
                selected = state.filter,
                onSelect = onSelectFilter,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
            Spacer(Modifier.height(SectionGap))

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Only a change of day, or of which face the screen is wearing, is worth a
                // cross-fade: a score arriving for the day already on screen has to update
                // in place, and a filter that keeps the list populated lets the items
                // themselves animate rather than replacing the whole page.
                AnimatedContent(
                    targetState = state,
                    contentKey = { Triple(it.selectedDate, it.isLoading, it.emptyReason) },
                    transitionSpec = {
                        fadeIn(Motion.effects(Motion.Duration.MEDIUM))
                            .togetherWith(fadeOut(Motion.effects(Motion.Duration.SHORT)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "fixtures-day",
                ) { dayState ->
                    DayFixtures(
                        state = dayState,
                        onOpenMatch = onOpenMatch,
                        onRefresh = onRefresh,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayFixtures(
    state: MatchesUiState,
    onOpenMatch: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    val emptyReason = state.emptyReason
    when {
        state.isLoading -> LoadingState(label = "Loading fixtures ${state.dateLabel}")

        // A lazy list rather than a plain Box: pull-to-refresh reaches the gesture through
        // nested scroll, so the empty page has to be a scroll container even with one child
        // in it. Filling the viewport is what keeps the copy centred.
        emptyReason != null -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "empty-${emptyReason.name}") {
                FixturesEmptyState(
                    reason = emptyReason,
                    state = state,
                    onRetry = onRefresh,
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        }

        else -> FixtureList(state = state, onOpenMatch = onOpenMatch, onRefresh = onRefresh)
    }
}

@Composable
private fun FixtureList(
    state: MatchesUiState,
    onOpenMatch: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = 2.dp,
            bottom = BottomPadding,
        ),
    ) {
        val stale = state.staleMessage
        if (stale != null) {
            item(key = "stale") {
                StaleFixturesBanner(
                    message = stale,
                    onRetry = onRefresh,
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = Motion.effects(Motion.Duration.SHORT),
                            placementSpec = Motion.offsetSpring(),
                            fadeOutSpec = Motion.effects(Motion.Duration.SHORT),
                        )
                        .padding(bottom = ItemGap),
                )
            }
        }

        state.groups.forEach { group ->
            item(key = "league-${group.leagueId}") {
                CompetitionHeader(
                    group = group,
                    modifier = Modifier.animateItem(
                        fadeInSpec = Motion.effects(Motion.Duration.SHORT),
                        placementSpec = Motion.offsetSpring(),
                        fadeOutSpec = Motion.effects(Motion.Duration.SHORT),
                    ),
                )
            }
            items(group.matches, key = { it.id }) { match ->
                MatchCard(
                    match = match,
                    onClick = onOpenMatch,
                    showLeague = false,
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = Motion.effects(Motion.Duration.SHORT),
                            placementSpec = Motion.offsetSpring(),
                            fadeOutSpec = Motion.effects(Motion.Duration.SHORT),
                        )
                        .padding(bottom = ItemGap),
                )
            }
        }
    }
}

private val BottomPadding = 40.dp

// ---- previews ---------------------------------------------------------------------

private fun previewTeam(id: Int, name: String, short: String) = Team(id, name, short, null)

private fun previewMatch(
    id: Long,
    leagueId: Int,
    leagueName: String,
    home: Team,
    away: Team,
    kickoffAt: Instant,
    phase: MatchPhase = MatchPhase.SCHEDULED,
    elapsedMinutes: Int? = null,
    score: Score? = null,
) = Match(
    id = id,
    leagueId = leagueId,
    leagueName = leagueName,
    leagueLogoUrl = null,
    round = "Matchweek 4",
    kickoffAt = kickoffAt,
    venue = null,
    phase = phase,
    elapsedMinutes = elapsedMinutes,
    extraMinutes = null,
    home = home,
    away = away,
    score = score,
)

private fun previewDays(today: LocalDate): List<DayChip> = (-7L..21L).map { offset ->
    val date = today.plusDays(offset)
    DayChip(
        date = date,
        weekday = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[date.dayOfWeek.value - 1],
        dayOfMonth = date.dayOfMonth.toString(),
        isToday = offset == 0L,
    )
}

private fun previewState(
    groups: List<CompetitionGroup> = emptyList(),
    isLoading: Boolean = false,
    filter: MatchFilter = MatchFilter.ALL,
    dayMatchCount: Int = groups.sumOf { it.matches.size },
    errorMessage: String? = null,
    sourceMissing: Boolean = false,
): MatchesUiState {
    val today = LocalDate.now()
    return MatchesUiState(
        days = previewDays(today),
        selectedDate = today,
        isOnToday = true,
        monthLabel = "August",
        dateLabel = "today",
        filter = filter,
        groups = groups,
        isLoading = isLoading,
        dayMatchCount = dayMatchCount,
        followedTeamCount = 4,
        errorMessage = errorMessage,
        sourceMissing = sourceMissing,
    )
}

private fun previewGroups(): List<CompetitionGroup> {
    val now = Instant.now()
    val arsenal = previewTeam(1, "Arsenal", "ARS")
    val chelsea = previewTeam(2, "Chelsea", "CHE")
    val spurs = previewTeam(3, "Tottenham Hotspur", "TOT")
    val liverpool = previewTeam(4, "Liverpool", "LIV")
    val madrid = previewTeam(5, "Real Madrid", "RMA")
    val sevilla = previewTeam(6, "Sevilla", "SEV")
    return listOf(
        CompetitionGroup(
            leagueId = 39,
            leagueName = "Premier League",
            leagueLogoUrl = null,
            round = "Matchweek 4",
            matches = listOf(
                previewMatch(
                    id = 1,
                    leagueId = 39,
                    leagueName = "Premier League",
                    home = arsenal,
                    away = chelsea,
                    kickoffAt = now.minus(67, ChronoUnit.MINUTES),
                    phase = MatchPhase.SECOND_HALF,
                    elapsedMinutes = 67,
                    score = Score(2, 1),
                ),
                previewMatch(
                    id = 2,
                    leagueId = 39,
                    leagueName = "Premier League",
                    home = spurs,
                    away = liverpool,
                    kickoffAt = now.plus(3, ChronoUnit.HOURS),
                ),
            ),
        ),
        CompetitionGroup(
            leagueId = 140,
            leagueName = "La Liga",
            leagueLogoUrl = null,
            round = null,
            matches = listOf(
                previewMatch(
                    id = 3,
                    leagueId = 140,
                    leagueName = "La Liga",
                    home = madrid,
                    away = sevilla,
                    kickoffAt = now.plus(5, ChronoUnit.HOURS),
                ),
            ),
        ),
    )
}

@Preview(name = "Matches - fixtures", heightDp = 900)
@Composable
private fun MatchesContentPreview() {
    KickoffTheme {
        MatchesContent(
            state = previewState(groups = previewGroups()),
            onOpenMatch = {},
            onSelectDate = {},
            onSelectFilter = {},
            onJumpToToday = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Matches - loading", heightDp = 900)
@Composable
private fun MatchesLoadingPreview() {
    KickoffTheme {
        MatchesContent(
            state = previewState(isLoading = true),
            onOpenMatch = {},
            onSelectDate = {},
            onSelectFilter = {},
            onJumpToToday = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Matches - no fixtures", heightDp = 900)
@Composable
private fun MatchesEmptyPreview() {
    KickoffTheme {
        MatchesContent(
            state = previewState(),
            onOpenMatch = {},
            onSelectDate = {},
            onSelectFilter = {},
            onJumpToToday = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Matches - load failed", heightDp = 900)
@Composable
private fun MatchesErrorPreview() {
    KickoffTheme {
        MatchesContent(
            state = previewState(errorMessage = "Couldn't reach the network."),
            onOpenMatch = {},
            onSelectDate = {},
            onSelectFilter = {},
            onJumpToToday = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Matches - no source", heightDp = 900)
@Composable
private fun MatchesNoSourcePreview() {
    KickoffTheme {
        MatchesContent(
            state = previewState(sourceMissing = true),
            onOpenMatch = {},
            onSelectDate = {},
            onSelectFilter = {},
            onJumpToToday = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Matches - stale banner", heightDp = 900)
@Composable
private fun MatchesStalePreview() {
    KickoffTheme {
        MatchesContent(
            state = previewState(
                groups = previewGroups(),
                errorMessage = "Couldn't reach the network.",
            ),
            onOpenMatch = {},
            onSelectDate = {},
            onSelectFilter = {},
            onJumpToToday = {},
            onRefresh = {},
        )
    }
}
