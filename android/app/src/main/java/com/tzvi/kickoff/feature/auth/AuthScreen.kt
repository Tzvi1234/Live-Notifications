package com.tzvi.kickoff.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * The first screen on a fresh install, and the only one that stands between the app and
 * the onboarding flow behind it.
 *
 * Each step owns its whole page - mark, heading, form and buttons in one scrolling
 * column - rather than being poured into a fixed header/body/footer frame. The frame was
 * the problem: it gave the form only what height was left after the header and footer
 * had taken theirs, so a keyboard turned two text fields into a squeezed strip with the
 * heading sitting on top of them.
 *
 * Every state of it still ends in "Continue without an account". matchUP is a football
 * app that offers accounts, not an app you need an account for, and the way to keep that
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
    val skip = { viewModel.continueWithoutAccount(leave) }

    LaunchedEffect(state.signedIn) { if (state.signedIn) leave() }

    BackHandler(enabled = state.step != AuthStep.WELCOME) { viewModel.back() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .brandWash(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding(),
            ) {
                AnimatedContent(
                    targetState = state.step,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        // Forward is deeper into the flow, so it comes in from the end
                        // edge; going back reverses, which is the only cue that says
                        // "you have not lost what you typed".
                        val forward = targetState.ordinal >= initialState.ordinal
                        val arrive = { w: Int -> if (forward) w / 4 else -w / 4 }
                        val depart = { w: Int -> if (forward) -w / 6 else w / 6 }
                        (
                            slideInHorizontally(Motion.offsetSpring(), arrive) +
                                fadeIn(Motion.effects(Motion.Duration.MEDIUM))
                            ) togetherWith (
                            slideOutHorizontally(Motion.offsetSpring(), depart) +
                                fadeOut(Motion.effects(Motion.Duration.SHORT))
                            ) using SizeTransform(clip = false)
                    },
                    label = "auth-page",
                ) { step ->
                    when (step) {
                        AuthStep.WELCOME -> WelcomePage(
                            state = state,
                            onCreateAccount = { viewModel.goTo(AuthStep.SIGN_UP) },
                            onSignIn = { viewModel.goTo(AuthStep.SIGN_IN) },
                            onGoogle = viewModel::continueWithGoogle,
                            onSkip = skip,
                        )

                        AuthStep.SIGN_IN, AuthStep.SIGN_UP -> CredentialsPage(
                            state = state,
                            newAccount = step == AuthStep.SIGN_UP,
                            onEmailChange = viewModel::onEmailChange,
                            onPasswordChange = viewModel::onPasswordChange,
                            onSubmit = viewModel::submit,
                            onGoogle = viewModel::continueWithGoogle,
                            onBack = viewModel::back,
                            onSwitchMode = {
                                viewModel.goTo(
                                    if (step == AuthStep.SIGN_IN) {
                                        AuthStep.SIGN_UP
                                    } else {
                                        AuthStep.SIGN_IN
                                    },
                                )
                            },
                            onSkip = skip,
                        )

                        AuthStep.VERIFY -> VerifyPage(
                            state = state,
                            onCodeChange = viewModel::onCodeChange,
                            onSubmit = viewModel::submit,
                            onResend = viewModel::resendCode,
                            onBack = viewModel::back,
                            onSkip = skip,
                        )

                        AuthStep.DETAILS -> DetailsPage(
                            state = state,
                            onFieldChange = viewModel::onFieldChange,
                            onSubmit = viewModel::submit,
                            onBack = viewModel::back,
                            onSkip = skip,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A pitch-green glow behind the mark, fading out by a third of the way down.
 *
 * It is doing one job: giving the logo something to sit in, so the top of the page reads
 * as a place rather than as a gap above a form. Kept to a tenth of the primary so it
 * survives both themes without ever competing with the text over it.
 */
@Composable
private fun Modifier.brandWash(): Modifier {
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = GLOW_ALPHA)
    return drawWithCache {
        val brush = Brush.radialGradient(
            colors = listOf(glow, Color.Transparent),
            center = Offset(size.width / 2f, size.height * GLOW_CENTRE),
            radius = size.width * GLOW_SPREAD,
        )
        onDrawBehind { drawRect(brush) }
    }
}

private const val GLOW_ALPHA = 0.10f

/** Level with the mark, a fifth of the way down. */
private const val GLOW_CENTRE = 0.20f
private const val GLOW_SPREAD = 1.1f

@Preview(name = "Auth - welcome")
@Composable
private fun AuthWelcomePreview() {
    KickoffTheme {
        WelcomePage(
            state = AuthUiState(availability = AccountAvailability.AVAILABLE),
            onCreateAccount = {},
            onSignIn = {},
            onGoogle = {},
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
            onGoogle = {},
            onSkip = {},
        )
    }
}
