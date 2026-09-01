package com.tzvi.kickoff.feature.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PlayCircleOutline
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.component.AnimatedKickoffLogo
import androidx.compose.material.icons.outlined.ErrorOutline
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.ui.component.MetaChip
import com.tzvi.kickoff.ui.component.TeamCrest
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

private const val API_FOOTBALL_DASHBOARD = "https://dashboard.api-football.com"

@Composable
internal fun WelcomePage(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // SpaceBetween-by-hand: the logo block breathes in the upper half, the promises sit
    // in the lower, and the button holds the bottom edge - the page owns its whole height
    // instead of clustering in the middle with dead air above and below.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = OnboardingSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1.1f))
        AnimatedKickoffLogo(size = 148.dp)
        Spacer(Modifier.height(30.dp))
        Text(
            text = "matchUP",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Live match cards on your lock screen, and every fixture you follow " +
                "in the same place.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FeatureLine(
                icon = Icons.Outlined.Notifications,
                text = "A score that updates in place, not a new alert every goal",
            )
            FeatureLine(
                icon = Icons.Outlined.SportsSoccer,
                text = "Line-ups and the countdown an hour before kick-off",
            )
            FeatureLine(
                icon = Icons.Outlined.Casino,
                text = "A prediction game to play against everyone in your group",
            )
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("Get started", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Three tiles and nothing else.
 *
 * This step used to carry all three explanations, both text fields and every button at
 * once, which is most of what made the flow feel like a wall. The choice is one question;
 * whatever the choice needs gets its own page after it.
 */
@Composable
internal fun SourcePage(
    state: OnboardingUiState,
    entrance: EntranceScope,
    onChoose: (ConfiguredSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        entrance.Block(0) {
            SourceTile(
                icon = Icons.Outlined.PlayCircleOutline,
                title = "Demo data",
                body = "Real clubs and crests. Nothing to sign up for.",
                badge = "NO SIGN-UP",
                selected = state.chosenSource == ConfiguredSource.DEMO,
                onClick = { onChoose(ConfiguredSource.DEMO) },
            )
        }
        entrance.Block(1) {
            SourceTile(
                icon = Icons.Outlined.Key,
                title = "An API-Football key",
                body = "Your own key. 100 requests a day on the free tier.",
                badge = "FREE TIER",
                selected = state.chosenSource == ConfiguredSource.API_FOOTBALL,
                onClick = { onChoose(ConfiguredSource.API_FOOTBALL) },
            )
        }
        entrance.Block(2) {
            SourceTile(
                icon = Icons.Outlined.Link,
                title = "Your own backend",
                body = "The only one that can push a goal the moment it happens.",
                badge = "BEST FOR LIVE",
                selected = state.chosenSource == ConfiguredSource.BACKEND,
                onClick = { onChoose(ConfiguredSource.BACKEND) },
            )
        }
        entrance.Block(3) {
            Text(
                text = "You can change this later in Settings, and switching sources " +
                    "never loses the teams you follow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(OnboardingSpacing.block))
    }
}

@Composable
private fun SourceTile(
    icon: ImageVector,
    title: String,
    body: String,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = Motion.effects(Motion.Duration.SHORT),
        label = "source-tile-container",
    )
    // The chosen tile lifts off the page; the rest stay flat.
    val elevation by animateDpAsState(
        targetValue = if (selected) 6.dp else 0.dp,
        animationSpec = Motion.dpSpring(),
        label = "source-tile-elevation",
    )

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.medium,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    MetaChip(text = badge)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Whatever the choice on the previous page actually needs - and nothing else. */
@Composable
internal fun SetupPage(
    state: OnboardingUiState,
    entrance: EntranceScope,
    onApiKeyChange: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    onBackendUrlChange: (String) -> Unit,
    onSaveBackendUrl: () -> Unit,
    onBackToChoice: () -> Unit,
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
        when (state.chosenSource) {
            ConfiguredSource.DEMO -> {
                entrance.Block(0) {
                    ReadyNote(
                        title = "Ready to go",
                        body = "Fifteen clubs with their official crests, a match already " +
                            "in play, and one you can watch run its whole ninety minutes " +
                            "in about three.",
                    )
                }
                entrance.Block(1) {
                    Text(
                        text = "Settings has the buttons that fire each notification by " +
                            "hand, and the simulator that plays a match out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ConfiguredSource.API_FOOTBALL -> {
                entrance.Block(0) {
                    OutlinedTextField(
                        value = state.apiKeyInput,
                        onValueChange = onApiKeyChange,
                        label = { Text("API key") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onSaveApiKey() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                entrance.Block(1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(OnboardingSpacing.tight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onSaveApiKey,
                            enabled = state.apiKeyInput.isNotBlank() && !state.checkingSource,
                        ) {
                            if (state.checkingSource) {
                                KickoffLoader(size = 18.dp)
                            } else {
                                Text("Check and save")
                            }
                        }
                        TextButton(onClick = { uriHandler.openUri(API_FOOTBALL_DASHBOARD) }) {
                            Text("Where do I get one?")
                        }
                    }
                }
                entrance.Block(2) {
                    SourceVerdict(
                        state = state,
                        okTitle = "Key accepted",
                        hint = "Take it from dashboard.api-football.com, not from " +
                            "RapidAPI - a RapidAPI key will not work here.",
                    )
                }
            }

            ConfiguredSource.BACKEND -> {
                entrance.Block(0) {
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
                }
                entrance.Block(1) {
                    Button(
                        onClick = onSaveBackendUrl,
                        enabled = state.backendUrlInput.isNotBlank() && !state.checkingSource,
                    ) {
                        if (state.checkingSource) {
                            KickoffLoader(size = 18.dp)
                        } else {
                            Text("Check and use")
                        }
                    }
                }
                entrance.Block(2) {
                    SourceVerdict(
                        state = state,
                        okTitle = "Backend reached",
                        hint = "The address Render shows at the top of your service page.",
                    )
                }
            }

            else -> entrance.Block(0) {
                Column(verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.tight)) {
                    Text(
                        text = "Nothing was picked on the last step, so there is nothing " +
                            "to set up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onBackToChoice) { Text("Go back and choose") }
                }
            }
        }
        Spacer(Modifier.height(OnboardingSpacing.block))
    }
}

@Composable
internal fun AlertsPage(
    state: OnboardingUiState,
    entrance: EntranceScope,
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
        entrance.Block(0) {
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
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
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
        }

        entrance.Block(1) {
            Text(
                text = "Only goals, red cards and full time make a sound. Everything else " +
                    "edits the card that is already there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        entrance.Block(2) {
            Text(
                text = "You can finish without this. Fixtures, line-ups and predictions " +
                    "all still work - only the live card needs the permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(OnboardingSpacing.block))
    }
}

/** The closing beat: what you picked, and what happens next. */
@Composable
internal fun ReadyPage(
    state: OnboardingUiState,
    entrance: EntranceScope,
    modifier: Modifier = Modifier,
) {
    val teams = state.selected.values.toList()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.block),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        entrance.Block(0) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedKickoffLogo(size = 84.dp)
            }
        }

        entrance.Block(1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                teams.take(6).forEach { option ->
                    TeamCrest(team = option.team, size = 38.dp)
                }
                if (teams.size > 6) {
                    Text(
                        text = "+${teams.size - 6}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        entrance.Block(2) {
            SummaryLine(
                index = "1",
                text = "An hour before kick-off a card appears with the line-ups and a " +
                    "countdown.",
            )
        }
        entrance.Block(3) {
            SummaryLine(
                index = "2",
                text = "At kick-off the same card becomes the scoreline and keeps editing " +
                    "itself - it never posts a second notification.",
            )
        }
        entrance.Block(4) {
            SummaryLine(
                index = "3",
                text = "At full time it settles into the result, then clears itself.",
            )
        }

        entrance.Block(5) {
            val error = state.saveError
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(OnboardingSpacing.block))
    }
}

/** The three things that will happen, numbered because they genuinely are a sequence. */
@Composable
private fun SummaryLine(index: String, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = index,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the probe found, in the place the value was typed.
 *
 * A failure here is the whole point of probing: it names the address or the key that did
 * not work, rather than letting the flow continue and blame competitions two steps later.
 */
@Composable
private fun SourceVerdict(
    state: OnboardingUiState,
    okTitle: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val message = state.sourceCheck
    when {
        message != null && state.sourceCheckFailed -> Card(
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
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        message != null -> ReadyNote(title = okTitle, body = message)

        else -> Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

@Composable
private fun ReadyNote(title: String, body: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(OnboardingSpacing.card),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Preview(name = "Welcome")
@Composable
private fun WelcomePagePreview() {
    KickoffTheme { WelcomePage(onGetStarted = {}) }
}

@Preview(name = "Source")
@Composable
private fun SourcePagePreview() {
    KickoffTheme {
        SourcePage(
            state = OnboardingUiState(chosenSource = ConfiguredSource.DEMO),
            entrance = EntranceScope(dealt = true),
            onChoose = {},
        )
    }
}

@Preview(name = "Setup - key")
@Composable
private fun SetupPagePreview() {
    KickoffTheme {
        SetupPage(
            state = OnboardingUiState(chosenSource = ConfiguredSource.API_FOOTBALL),
            entrance = EntranceScope(dealt = true),
            onApiKeyChange = {},
            onSaveApiKey = {},
            onBackendUrlChange = {},
            onSaveBackendUrl = {},
            onBackToChoice = {},
        )
    }
}

@Preview(name = "Alerts")
@Composable
private fun AlertsPagePreview() {
    KickoffTheme {
        AlertsPage(
            state = OnboardingUiState(notificationsDenied = true),
            entrance = EntranceScope(dealt = true),
            onRequestPermission = {},
            onOpenSystemSettings = {},
        )
    }
}
