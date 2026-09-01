package com.tzvi.kickoff.feature.matchdetail

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.ui.component.EmptyState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tzvi.kickoff.core.model.LineupPlayer
import com.tzvi.kickoff.core.model.TeamLineup
import com.tzvi.kickoff.feature.player.PlayerRequest
import com.tzvi.kickoff.feature.player.PlayerSheet
import com.tzvi.kickoff.ui.component.LoadingState
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

@Composable
fun MatchDetailScreen(onBack: () -> Unit) {
    val viewModel: MatchDetailViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.toggleFollowing() }

    // The sheet is match-detail state, not navigation: you tap through a whole line-up in
    // one sitting, and pushing a destination per shirt would bury the pitch under a stack.
    var playerRequest by remember { mutableStateOf<PlayerRequest?>(null) }

    MatchDetailContent(
        state = state,
        onBack = onBack,
        onSelectTab = viewModel::selectTab,
        onRefresh = viewModel::refresh,
        // The live card is a notification, so following without POST_NOTIFICATIONS would
        // post into nothing. The bell is the explicit action that earns the one dialog
        // Android grants us - asking on the way in would spend it on a user who never
        // wanted the card, and a second denial silences the dialog for good.
        onToggleFollowing = {
            if (state.following || context.canPostNotifications()) {
                viewModel.toggleFollowing()
            } else {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onPlayerTap = { player, lineup ->
            val id = player.id ?: return@MatchDetailContent
            playerRequest = PlayerRequest(
                playerId = id,
                name = player.name,
                photoUrl = player.photoUrl,
                teamName = lineup.teamName,
                matchId = state.match?.id,
            )
        },
    )

    playerRequest?.let { request ->
        PlayerSheet(request = request, onDismiss = { playerRequest = null })
    }
}

private fun Context.canPostNotifications(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatchDetailContent(
    state: MatchDetailUiState,
    onBack: () -> Unit,
    onSelectTab: (MatchDetailTab) -> Unit,
    onRefresh: () -> Unit,
    onToggleFollowing: () -> Unit = {},
    onPlayerTap: (LineupPlayer, TeamLineup) -> Unit = { _, _ -> },
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.match?.leagueName?.takeIf { it.isNotBlank() } ?: "Match",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFollowing) {
                        Icon(
                            imageVector = if (state.following) {
                                Icons.Filled.NotificationsActive
                            } else {
                                Icons.Outlined.NotificationsNone
                            },
                            contentDescription = if (state.following) {
                                "Stop the live card for this match"
                            } else {
                                "Show a live card for this match"
                            },
                            tint = if (state.following) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
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
            val match = state.match
            when {
                state.isLoading -> LoadingState(label = "Loading the match")
                match == null -> MatchUnavailable(state, onRefresh)
                else -> MatchDetailBody(
                    state = state,
                    match = match,
                    onSelectTab = onSelectTab,
                    onRetry = onRefresh,
                    onPlayerTap = onPlayerTap,
                )
            }
        }
    }
}

@Composable
private fun MatchDetailBody(
    state: MatchDetailUiState,
    match: Match,
    onSelectTab: (MatchDetailTab) -> Unit,
    onRetry: () -> Unit,
    onPlayerTap: (LineupPlayer, TeamLineup) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = BottomPadding),
    ) {
        MatchHero(match = match, modifier = Modifier.padding(horizontal = ScreenPadding))
        Spacer(Modifier.height(SectionGap))
        MatchClockBar(match = match, modifier = Modifier.padding(horizontal = ScreenPadding))

        state.staleMessage?.let { message ->
            Spacer(Modifier.height(SectionGap))
            StaleBanner(
                message = message,
                onRetry = onRetry,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        Spacer(Modifier.height(SectionGap))
        MatchDetailTabs(selected = state.selectedTab, onSelect = onSelectTab)
        Spacer(Modifier.height(SectionGap))
        MatchTabContent(state = state, match = match, onPlayerTap = onPlayerTap)
    }
}

@Composable
private fun MatchDetailTabs(selected: MatchDetailTab, onSelect: (MatchDetailTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        MatchDetailTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun MatchTabContent(
    state: MatchDetailUiState,
    match: Match,
    onPlayerTap: (LineupPlayer, TeamLineup) -> Unit,
) {
    AnimatedContent(
        targetState = state.selectedTab,
        transitionSpec = {
            // Panels travel the way the finger did: forward tabs come in from the right.
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (
                slideInHorizontally(Motion.offsetSpring()) { width -> direction * width / 5 } +
                    fadeIn(Motion.effects(Motion.Duration.MEDIUM))
                ).togetherWith(
                slideOutHorizontally(Motion.offsetSpring()) { width -> -direction * width / 5 } +
                    fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ).using(SizeTransform(clip = false))
        },
        label = "match-tab",
    ) { tab ->
        when (tab) {
            MatchDetailTab.PREVIEW -> MatchPreviewSection(
                match = match,
                prediction = state.prediction,
                headToHead = state.headToHead,
                isLoading = state.predictionLoading,
                predictionsCovered = state.coverage?.predictions != false,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
            MatchDetailTab.TIMELINE -> MatchTimeline(
                entries = state.timeline,
                match = match,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
            MatchDetailTab.LINEUPS -> Column {
                MatchLineupsSection(
                    lineups = state.lineups,
                    match = match,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                    lineupsCovered = state.coverage?.lineups != false,
                    onPlayerTap = onPlayerTap,
                )
                // Above the fold when there is no line-up yet, which is most of the time
                // somebody opens this tab: the XI is published twenty minutes before
                // kick-off and the absences are known days earlier.
                AbsencesSection(
                    absences = state.absences,
                    match = match,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }
            MatchDetailTab.STATS -> MatchStatsSection(
                stats = state.stats,
                match = match,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }
    }
}

/** A refresh that failed over a match we still hold: a banner, not a blank page. */
@Composable
private fun StaleBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text("Retry")
            }
        }
    }
}

/**
 * Nothing to render at all.
 *
 * It still has to be a scroll container: pull-to-refresh reaches the gesture through
 * nested scroll, and retrying is the whole point of this page.
 */
@Composable
private fun MatchUnavailable(state: MatchDetailUiState, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        when {
            state.sourceMissing -> EmptyState(
                title = "No data source",
                body = "matchUP has nowhere to fetch this match from. Add a backend URL " +
                    "or an API-Football key in Settings.",
                icon = Icons.Outlined.CloudOff,
            )
            state.errorMessage != null -> EmptyState(
                title = "Couldn't load this match",
                body = state.errorMessage,
                icon = Icons.Outlined.WifiOff,
                actionLabel = "Try again",
                onAction = onRetry,
            )
            else -> EmptyState(
                title = "Match not found",
                body = "This fixture is no longer in the schedule - it may have been " +
                    "postponed or dropped by the provider.",
                icon = Icons.Outlined.SportsSoccer,
                actionLabel = "Try again",
                onAction = onRetry,
            )
        }
    }
}

internal val ScreenPadding = 16.dp
internal val SectionGap = 16.dp
private val BottomPadding = 40.dp

@Preview(name = "Match - timeline", heightDp = 1000)
@Composable
private fun MatchDetailTimelinePreview() {
    KickoffTheme {
        MatchDetailContent(
            state = MatchDetailUiState(
                matchId = 1,
                match = previewMatch(
                    phase = MatchPhase.SECOND_HALF,
                    elapsedMinutes = 67,
                    score = Score(2, 1),
                    halfTimeScore = Score(1, 1),
                ),
                timeline = previewTimeline(),
                lineups = previewLineups(),
                stats = previewStats(),
                isLoading = false,
            ),
            onBack = {},
            onSelectTab = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Match - line-ups", heightDp = 1400)
@Composable
private fun MatchDetailLineupsPreview() {
    KickoffTheme {
        MatchDetailContent(
            state = MatchDetailUiState(
                matchId = 1,
                match = previewMatch(
                    phase = MatchPhase.SECOND_HALF,
                    elapsedMinutes = 67,
                    score = Score(2, 1),
                ),
                timeline = previewTimeline(),
                lineups = previewLineups(),
                stats = previewStats(),
                selectedTab = MatchDetailTab.LINEUPS,
                isLoading = false,
            ),
            onBack = {},
            onSelectTab = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Match - stats", heightDp = 1000)
@Composable
private fun MatchDetailStatsPreview() {
    KickoffTheme {
        MatchDetailContent(
            state = MatchDetailUiState(
                matchId = 1,
                match = previewMatch(
                    phase = MatchPhase.FINISHED,
                    elapsedMinutes = 90,
                    score = Score(2, 1),
                    halfTimeScore = Score(1, 1),
                ),
                timeline = previewTimeline(),
                stats = previewStats(),
                selectedTab = MatchDetailTab.STATS,
                isLoading = false,
            ),
            onBack = {},
            onSelectTab = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Match - before kick-off", heightDp = 1000)
@Composable
private fun MatchDetailScheduledPreview() {
    KickoffTheme {
        MatchDetailContent(
            state = MatchDetailUiState(
                matchId = 1,
                match = previewMatch(),
                selectedTab = MatchDetailTab.LINEUPS,
                isLoading = false,
            ),
            onBack = {},
            onSelectTab = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Match - loading", heightDp = 800)
@Composable
private fun MatchDetailLoadingPreview() {
    KickoffTheme {
        MatchDetailContent(
            state = MatchDetailUiState(matchId = 1),
            onBack = {},
            onSelectTab = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Match - load failed", heightDp = 800)
@Composable
private fun MatchDetailErrorPreview() {
    KickoffTheme {
        MatchDetailContent(
            state = MatchDetailUiState(
                matchId = 1,
                isLoading = false,
                errorMessage = "Couldn't reach the network.",
            ),
            onBack = {},
            onSelectTab = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Match - stale", heightDp = 1000)
@Composable
private fun MatchDetailStalePreview() {
    KickoffTheme {
        MatchDetailContent(
            state = MatchDetailUiState(
                matchId = 1,
                match = previewMatch(
                    phase = MatchPhase.SECOND_HALF,
                    elapsedMinutes = 67,
                    score = Score(2, 1),
                ),
                timeline = previewTimeline(),
                isLoading = false,
                errorMessage = "Couldn't reach the network.",
            ),
            onBack = {},
            onSelectTab = {},
            onRefresh = {},
        )
    }
}
