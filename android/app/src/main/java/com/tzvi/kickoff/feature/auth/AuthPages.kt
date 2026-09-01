package com.tzvi.kickoff.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Scoreboard
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.data.auth.missingFieldLabel
import com.tzvi.kickoff.feature.onboarding.OnboardingSpacing
import com.tzvi.kickoff.ui.component.AnimatedKickoffLogo
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

/** Title and one line, resolved per step because the sign-in and sign-up copy differ. */
internal data class StepCopy(val title: String, val subtitle: String)

internal fun copyFor(step: AuthStep, state: AuthUiState): StepCopy = when (step) {
    AuthStep.WELCOME -> StepCopy("matchUP", "")

    AuthStep.SIGN_IN -> StepCopy(
        title = "Welcome back",
        subtitle = "The email address and password you signed up with.",
    )

    AuthStep.SIGN_UP -> StepCopy(
        title = "Make an account",
        subtitle = "It carries your predictions and your teams between devices. Nothing else.",
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
}

/**
 * The mark, what an account is for, and three ways out.
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
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = OnboardingSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1.1f))
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
        Spacer(Modifier.weight(1f))

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
        Spacer(Modifier.weight(1f))

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
                Button(
                    onClick = onCreateAccount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text("Create an account", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onSignIn) { Text("I already have an account") }
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onSkip) { Text("Continue without an account") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AccountBenefit(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
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
    modifier: Modifier = Modifier,
) {
    var revealed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.block),
    ) {
        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = { Text("Email address") },
            singleLine = true,
            enabled = !state.busy,
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
            enabled = !state.busy,
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
                TextButton(onClick = { revealed = !revealed }) {
                    Text(if (revealed) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        AuthMessage(error = state.error, notice = state.notice)
    }
}

@Composable
internal fun VerifyPage(
    state: AuthUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.block),
    ) {
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            label = { Text("Six-digit code") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
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

        TextButton(onClick = onResend, enabled = !state.busy) { Text("Send a new code") }

        AuthMessage(error = state.error, notice = state.notice)
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.block),
    ) {
        state.missingFields.forEachIndexed { index, field ->
            val last = index == state.missingFields.lastIndex
            OutlinedTextField(
                value = state.fieldValues[field].orEmpty(),
                onValueChange = { onFieldChange(field, it) },
                label = { Text(missingFieldLabel(field)) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(
                    imeAction = if (last) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AuthMessage(error = state.error, notice = state.notice)
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
            modifier = modifier,
        )
    }
}

@Preview(name = "Auth - sign up")
@Composable
private fun CredentialsPagePreview() {
    KickoffTheme {
        CredentialsPage(
            state = AuthUiState(
                step = AuthStep.SIGN_UP,
                email = "tzvi@example.com",
                password = "hunter22",
                error = "That email address is taken already.",
            ),
            newAccount = true,
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
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
        )
    }
}
