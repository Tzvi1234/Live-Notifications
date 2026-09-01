package com.tzvi.kickoff.feature.auth

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.feature.onboarding.OnboardingSpacing
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * The first screen on a fresh install, and the only one that stands between the app and
 * the onboarding flow behind it.
 *
 * It borrows onboarding's frame on purpose - same margins, same header block, same
 * footer height - because it is the page immediately before it and the two read as one
 * arrival. What it does not borrow is the step rail: this is one question with three
 * answers, not a sequence, and a progress bar over it would be a lie.
 *
 * Every state of it ends in "Continue without an account". matchUP is a football app
 * that offers accounts, not an app you need an account for, and the way to keep that
 * true is to never draw a screen this flow cannot be left from.
 */
@Composable
fun AuthScreen(
    onDone: (needsOnboarding: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val leave = { onDone(state.needsOnboarding) }

    LaunchedEffect(state.signedIn) { if (state.signedIn) leave() }

    BackHandler(enabled = state.step != AuthStep.WELCOME) { viewModel.back() }

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
            // The welcome page owns the whole screen, logo and all, so the frame folds
            // away for it rather than captioning a mark with a heading.
            AnimatedVisibility(
                visible = state.step != AuthStep.WELCOME,
                enter = expandVertically(Motion.sizeSpring()) +
                    fadeIn(Motion.effects(Motion.Duration.MEDIUM)),
                exit = shrinkVertically(Motion.sizeSpring()) +
                    fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ) {
                AnimatedContent(
                    targetState = state.step,
                    transitionSpec = {
                        (fadeIn(Motion.effects(Motion.Duration.MEDIUM)) togetherWith
                            fadeOut(Motion.effects(Motion.Duration.SHORT)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "auth-header",
                ) { step ->
                    AuthHeader(copy = copyFor(step, state))
                }
            }

            AnimatedContent(
                targetState = state.step,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn(Motion.effects(Motion.Duration.MEDIUM)) togetherWith
                        fadeOut(Motion.effects(Motion.Duration.SHORT)))
                        .using(SizeTransform(clip = false))
                },
                label = "auth-page",
            ) { step ->
                Box(Modifier.fillMaxSize()) {
                    when (step) {
                        AuthStep.WELCOME -> WelcomePage(
                            state = state,
                            onCreateAccount = { viewModel.goTo(AuthStep.SIGN_UP) },
                            onSignIn = { viewModel.goTo(AuthStep.SIGN_IN) },
                            onSkip = { viewModel.continueWithoutAccount(leave) },
                        )

                        AuthStep.SIGN_IN -> CredentialsPage(
                            state = state,
                            newAccount = false,
                            onEmailChange = viewModel::onEmailChange,
                            onPasswordChange = viewModel::onPasswordChange,
                            onSubmit = viewModel::submit,
                        )

                        AuthStep.SIGN_UP -> CredentialsPage(
                            state = state,
                            newAccount = true,
                            onEmailChange = viewModel::onEmailChange,
                            onPasswordChange = viewModel::onPasswordChange,
                            onSubmit = viewModel::submit,
                        )

                        AuthStep.VERIFY -> VerifyPage(
                            state = state,
                            onCodeChange = viewModel::onCodeChange,
                            onSubmit = viewModel::submit,
                            onResend = viewModel::resendCode,
                        )

                        AuthStep.DETAILS -> DetailsPage(
                            state = state,
                            onFieldChange = viewModel::onFieldChange,
                            onSubmit = viewModel::submit,
                        )
                    }
                }
            }

            AuthFooter(
                state = state,
                onSubmit = viewModel::submit,
                onSwitchMode = {
                    viewModel.goTo(
                        if (state.step == AuthStep.SIGN_IN) AuthStep.SIGN_UP else AuthStep.SIGN_IN,
                    )
                },
                onSkip = { viewModel.continueWithoutAccount(leave) },
            )
        }
    }
}

/** Title and one line under it, in the same block onboarding's header uses. */
@Composable
private fun AuthHeader(copy: StepCopy, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = OnboardingSpacing.screen,
                end = OnboardingSpacing.screen,
                top = OnboardingSpacing.block,
                bottom = OnboardingSpacing.block,
            ),
        verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.tight),
    ) {
        Text(
            text = copy.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = copy.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Two lines always, so the form under it does not shift between steps.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The primary action, the way to the other mode, and the way out - always in that order
 * and always in the same place, so the button under your thumb never changes meaning.
 */
@Composable
private fun AuthFooter(
    state: AuthUiState,
    onSubmit: () -> Unit,
    onSwitchMode: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The welcome page carries its own buttons, arranged around the mark.
    if (state.step == AuthStep.WELCOME) {
        Spacer(modifier.height(OnboardingSpacing.tight))
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OnboardingSpacing.screen,
                    vertical = OnboardingSpacing.tight,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.blockedReason.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                minLines = 1,
                maxLines = 1,
            )
            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.busy) {
                    KickoffLoader(size = 18.dp, ringColor = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(primaryLabel(state.step))
                }
            }
            if (state.step == AuthStep.SIGN_IN || state.step == AuthStep.SIGN_UP) {
                TextButton(onClick = onSwitchMode, enabled = !state.busy) {
                    Text(
                        if (state.step == AuthStep.SIGN_IN) {
                            "I do not have an account yet"
                        } else {
                            "I already have an account"
                        },
                    )
                }
            }
            TextButton(onClick = onSkip, enabled = !state.busy) {
                Text("Continue without an account")
            }
        }
    }
}

private fun primaryLabel(step: AuthStep): String = when (step) {
    AuthStep.WELCOME -> "Get started"
    AuthStep.SIGN_IN -> "Sign in"
    AuthStep.SIGN_UP -> "Create account"
    AuthStep.VERIFY -> "Verify email"
    AuthStep.DETAILS -> "Finish"
}

@Preview(name = "Auth - welcome")
@Composable
private fun AuthWelcomePreview() {
    KickoffTheme {
        WelcomePage(
            state = AuthUiState(availability = AccountAvailability.AVAILABLE),
            onCreateAccount = {},
            onSignIn = {},
            onSkip = {},
        )
    }
}

@Preview(name = "Auth - accounts unavailable")
@Composable
private fun AuthUnavailablePreview() {
    KickoffTheme {
        WelcomePage(
            state = AuthUiState(availability = AccountAvailability.UNAVAILABLE),
            onCreateAccount = {},
            onSignIn = {},
            onSkip = {},
        )
    }
}
