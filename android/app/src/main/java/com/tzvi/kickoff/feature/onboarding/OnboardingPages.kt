package com.tzvi.kickoff.feature.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.component.AnimatedKickoffLogo
import com.tzvi.kickoff.ui.component.MetaChip
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

private const val API_FOOTBALL_DASHBOARD = "https://dashboard.api-football.com"

@Composable
internal fun WelcomePage(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = OnboardingSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedKickoffLogo(size = 120.dp)
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Kickoff",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Live match cards on your lock screen, and the rest of your day in the " +
                "same place.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FeatureLine(
                icon = Icons.Outlined.Notifications,
                text = "A score that updates in place, not a new alert every goal",
            )
            FeatureLine(
                icon = Icons.Outlined.SportsSoccer,
                text = "Lineups and the countdown an hour before kick-off",
            )
            FeatureLine(
                icon = Icons.Outlined.CalendarMonth,
                text = "Your own calendar beside the fixtures, read-only",
            )
        }
        Spacer(Modifier.height(40.dp))
        Button(onClick = onGetStarted, modifier = Modifier.fillMaxWidth()) {
            Text("Get started")
        }
    }
}

@Composable
internal fun ConnectPage(
    state: OnboardingUiState,
    onApiKeyChange: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    onBackendUrlChange: (String) -> Unit,
    onSaveBackendUrl: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.block),
    ) {
        PageHeading(
            title = "Where do the scores come from?",
            body = "Kickoff has no feed of its own. Pick one of these two - Settings can " +
                "change it later.",
        )

        SourceCard(
            icon = Icons.Outlined.Key,
            title = "An API-Football key",
            status = when {
                !state.apiKeySaved -> null
                state.source == ConfiguredSource.API_FOOTBALL -> "IN USE"
                else -> "SAVED"
            },
            body = "Paste the key from your API-Football dashboard. The free tier allows " +
                "100 requests a day, which is fine for a handful of teams and will not " +
                "survive constant refreshing.",
        ) {
            OutlinedTextField(
                value = state.apiKeyInput,
                onValueChange = onApiKeyChange,
                label = { Text("API key") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSaveApiKey() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(OnboardingSpacing.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onSaveApiKey, enabled = state.apiKeyInput.isNotBlank()) {
                    Text("Save key")
                }
                TextButton(onClick = { uriHandler.openUri(API_FOOTBALL_DASHBOARD) }) {
                    Text("Where do I get one?")
                }
            }
            if (state.apiKeySaved && state.backendSaved) {
                Text(
                    text = "A backend is set too, and it takes priority while it is there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SourceCard(
            icon = Icons.Outlined.Link,
            title = "A Kickoff backend",
            status = if (state.backendSaved) "IN USE" else null,
            body = "Point at your own deployment. It holds the provider key, polls matches " +
                "for you, and is the only option that can push a goal to the phone instead " +
                "of waiting for the next poll.",
        ) {
            OutlinedTextField(
                value = state.backendUrlInput,
                onValueChange = onBackendUrlChange,
                label = { Text("Backend URL") },
                placeholder = { Text("https://your-app.onrender.com") },
                singleLine = true,
                isError = state.backendUrlError != null,
                supportingText = {
                    Text(
                        state.backendUrlError
                            ?: "A bare host works too - https:// is added for you.",
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSaveBackendUrl() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSaveBackendUrl,
                enabled = state.backendUrlInput.isNotBlank(),
            ) {
                Text("Use this backend")
            }
        }

        AnimatedVisibility(visible = !state.hasSource, enter = fadeIn(), exit = fadeOut()) {
            Card(
                shape = KickoffShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(OnboardingSpacing.card),
                    verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.tight),
                ) {
                    Text(
                        text = "You can skip this, but the next step has nothing to list " +
                            "until one of the two is set.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = onSkip) { Text("Skip for now") }
                }
            }
        }
        Spacer(Modifier.height(OnboardingSpacing.block))
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    status: String?,
    body: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(OnboardingSpacing.card),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (status != null) MetaChip(text = status)
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
internal fun NotificationsPage(
    state: OnboardingUiState,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.block),
    ) {
        PageHeading(
            title = "The live card",
            body = "An hour before kick-off Kickoff posts one notification per match and " +
                "keeps editing it: the countdown becomes the lineups, then the scoreline, " +
                "then the full-time summary. Only goals, red cards and full time make a " +
                "sound - everything else updates silently.",
        )

        Card(
            shape = KickoffShapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (state.notificationsGranted) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(OnboardingSpacing.card),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (state.notificationsGranted) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Outlined.NotificationsActive
                        },
                        contentDescription = null,
                        tint = if (state.notificationsGranted) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (state.notificationsGranted) {
                            "Notifications allowed"
                        } else {
                            "Notifications are off"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.notificationsGranted) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (!state.notificationsGranted) {
                    Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow notifications")
                    }
                    if (state.notificationsDenied) {
                        Text(
                            text = "Android only shows that dialog once. If it did not " +
                                "appear, the switch is in the system settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onOpenSystemSettings) {
                            Text("Open notification settings")
                        }
                    }
                }
            }
        }

        Text(
            text = "You can finish without this. Fixtures, lineups and the calendar all " +
                "still work inside the app - only the live card needs the permission.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val saveError = state.saveError
        if (saveError != null) {
            Text(
                text = saveError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(OnboardingSpacing.block))
    }
}

@Preview(name = "Welcome")
@Composable
private fun WelcomePagePreview() {
    KickoffTheme { WelcomePage(onGetStarted = {}) }
}

@Preview(name = "Connect")
@Composable
private fun ConnectPagePreview() {
    KickoffTheme {
        ConnectPage(
            state = OnboardingUiState(backendUrlInput = "your-app.onrender"),
            onApiKeyChange = {},
            onSaveApiKey = {},
            onBackendUrlChange = {},
            onSaveBackendUrl = {},
            onSkip = {},
        )
    }
}

@Preview(name = "Notifications - not granted")
@Composable
private fun NotificationsPagePreview() {
    KickoffTheme {
        NotificationsPage(
            state = OnboardingUiState(notificationsDenied = true),
            onRequestPermission = {},
            onOpenSystemSettings = {},
        )
    }
}

@Preview(name = "Notifications - granted")
@Composable
private fun NotificationsGrantedPreview() {
    KickoffTheme {
        NotificationsPage(
            state = OnboardingUiState(notificationsGranted = true),
            onRequestPermission = {},
            onOpenSystemSettings = {},
        )
    }
}
