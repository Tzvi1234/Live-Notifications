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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffTheme
import kotlinx.coroutines.launch

/**
 * A fixed frame with one moving part.
 *
 * Header on top (where you are, what this step wants), pager in the middle (the controls
 * for this step and nothing else), footer at the bottom (Back, Next, and the reason Next
 * is greyed out when it is). Every step lands its content in the same place, so moving
 * through the flow never asks the user to re-find anything.
 */
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
            // The welcome splash is the one page that owns the whole screen, so the frame
            // folds away for it rather than captioning a logo with "step 0".
            AnimatedVisibility(
                visible = step != OnboardingStep.WELCOME,
                enter = expandVertically(Motion.sizeSpring()) +
                    fadeIn(Motion.effects(Motion.Duration.MEDIUM)),
                exit = shrinkVertically(Motion.sizeSpring()) +
                    fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        (fadeIn(Motion.effects(Motion.Duration.MEDIUM)) togetherWith
                            fadeOut(Motion.effects(Motion.Duration.SHORT)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "step-header",
                ) { current ->
                    StepHeader(step = current, status = state.statusFor(current))
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                pageSpacing = OnboardingSpacing.screen,
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
                            onUseDemo = viewModel::useDemoData,
                            onStopDemo = viewModel::stopUsingDemoData,
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

            OnboardingFooter(
                step = step,
                state = state,
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

/**
 * Back on the left, the reason you cannot leave in the middle, Next on the right.
 *
 * The bar used to stack a row of dots underneath the two buttons in the same box, which
 * is a lot of unexplained furniture for one strip; the progress rail in the header does
 * that job now.
 */
@Composable
private fun OnboardingFooter(
    step: OnboardingStep,
    state: OnboardingUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The welcome page carries its own "Get started", so its footer is empty space.
    val showButtons = step != OnboardingStep.WELCOME
    val isLast = step == OnboardingStep.NOTIFICATIONS
    val blockedReason = state.blockedReason(step)

    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = showButtons,
            enter = fadeIn(Motion.effects(Motion.Duration.SHORT)),
            exit = fadeOut(Motion.effects(Motion.Duration.SHORT)),
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OnboardingSpacing.screen, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
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

                    Text(
                        text = blockedReason.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = OnboardingSpacing.tight),
                    )

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
        // Keeps the splash's bottom edge from jumping when the buttons fade in.
        if (!showButtons) Spacer(Modifier.height(24.dp))
    }
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

@Preview(name = "Footer - blocked")
@Composable
private fun OnboardingFooterPreview() {
    KickoffTheme {
        OnboardingFooter(
            step = OnboardingStep.TEAMS,
            state = OnboardingUiState(),
            onBack = {},
            onNext = {},
        )
    }
}
