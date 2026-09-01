package com.tzvi.kickoff.feature.today

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.tzvi.kickoff.ui.component.SectionHeader
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Composable
fun TodayScreen(
    onOpenMatch: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTeams: () -> Unit,
) {
    val viewModel: TodayViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    TodayContent(
        state = state,
        onOpenMatch = onOpenMatch,
        onOpenSettings = onOpenSettings,
        onOpenTeams = onOpenTeams,
        onRefresh = viewModel::refresh,
        onDismissError = viewModel::dismissError,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayContent(
    state: TodayUiState,
    onOpenMatch: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTeams: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    animateIn: Boolean = true,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Held here rather than inside the list items: a section that scrolls out of the
    // LazyColumn and back in must not play its entrance a second time.
    val reveals = remember(animateIn) {
        List(SECTION_COUNT) { SectionReveal(visible = !animateIn) }
    }
    // Tied to the content arriving rather than to the screen: started while the loader is
    // still up, the whole stagger would run behind it and the list would simply appear.
    LaunchedEffect(reveals, state.isLoading) {
        if (!animateIn || state.isLoading) return@LaunchedEffect
        reveals.forEachIndexed { index, reveal ->
            launch {
                delay(index * STAGGER_STEP_MILLIS)
                reveal.play()
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Today") },
                actions = { SettingsAction(onOpenSettings) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { insets ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            if (state.isLoading) {
                LoadingState(label = "Pulling in today's football")
            } else {
                TodayList(
                    state = state,
                    reveals = reveals,
                    onOpenMatch = onOpenMatch,
                    onOpenSettings = onOpenSettings,
                    onOpenTeams = onOpenTeams,
                    onRefresh = onRefresh,
                    onDismissError = onDismissError,
                )
            }
        }
    }
}

@Composable
private fun TodayList(
    state: TodayUiState,
    reveals: List<SectionReveal>,
    onOpenMatch: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTeams: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = 4.dp,
            bottom = BottomPadding,
        ),
    ) {
        if (state.sourceMissing) {
            item(key = "no-source") {
                NoSourceBanner(
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .revealed(reveals[SECTION_BANNER])
                        .padding(bottom = ItemGap),
                )
            }
        }

        val error = state.errorMessage
        if (error != null) {
            item(key = "refresh-error") {
                RefreshErrorBanner(
                    message = error,
                    onRetry = onRefresh,
                    onDismiss = onDismissError,
                    modifier = Modifier
                        .revealed(reveals[SECTION_BANNER])
                        .padding(bottom = ItemGap),
                )
            }
        }

        if (state.liveMatches.isNotEmpty()) {
            item(key = "live-header") {
                SectionHeader(
                    title = "Live now",
                    modifier = Modifier
                        .revealed(reveals[SECTION_LIVE])
                        .padding(top = SectionGap),
                )
            }
            items(state.liveMatches, key = { "live-${it.id}" }) { match ->
                MatchCard(
                    match = match,
                    onClick = onOpenMatch,
                    modifier = Modifier
                        .revealed(reveals[SECTION_LIVE])
                        .padding(bottom = ItemGap),
                )
            }
        }

        if (state.upcomingDays.isNotEmpty()) {
            item(key = "upcoming-header") {
                SectionHeader(
                    title = "Next up",
                    modifier = Modifier
                        .revealed(reveals[SECTION_UPCOMING])
                        .padding(top = SectionGap),
                )
            }
            state.upcomingDays.forEach { day ->
                stickyHeader(key = "day-${day.date}") { _ ->
                    DayHeader(
                        label = day.label,
                        modifier = Modifier.revealed(reveals[SECTION_UPCOMING]),
                    )
                }
                items(day.matches, key = { "upcoming-${it.id}" }) { match ->
                    MatchCard(
                        match = match,
                        onClick = onOpenMatch,
                        modifier = Modifier
                            .revealed(reveals[SECTION_UPCOMING])
                            .padding(bottom = ItemGap),
                    )
                }
            }
        }

        item(key = "teams-header") {
            SectionHeader(
                title = "Your teams",
                modifier = Modifier
                    .revealed(reveals[SECTION_TEAMS])
                    .padding(top = SectionGap),
                action = "See all".takeIf { state.favouriteTeams.isNotEmpty() },
                onAction = onOpenTeams,
            )
        }
        item(key = "teams-row") {
            if (state.favouriteTeams.isEmpty()) {
                NoTeamsState(
                    onOpenTeams = onOpenTeams,
                    modifier = Modifier.revealed(reveals[SECTION_TEAMS]),
                )
            } else {
                FavouriteTeamsRow(
                    teams = state.favouriteTeams,
                    onOpenTeams = onOpenTeams,
                    modifier = Modifier.revealed(reveals[SECTION_TEAMS]),
                )
            }
        }
    }
}

/**
 * One section's entrance: it fades and lifts into place, a beat after the section above.
 *
 * Opacity is duration-based and travel is spring-based, which is the split the motion
 * system draws - a spring on alpha reads as a flicker rather than as movement.
 */
@Stable
private class SectionReveal(visible: Boolean) {
    val alpha = Animatable(if (visible) 1f else 0f)
    val rise = Animatable(if (visible) 0f else 1f)

    suspend fun play() {
        coroutineScope {
            launch { alpha.animateTo(1f, Motion.effects<Float>(Motion.Duration.MEDIUM)) }
            launch { rise.animateTo(0f, Motion.spatial<Float>()) }
        }
    }
}

private fun Modifier.revealed(reveal: SectionReveal): Modifier = graphicsLayer {
    alpha = reveal.alpha.value
    translationY = reveal.rise.value * SectionRise.toPx()
}

private const val SECTION_BANNER = 0
private const val SECTION_LIVE = 1
private const val SECTION_UPCOMING = 2
private const val SECTION_TEAMS = 3
private const val SECTION_COUNT = 4
private const val STAGGER_STEP_MILLIS = 70L

private val ScreenPadding = 16.dp
private val BottomPadding = 40.dp
private val ItemGap = 10.dp
private val SectionGap = 14.dp
private val SectionRise = 20.dp

// ---- previews ---------------------------------------------------------------------

private fun previewTeam(id: Int, name: String, short: String) = Team(id, name, short, null)

private fun previewMatch(
    id: Long,
    home: Team,
    away: Team,
    kickoffAt: Instant,
    phase: MatchPhase = MatchPhase.SCHEDULED,
    elapsedMinutes: Int? = null,
    score: Score? = null,
) = Match(
    id = id,
    leagueId = 39,
    leagueName = "Premier League",
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

private fun previewState(): TodayUiState {
    val now = Instant.now()
    val arsenal = previewTeam(1, "Arsenal", "ARS")
    val chelsea = previewTeam(2, "Chelsea", "CHE")
    val spurs = previewTeam(3, "Tottenham Hotspur", "TOT")
    val liverpool = previewTeam(4, "Liverpool", "LIV")
    val today = LocalDate.now(ZoneId.systemDefault())
    return TodayUiState(
        isLoading = false,
        liveMatches = listOf(
            previewMatch(
                id = 1,
                home = arsenal,
                away = chelsea,
                kickoffAt = now.minus(67, ChronoUnit.MINUTES),
                phase = MatchPhase.SECOND_HALF,
                elapsedMinutes = 67,
                score = Score(2, 1),
            ),
        ),
        upcomingDays = listOf(
            UpcomingDay(
                date = today,
                label = "Today",
                matches = listOf(
                    previewMatch(2, spurs, liverpool, now.plus(4, ChronoUnit.HOURS)),
                ),
            ),
            UpcomingDay(
                date = today.plusDays(1),
                label = "Tomorrow",
                matches = listOf(
                    previewMatch(3, liverpool, arsenal, now.plus(28, ChronoUnit.HOURS)),
                ),
            ),
        ),
        favouriteTeams = listOf(arsenal, chelsea, spurs, liverpool),
    )
}

@Preview(name = "Today - content", heightDp = 900)
@Composable
private fun TodayScreenPreview() {
    KickoffTheme {
        TodayContent(
            state = previewState(),
            onOpenMatch = {},
            onOpenSettings = {},
            onOpenTeams = {},
            onRefresh = {},
            onDismissError = {},
            animateIn = false,
        )
    }
}

@Preview(name = "Today - loading")
@Composable
private fun TodayScreenLoadingPreview() {
    KickoffTheme {
        TodayContent(
            state = TodayUiState(),
            onOpenMatch = {},
            onOpenSettings = {},
            onOpenTeams = {},
            onRefresh = {},
            onDismissError = {},
            animateIn = false,
        )
    }
}

@Preview(name = "Today - no source, no teams", heightDp = 900)
@Composable
private fun TodayScreenNoSourcePreview() {
    KickoffTheme {
        TodayContent(
            state = TodayUiState(isLoading = false, sourceMissing = true),
            onOpenMatch = {},
            onOpenSettings = {},
            onOpenTeams = {},
            onRefresh = {},
            onDismissError = {},
            animateIn = false,
        )
    }
}
