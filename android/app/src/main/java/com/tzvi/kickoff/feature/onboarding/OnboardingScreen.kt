package com.tzvi.kickoff.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

private val DOT_SIZE = 8.dp
private val DOT_ACTIVE_WIDTH = 24.dp
private val DOT_GAP = 6.dp
private const val CONTENT_LAG = 0.18f
private const val EDGE_ALPHA = 0.2f

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { OnboardingStep.entries.size })
    val step = OnboardingStep.at(pagerState.currentPage)

    val goTo: (Int) -> Unit = { target ->
        val page = target.coerceIn(OnboardingStep.entries.indices)
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    // Re-read on every resume, so a permission granted in system settings is reflected
    // the moment the user comes back - and so nothing is ever requested on cold start.
    LifecycleResumeEffect(Unit) {
        viewModel.setNotificationsGranted(hasNotificationPermission(context))
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onNotificationPermissionResult(granted) }

    LaunchedEffect(state.completed) { if (state.completed) onFinished() }

    LaunchedEffect(pagerState.settledPage, state.source) {
        when (OnboardingStep.at(pagerState.settledPage)) {
            OnboardingStep.LEAGUES -> viewModel.loadLeagues()
            OnboardingStep.TEAMS -> viewModel.loadTeams()
            else -> Unit
        }
    }

    // A swipe may run ahead of what has been filled in; it settles, then eases back to
    // the last page whose requirement is met rather than refusing the drag outright.
    LaunchedEffect(pagerState.settledPage, state.furthestReachableIndex) {
        val limit = state.furthestReachableIndex
        if (pagerState.settledPage > limit) pagerState.animateScrollToPage(limit)
    }

    BackHandler(enabled = pagerState.currentPage > 0) { goTo(pagerState.currentPage - 1) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                pageSpacing = 8.dp,
                beyondViewportPageCount = 1,
            ) { page ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .pageMotion(pagerState, page),
                ) {
                    when (OnboardingStep.at(page)) {
                        OnboardingStep.WELCOME -> WelcomePage(
                            onGetStarted = { goTo(OnboardingStep.CONNECT.ordinal) },
                        )

                        OnboardingStep.CONNECT -> ConnectPage(
                            state = state,
                            onApiKeyChange = viewModel::onApiKeyChange,
                            onSaveApiKey = viewModel::saveApiKey,
                            onBackendUrlChange = viewModel::onBackendUrlChange,
                            onSaveBackendUrl = viewModel::saveBackendUrl,
                            onSkip = { goTo(OnboardingStep.LEAGUES.ordinal) },
                        )

                        OnboardingStep.LEAGUES -> LeaguesPage(
                            state = state,
                            onToggleLeague = viewModel::toggleLeague,
                            onRetry = { viewModel.loadLeagues(force = true) },
                            onFixSource = { goTo(OnboardingStep.CONNECT.ordinal) },
                        )

                        OnboardingStep.TEAMS -> TeamsPage(
                            state = state,
                            onQueryChange = viewModel::onTeamQueryChange,
                            onToggleTeam = viewModel::toggleTeam,
                            onRemoveTeam = viewModel::removeTeam,
                            onRetry = { viewModel.loadTeams(force = true) },
                            onFixSource = { goTo(OnboardingStep.CONNECT.ordinal) },
                        )

                        OnboardingStep.NOTIFICATIONS -> NotificationsPage(
                            state = state,
                            onRequestPermission = {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            onOpenSystemSettings = { openNotificationSettings(context) },
                        )
                    }
                }
            }

            OnboardingControls(
                step = step,
                state = state,
                pagerState = pagerState,
                onBack = { goTo(pagerState.currentPage - 1) },
                onNext = {
                    if (step == OnboardingStep.NOTIFICATIONS) {
                        viewModel.finish()
                    } else {
                        goTo(pagerState.currentPage + 1)
                    }
                },
            )
        }
    }
}

@Composable
private fun OnboardingControls(
    step: OnboardingStep,
    state: OnboardingUiState,
    pagerState: PagerState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The welcome page carries its own "Get started" button, so the bar there is dots only.
    val showButtons = step != OnboardingStep.WELCOME
    val isLast = step == OnboardingStep.NOTIFICATIONS

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OnboardingSpacing.screen, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        PageIndicator(pagerState = pagerState)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = showButtons,
                enter = fadeIn(Motion.effects(Motion.Duration.SHORT)),
                exit = fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ) {
                TextButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Back")
                }
            }
            AnimatedVisibility(
                visible = showButtons,
                enter = fadeIn(Motion.effects(Motion.Duration.SHORT)),
                exit = fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ) {
                Button(onClick = onNext, enabled = state.canAdvanceFrom(step)) {
                    if (state.saving) {
                        KickoffLoader(
                            size = 18.dp,
                            ringColor = MaterialTheme.colorScheme.onPrimary,
                            panelColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f),
                        )
                    } else {
                        Text(if (isLast) "Finish" else "Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(pagerState: PagerState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pagerState.pageCount) { index ->
            val active = pagerState.currentPage == index
            val width by animateDpAsState(
                targetValue = if (active) DOT_ACTIVE_WIDTH else DOT_SIZE,
                animationSpec = Motion.dpSpring(),
                label = "indicator-width",
            )
            val color by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                animationSpec = Motion.effects(Motion.Duration.SHORT),
                label = "indicator-color",
            )
            Box(
                Modifier
                    .height(DOT_SIZE)
                    .width(width)
                    .clip(KickoffShapeTokens.pill)
                    .background(color),
            )
        }
    }
}

/**
 * The page content trails the swipe and fades towards the edges, so the five steps read
 * as one object sliding under the frame rather than as five separate screens.
 */
private fun Modifier.pageMotion(pagerState: PagerState, page: Int): Modifier = graphicsLayer {
    val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
    translationX = size.width * offset * CONTENT_LAG
    alpha = lerp(EDGE_ALPHA, 1f, 1f - abs(offset).coerceIn(0f, 1f))
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/** The escape hatch once the system dialog has been spent: not every OEM has this screen. */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Preview(name = "Controls - mid flow")
@Composable
private fun OnboardingControlsPreview() {
    KickoffTheme {
        OnboardingControls(
            step = OnboardingStep.TEAMS,
            state = OnboardingUiState(),
            pagerState = rememberPagerState(initialPage = 3) { OnboardingStep.entries.size },
            onBack = {},
            onNext = {},
        )
    }
}
