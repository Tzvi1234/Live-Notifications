package com.tzvi.kickoff.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Scoreboard
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.R
import com.tzvi.kickoff.data.auth.missingFieldLabel
import com.tzvi.kickoff.feature.onboarding.OnboardingSpacing
import com.tzvi.kickoff.ui.component.AnimatedKickoffLogo
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.ui.component.KickoffLogo
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

/** Title and one line, resolved per step because the sign-in and sign-up copy differ. */
internal data class StepCopy(val title: String, val subtitle: String)

internal fun copyFor(step: AuthStep, state: AuthUiState): StepCopy = when (step) {
    AuthStep.WELCOME -> StepCopy("matchUP", "")

    AuthStep.SIGN_IN -> StepCopy(
        title = "Welcome back",
        subtitle = "Pick up your teams and your predictions where you left them.",
    )

    AuthStep.SIGN_UP -> StepCopy(
        title = "Make an account",
        subtitle = "It carries your predictions and your teams between devices. " +
            "Nothing else.",
    )

    AuthStep.VERIFY -> StepCopy(
        title = "Check your email",
        subtitle = if (state.email.isBlank()) {
            "We sent you a six-digit code. Type it in below."
        } else {
            "We sent a six-digit code to ${state.email}."
        },
    )

    AuthStep.DETAILS -> StepCopy(
        title = "One or two more things",
        subtitle = "This Clerk instance asks for these before it will finish the sign-up.",
    )

    // ProfilePage draws its own heading around the picture it is asking for.
    AuthStep.PROFILE -> StepCopy(title = "Say who you are", subtitle = "")
}

// ---- the shared frame ----------------------------------------------------------

/**
 * Every page that asks for something, laid out the same way.
 *
 * The whole page is one scrolling column - mark, heading, Google, fields, buttons and
 * all - rather than a fixed header over a scrolling middle over a pinned footer. That
 * earlier arrangement is what squeezed the form flat: with a keyboard up, the middle was
 * the only part allowed to give, so two text fields ended up sharing whatever height was
 * left over, and the top one was clipped by the header sitting on it. Letting the entire
 * page scroll means the keyboard just pushes it, and Compose brings the focused field
 * into view by itself.
 */
@Composable
private fun AuthFormPage(
    copy: StepCopy,
    state: AuthUiState,
    primaryLabel: String,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onGoogle: () -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
    fields: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, enabled = !state.working) {
                Icon(
                    // Auto-mirrored: Hebrew reads right to left, and a back arrow that
                    // does not turn with the language points at the wrong edge.
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        KickoffLogo(size = 68.dp)
        Spacer(Modifier.height(22.dp))

        Text(
            text = copy.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = copy.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (state.googleOffered) {
            Spacer(Modifier.height(30.dp))
            GoogleButton(busy = state.googleBusy, enabled = !state.working, onClick = onGoogle)
            Spacer(Modifier.height(22.dp))
            OrDivider()
        }

        Spacer(Modifier.height(26.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = fields,
        )

        AnimatedVisibility(visible = state.error != null || state.notice != null) {
            Column {
                Spacer(Modifier.height(18.dp))
                AuthMessage(error = state.error, notice = state.notice)
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            shape = KickoffShapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .height(BUTTON_HEIGHT),
        ) {
            if (state.busy) {
                KickoffLoader(size = 20.dp, ringColor = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(primaryLabel, style = MaterialTheme.typography.titleMedium)
            }
        }

        // Reserved whether or not there is anything to say, so nothing under it hops
        // about as the form goes from invalid to valid and back.
        Text(
            text = state.blockedReason.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = 1,
            maxLines = 2,
            modifier = Modifier.padding(top = 10.dp),
        )

        Spacer(Modifier.height(2.dp))
        footer()
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Google's button, to Google's rules: their mark, unrecoated, on a plain surface, with
 * the wording they publish. Nothing here is tinted to match the app.
 */
@Composable
private fun GoogleButton(
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = KickoffShapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(BUTTON_HEIGHT),
    ) {
        if (busy) {
            KickoffLoader(size = 20.dp)
        } else {
            Image(
                painter = painterResource(R.drawable.ic_google_g),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text("Continue with Google", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun OrDivider(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = "or with an email address",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * A filled field with a hairline round it.
 *
 * Explicit containers rather than the M3 defaults, because a field that is transparent
 * on this background is a floating label over nothing - and the platform's own autofill
 * wash, which the app theme now clears, was the other half of that same confusion.
 */
@Composable
private fun authFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

// ---- the pages -----------------------------------------------------------------

/**
 * The mark, what an account is for, and every way out.
 *
 * Laid out like onboarding's welcome page - logo breathing in the upper half, promises
 * in the lower, buttons holding the bottom edge - because it is the page immediately
 * before it and the pair should read as one arrival rather than two front doors.
 */
@Composable
internal fun WelcomePage(
    state: AuthUiState,
    onCreateAccount: () -> Unit,
    onSignIn: () -> Unit,
    onGoogle: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        AnimatedKickoffLogo(size = 132.dp)
        Spacer(Modifier.height(26.dp))
        Text(
            text = "matchUP",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "An account is optional. It is what carries your picks and your teams " +
                "from one phone to the next.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(34.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountBenefit(
                icon = Icons.Outlined.Scoreboard,
                text = "Call the score before kick-off and keep the record",
            )
            AccountBenefit(
                icon = Icons.Outlined.Groups,
                text = "Your teams and leagues, the same on every device",
            )
            AccountBenefit(
                icon = Icons.Outlined.Sync,
                text = "Nothing here needs one - scores and alerts work signed out",
            )
        }
        Spacer(Modifier.height(34.dp))

        when (state.availability) {
            AccountAvailability.RESOLVING -> {
                KickoffLoader(size = 32.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Checking whether accounts are available…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AccountAvailability.UNAVAILABLE -> Text(
                text = "Accounts are not configured for this build, so matchUP will run " +
                    "without one.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AccountAvailability.AVAILABLE -> {
                GoogleButton(
                    busy = state.googleBusy,
                    enabled = !state.working,
                    onClick = onGoogle,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onCreateAccount,
                    enabled = !state.working,
                    shape = KickoffShapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BUTTON_HEIGHT),
                ) {
                    Text("Sign up with email", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onSignIn, enabled = !state.working) {
                    Text("I already have an account")
                }
            }
        }

        AnimatedVisibility(
            visible = state.error != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                AuthMessage(error = state.error, notice = null)
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onSkip, enabled = !state.working) {
            Text("Continue without an account")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AccountBenefit(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(KickoffShapeTokens.crest)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One form for both modes: the fields are identical, only the copy around them moves. */
@Composable
internal fun CredentialsPage(
    state: AuthUiState,
    newAccount: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onGoogle: () -> Unit,
    onBack: () -> Unit,
    onSwitchMode: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var revealed by remember { mutableStateOf(false) }

    AuthFormPage(
        copy = copyFor(if (newAccount) AuthStep.SIGN_UP else AuthStep.SIGN_IN, state),
        state = state,
        primaryLabel = if (newAccount) "Create account" else "Sign in",
        onBack = onBack,
        onSubmit = onSubmit,
        onGoogle = onGoogle,
        modifier = modifier,
        footer = {
            TextButton(onClick = onSwitchMode, enabled = !state.working) {
                Text(
                    if (newAccount) {
                        "I already have an account"
                    } else {
                        "I do not have an account yet"
                    },
                )
            }
            TextButton(onClick = onSkip, enabled = !state.working) {
                Text("Continue without an account")
            }
        },
    ) {
        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = { Text("Email address") },
            singleLine = true,
            enabled = !state.working,
            shape = KickoffShapes.medium,
            colors = authFieldColors(),
            leadingIcon = {
                Icon(Icons.Outlined.AlternateEmail, contentDescription = null)
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            enabled = !state.working,
            shape = KickoffShapes.medium,
            colors = authFieldColors(),
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            visualTransformation = if (revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            supportingText = if (newAccount) {
                { Text("At least $MIN_PASSWORD_LENGTH characters.") }
            } else {
                null
            },
            trailingIcon = {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = if (revealed) {
                            "Hide the password"
                        } else {
                            "Show the password"
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun VerifyPage(
    state: AuthUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthFormPage(
        copy = copyFor(AuthStep.VERIFY, state),
        state = state,
        primaryLabel = "Verify email",
        onBack = onBack,
        onSubmit = onSubmit,
        onGoogle = {},
        modifier = modifier,
        footer = {
            TextButton(onClick = onResend, enabled = !state.working) {
                Text("Send a new code")
            }
            TextButton(onClick = onSkip, enabled = !state.working) {
                Text("Continue without an account")
            }
        },
    ) {
        OtpField(
            value = state.code,
            onValueChange = onCodeChange,
            onSubmit = onSubmit,
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.MarkEmailRead,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Codes can take a minute, and they land in spam more often than " +
                    "they should.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Exactly the fields Clerk named, in the order it named them.
 *
 * Which attributes an instance requires is a dashboard setting, so this page is built
 * from `missingFields` at run time rather than from a form somebody guessed at.
 */
@Composable
internal fun DetailsPage(
    state: AuthUiState,
    onFieldChange: (String, String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthFormPage(
        copy = copyFor(AuthStep.DETAILS, state),
        state = state,
        primaryLabel = "Finish",
        onBack = onBack,
        onSubmit = onSubmit,
        onGoogle = {},
        modifier = modifier,
        footer = {
            TextButton(onClick = onSkip, enabled = !state.working) {
                Text("Continue without an account")
            }
        },
    ) {
        state.missingFields.forEachIndexed { index, field ->
            val last = index == state.missingFields.lastIndex
            OutlinedTextField(
                value = state.fieldValues[field].orEmpty(),
                onValueChange = { onFieldChange(field, it) },
                label = { Text(missingFieldLabel(field)) },
                singleLine = true,
                enabled = !state.working,
                shape = KickoffShapes.medium,
                colors = authFieldColors(),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (last) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * What Clerk said, verbatim.
 *
 * An error card here carries the API's own sentence - "that email address is taken
 * already" - rather than a house phrase, because the house phrase is never the one that
 * tells you what to do next.
 */
@Composable
internal fun AuthMessage(
    error: String?,
    notice: String?,
    modifier: Modifier = Modifier,
) {
    when {
        error != null -> Card(
            modifier = modifier.fillMaxWidth(),
            shape = KickoffShapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(OnboardingSpacing.card),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        notice != null -> Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** Every button on this flow stands the same height, so the page has a rhythm. */
private val BUTTON_HEIGHT = 56.dp

@Preview(name = "Auth - sign up")
@Composable
private fun CredentialsPagePreview() {
    KickoffTheme {
        CredentialsPage(
            state = AuthUiState(
                step = AuthStep.SIGN_UP,
                availability = AccountAvailability.AVAILABLE,
                email = "tzvi@example.com",
                password = "hunter22",
                error = "That email address is taken already.",
            ),
            newAccount = true,
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onGoogle = {},
            onBack = {},
            onSwitchMode = {},
            onSkip = {},
        )
    }
}

@Preview(name = "Auth - verify")
@Composable
private fun VerifyPagePreview() {
    KickoffTheme {
        VerifyPage(
            state = AuthUiState(step = AuthStep.VERIFY, email = "tzvi@example.com"),
            onCodeChange = {},
            onSubmit = {},
            onResend = {},
            onBack = {},
            onSkip = {},
        )
    }
}

@Preview(name = "Auth - missing fields")
@Composable
private fun DetailsPagePreview() {
    KickoffTheme {
        DetailsPage(
            state = AuthUiState(
                step = AuthStep.DETAILS,
                missingFields = listOf("first_name", "last_name"),
            ),
            onFieldChange = { _, _ -> },
            onSubmit = {},
            onBack = {},
            onSkip = {},
        )
    }
}
