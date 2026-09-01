package com.tzvi.kickoff.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
import com.tzvi.kickoff.ui.component.LivePill
import com.tzvi.kickoff.ui.theme.KickoffTextStyles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.AppSettings
import com.tzvi.kickoff.core.model.LiveCardStyle
import com.tzvi.kickoff.ui.component.KickoffLogo
import com.tzvi.kickoff.ui.component.SectionHeader
import com.tzvi.kickoff.ui.component.SettingsRow
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme
import kotlin.math.roundToInt

// ---- 1. live card -------------------------------------------------------------------

@Composable
internal fun LiveCardSection(
    style: LiveCardStyle,
    status: LiveUpdateStatus,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectStyle: (LiveCardStyle) -> Unit,
    onOpenPromotionSettings: () -> Unit,
    onPreviewCard: () -> Unit,
    onDismissPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(
        title = "Live card",
        icon = Icons.Outlined.Bolt,
        summary = if (status.reachesAmbientSurfaces) {
            "Reaches the lock screen and the always-on display"
        } else {
            "Inside the notification shade only"
        },
        badge = style.label.uppercase(),
        highlighted = status.reachesAmbientSurfaces,
        expanded = expanded,
        onToggle = onToggle,
        help = LIVE_CARD_HELP,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = GroupPadding),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            ChoiceRow(
                options = LiveCardStyle.entries,
                selected = style,
                label = { it.label },
                onSelect = onSelectStyle,
            )

            // Only the chosen style explains itself; the other three are one tap away
            // under the "?" rather than three paragraphs you scroll past every time.
            StyleExplanation(option = style, selected = true)

            StatusLine(
                tone = if (status.supportsProgressStyle) StatusTone.OK else StatusTone.INFO,
                text = if (status.supportsProgressStyle) {
                    "The card can draw a real match clock."
                } else {
                    "No match-clock bar - that needs Android 16."
                },
            )

            StatusLine(
                tone = when {
                    status.reachesAmbientSurfaces -> StatusTone.OK
                    status.supportsPromotion -> StatusTone.ACTION
                    else -> StatusTone.INFO
                },
                text = when {
                    status.reachesAmbientSurfaces -> "Chip, lock screen and AOD are allowed."
                    status.supportsPromotion -> "Promotion is off for matchUP - shade only."
                    else -> "This device cannot promote notifications - shade only."
                },
            )

            if (status.supportsPromotion && !status.promotionAllowed) {
                if (status.canOpenPromotionSettings) {
                    FilledTonalButton(onClick = onOpenPromotionSettings) {
                        Text("Turn promoted notifications on")
                    }
                } else {
                    Text(
                        text = "No screen for that switch on this build.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(RowGap)) {
                FilledTonalButton(onClick = onPreviewCard) { Text("Preview the live card") }
                TextButton(onClick = onDismissPreview) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun StyleExplanation(option: LiveCardStyle, selected: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapes.small)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = TightGap, vertical = TightGap),
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = option.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- 2. alerts ----------------------------------------------------------------------

@Composable
internal fun AlertsSection(
    settings: AppSettings,
    access: NotificationAccess,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSetGoals: (Boolean) -> Unit,
    onSetCards: (Boolean) -> Unit,
    onSetSubstitutions: (Boolean) -> Unit,
    onSetKickoffAndFullTime: (Boolean) -> Unit,
    onSetLineups: (Boolean) -> Unit,
    onSetLeadMinutes: (Int) -> Unit,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(
        title = "Alerts",
        icon = Icons.Outlined.Schedule,
        summary = alertsSummary(settings, access),
        highlighted = !access.granted,
        expanded = expanded,
        onToggle = onToggle,
        help = ALERTS_HELP,
        modifier = modifier,
    ) {
        if (!access.granted) {
            Notice(
                text = "Notifications are switched off for matchUP, so none of these alerts " +
                    "can appear.",
                actionLabel = if (access.requestSpent) {
                    "Open notification settings"
                } else {
                    "Allow notifications"
                },
                onAction = onRequestNotifications,
                modifier = Modifier.padding(horizontal = GroupPadding, vertical = TightGap),
            )
        }

        SettingsToggleRow(
            title = "Goals",
            subtitle = "Every goal, own goal and penalty, with the new scoreline.",
            icon = Icons.Outlined.SportsSoccer,
            checked = settings.notifyGoals,
            onCheckedChange = onSetGoals,
        )
        SettingsToggleRow(
            title = "Cards",
            subtitle = "Yellows, second yellows and straight reds.",
            icon = Icons.Outlined.Style,
            checked = settings.notifyCards,
            onCheckedChange = onSetCards,
        )
        SettingsToggleRow(
            title = "Substitutions",
            subtitle = "Noisy in a busy match; off unless you want every change.",
            icon = Icons.Outlined.SwapHoriz,
            checked = settings.notifySubstitutions,
            onCheckedChange = onSetSubstitutions,
        )
        SettingsToggleRow(
            title = "Kick-off & full time",
            subtitle = "The two moments that open and close the live card.",
            icon = Icons.Outlined.Schedule,
            checked = settings.notifyKickoffAndFullTime,
            onCheckedChange = onSetKickoffAndFullTime,
        )
        SettingsToggleRow(
            title = "Line-ups",
            subtitle = "When the confirmed XI lands, usually an hour before kick-off.",
            icon = Icons.Outlined.Groups,
            checked = settings.notifyLineups,
            onCheckedChange = onSetLineups,
        )

        PreMatchLeadRow(minutes = settings.preMatchLeadMinutes, onCommit = onSetLeadMinutes)
    }
}

@Composable
private fun PreMatchLeadRow(minutes: Int, onCommit: (Int) -> Unit, modifier: Modifier = Modifier) {
    // The gesture is continuous but the write is not: committing on every frame would
    // queue a DataStore edit per pixel dragged, so the value only lands on release.
    var draft by remember(minutes) { mutableFloatStateOf(minutes.toFloat()) }

    Column(modifier = modifier.padding(horizontal = GroupPadding, vertical = TightGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Pre-match card",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${draft.roundToInt()} min",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = "How far ahead of kick-off the countdown and line-ups appear.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft.roundToInt()) },
            valueRange = PRE_MATCH_LEAD_MIN.toFloat()..PRE_MATCH_LEAD_MAX.toFloat(),
            steps = LEAD_STEPS,
        )
    }
}

// ---- 4. data source -----------------------------------------------------------------

@Composable
internal fun DataSourceSection(
    form: DataSourceForm,
    pushEnabled: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onSaveApiKey: () -> Unit,
    onBackendUrlChange: (String) -> Unit,
    onSaveBackendUrl: () -> Unit,
    onSetPushEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    SettingsCard(
        title = "Data source",
        icon = Icons.Outlined.Link,
        summary = if (form.hasSource) {
            "Fetching through ${form.activeSourceName}"
        } else {
            "Nothing configured - no fixtures can be fetched"
        },
        highlighted = !form.hasSource,
        expanded = expanded,
        onToggle = onToggle,
        help = DATA_SOURCE_HELP,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = GroupPadding),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            StatusLine(
                tone = if (form.hasSource) StatusTone.OK else StatusTone.ACTION,
                text = if (form.hasSource) {
                    "Fetching through ${form.activeSourceName}."
                } else {
                    "Nothing is configured, so no fixtures can be fetched at all."
                },
            )

            OutlinedTextField(
                value = form.apiKeyInput,
                onValueChange = onApiKeyChange,
                label = { Text("API-Football key") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                visualTransformation = if (form.apiKeyRevealed) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = onToggleApiKeyVisibility) {
                        Icon(
                            imageVector = if (form.apiKeyRevealed) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (form.apiKeyRevealed) {
                                "Hide the key"
                            } else {
                                "Show the key"
                            },
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSaveApiKey() }),
                supportingText = {
                    Text("The free tier allows 100 requests a day, which covers a few teams.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(TightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onSaveApiKey) { Text(if (form.apiKeyStored) "Update key" else "Save key") }
                TextButton(onClick = { uriHandler.openUri(API_FOOTBALL_URL) }) {
                    Text("Get a key")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedTextField(
                value = form.backendUrlInput,
                onValueChange = onBackendUrlChange,
                label = { Text("matchUP backend URL") },
                placeholder = { Text("https://your-app.onrender.com") },
                singleLine = true,
                isError = form.backendUrlError != null,
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onSaveBackendUrl() }),
                supportingText = {
                    Text(
                        form.backendUrlError
                            ?: "A backend holds the provider key and is the only source that " +
                            "can push a goal instead of waiting for the next poll. It wins " +
                            "over a pasted key whenever one is set.",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onSaveBackendUrl) {
                Text(if (form.backendUrlStored) "Update backend" else "Save backend")
            }
        }

        Spacer(Modifier.height(TightGap))

        SettingsToggleRow(
            title = "Push updates",
            subtitle = if (form.backendUrlStored) {
                "Draw a goal the moment the backend pushes it, instead of at the next poll."
            } else {
                "Only a backend can push. Without one, scores arrive on the polling schedule."
            },
            icon = Icons.Outlined.Bolt,
            checked = pushEnabled,
            onCheckedChange = onSetPushEnabled,
        )
    }
}

// ---- 5. appearance ------------------------------------------------------------------

@Composable
internal fun AppearanceSection(
    settings: AppSettings,
    dynamicColorAvailable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectTheme: (AppSettings.DarkThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(
        title = "Appearance",
        icon = Icons.Outlined.Palette,
        summary = "${settings.darkTheme.label} theme" +
            if (settings.useDynamicColor) ", wallpaper colours" else "",
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        ChoiceRow(
            options = AppSettings.DarkThemePreference.entries,
            selected = settings.darkTheme,
            label = { it.label },
            onSelect = onSelectTheme,
            modifier = Modifier.padding(horizontal = GroupPadding),
        )
        Spacer(Modifier.height(TightGap))
        SettingsToggleRow(
            title = "Dynamic colour",
            subtitle = if (dynamicColorAvailable) {
                "Take the palette from the wallpaper."
            } else {
                "Needs Android 12 or newer."
            },
            icon = Icons.Outlined.Palette,
            checked = settings.useDynamicColor,
            enabled = dynamicColorAvailable,
            onCheckedChange = onSetDynamicColor,
        )
        Text(
            text = "Dynamic colour replaces matchUP's own palette with hues derived from your " +
                "wallpaper. Club colours, the live pill and the card colours stay as they are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = GroupPadding, vertical = TightGap),
        )
    }
}

// ---- 6. about -----------------------------------------------------------------------

@Composable
internal fun AboutSection(
    version: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEraseEverything: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(
        title = "About",
        icon = Icons.Outlined.Info,
        summary = "matchUP $version",
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = GroupPadding, vertical = TightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KickoffLogo(size = LogoSize)
            Spacer(Modifier.width(RowGap))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "matchUP",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (version.isBlank()) "Version unavailable" else "Version $version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "Fixtures, scores, line-ups and crests come from API-Football.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = GroupPadding, vertical = TightGap),
        )

        EraseEverythingRow(
            onEraseEverything = onEraseEverything,
            modifier = Modifier.padding(horizontal = GroupPadding, vertical = TightGap),
        )
    }
}

// ---- shared pieces ------------------------------------------------------------------

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        modifier = modifier,
        onClick = if (enabled) {
            { onCheckedChange(!checked) }
        } else {
            null
        },
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label(option), maxLines = 1) },
            )
        }
    }
}

/** Three tones only: a fact, a fact worth acting on, and a fact that is already fine. */
private enum class StatusTone { OK, INFO, ACTION }

@Composable
private fun StatusLine(tone: StatusTone, text: String, modifier: Modifier = Modifier) {
    val colour: Color = when (tone) {
        StatusTone.OK -> KickoffTheme.accents.win
        StatusTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.ACTION -> MaterialTheme.colorScheme.error
    }
    Row(modifier = modifier.fillMaxWidth()) {
        Icon(
            imageVector = if (tone == StatusTone.OK) {
                Icons.Outlined.CheckCircle
            } else {
                Icons.Outlined.Info
            },
            contentDescription = null,
            tint = colour,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(StatusIconSize),
        )
        Spacer(Modifier.width(TightGap))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Notice(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = KickoffShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(RowGap)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = onAction,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text(actionLabel) }
        }
    }
}

private const val API_FOOTBALL_URL = "https://dashboard.api-football.com/"

private const val LEAD_STEPS = (PRE_MATCH_LEAD_MAX - PRE_MATCH_LEAD_MIN) / PRE_MATCH_LEAD_STEP - 1


/**
 * The way out. Wipes the key, the followed teams, the cache and every preference, then
 * relaunches into onboarding - and it asks first, because there is no way back from it.
 */
@Composable
private fun EraseEverythingRow(
    onEraseEverything: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TightGap)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "Start over",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "Deletes the API key, the backend address, your teams, the cached " +
                "matches and every setting, then opens the app at its first screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { confirming = true },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteForever,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Erase everything")
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Erase everything?") },
            text = {
                Text(
                    "Your key, backend address, teams, cached matches and settings will " +
                        "all be deleted, and the app will restart at onboarding. This " +
                        "cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onEraseEverything()
                    },
                ) {
                    Text("Erase and start over", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep my data") }
            },
        )
    }
}


// ---- the long explanations, one tap away ---------------------------------------------
//
// These used to be printed inline under every control. They are worth keeping - each one
// answers a question the setting itself raises - but not at the price of scrolling three
// paragraphs to reach a switch, so they live behind the "?" in each card's header.

private const val LIVE_CARD_HELP =
    "Clock draws the match as a progress bar: two halves, a mark at every goal, the ball " +
        "on the current minute.\n\n" +
        "Commentary lists the last five things that happened. Long text is the only kind " +
        "Android carries onto the always-on display in full, so pick this one if the lock " +
        "screen is where you actually read it.\n\n" +
        "Plain is the system's own template, and the only style that never asks to be " +
        "promoted.\n\n" +
        "Scoreboard draws its own card - crest, big score, crest. Android refuses to " +
        "promote custom layouts, so that one stays in the shade and on the lock screen " +
        "and never reaches the status-bar chip or the always-on display.\n\n" +
        "Preview posts a sample card through the same builder and the same eligibility " +
        "rules a real match uses, then tells you which rendering the system actually chose."

private const val DATA_SOURCE_HELP =
    "matchUP has no feed of its own.\n\n" +
        "An API-Football key works with nothing deployed, but the free tier allows 100 " +
        "requests a day - roughly one match followed loosely.\n\n" +
        "A backend holds the key itself, polls on behalf of every device, and is the only " +
        "source that can push a goal the moment it happens instead of waiting for the next " +
        "poll. It wins over a pasted key whenever one is set.\n\n" +
        "Push delivery needs Firebase configured on both the server and this build."

private const val ALERTS_HELP =
    "These decide which events make a sound. Everything else still updates the live card " +
        "silently - the card is edited in place rather than reposted, which is the whole " +
        "point of it.\n\n" +
        "The lead time is how long before kick-off the card first appears with the line-ups " +
        "and a countdown."

private const val DEMO_HELP =
    "Demo mode swaps the football source for a generated one: fifteen real clubs with " +
        "their official crests, fixtures invented around right now, and one match already " +
        "in play.\n\n" +
        "The buttons post each stage of the live card by hand. The simulator plays a whole " +
        "ninety minutes in about three, firing real goals, cards and substitutions through " +
        "the same pipeline a real match uses.\n\n" +
        "Demo outranks a key and a backend while it is on, so nothing you have saved is " +
        "lost - turning it off hands the app straight back to your own source."

// ---- closed-card summaries ----------------------------------------------------------
//
// A collapsed card is only worth collapsing if its one line answers the question that
// brought you here. These say what the section is doing right now, not what it is for.

private fun alertsSummary(settings: AppSettings, access: NotificationAccess): String {
    if (!access.granted) return "Notifications are off, so none of these fire"
    val on = buildList {
        if (settings.notifyGoals) add("goals")
        if (settings.notifyCards) add("cards")
        if (settings.notifyKickoffAndFullTime) add("kick-off")
        if (settings.notifyLineups) add("line-ups")
        if (settings.notifySubstitutions) add("subs")
    }
    if (on.isEmpty()) return "Everything silent - the card still updates in place"
    return on.joinToString(", ").replaceFirstChar { it.uppercase() } +
        " \u00b7 ${settings.preMatchLeadMinutes} min before"
}

private fun demoSummary(demo: DemoStatus): String = when {
    demo.simulating -> "Simulating \u00b7 ${demo.minute}' \u00b7 ${demo.scoreLabel}"
    demo.enabled -> "Real clubs, invented fixtures"
    else -> "Off - the app is using your own source"
}

private val GroupPadding = 16.dp
private val RowGap = 12.dp
private val TightGap = 6.dp
private val LogoSize = 44.dp
private val StatusIconSize = 16.dp

// ---- demo ----------------------------------------------------------------------------

/**
 * The demo panel.
 *
 * Everything here drives the real pipeline rather than a mock of it: the cards go through
 * the same builder and channels as a live match, and the simulator writes into the same
 * cache the rest of the app reads. What you see is what a real Saturday looks like.
 */
@Composable
internal fun DemoSection(
    demo: DemoStatus,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSetDemoMode: (Boolean) -> Unit,
    onPreMatch: () -> Unit,
    onLive: () -> Unit,
    onFullTime: () -> Unit,
    onToggleSimulation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(
        title = "Demo",
        icon = Icons.Outlined.SportsSoccer,
        summary = demoSummary(demo),
        badge = if (demo.enabled) "ON" else null,
        highlighted = demo.enabled,
        expanded = expanded,
        onToggle = onToggle,
        help = DEMO_HELP,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = GroupPadding),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use demo data", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Real clubs and crests, fixtures generated around right now. " +
                            "Overrides a key or a backend while it is on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = demo.enabled, onCheckedChange = onSetDemoMode)
            }

            AnimatedVisibility(visible = demo.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = "Post a card",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(TightGap)) {
                        FilledTonalButton(onClick = onPreMatch, modifier = Modifier.weight(1f)) {
                            Text("Pre-match")
                        }
                        FilledTonalButton(onClick = onLive, modifier = Modifier.weight(1f)) {
                            Text("Live")
                        }
                        FilledTonalButton(onClick = onFullTime, modifier = Modifier.weight(1f)) {
                            Text("Full time")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = "Play a whole match in about three minutes. The card, the " +
                            "status-bar chip and the island all update as it runs, and goals " +
                            "and the red card interrupt exactly as they would live.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    AnimatedVisibility(visible = demo.simulating) {
                        SimulationReadout(demo)
                    }

                    Button(
                        onClick = onToggleSimulation,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (demo.simulating) "Stop the match" else "Simulate a match")
                    }
                }
            }
        }
    }
}

@Composable
private fun SimulationReadout(demo: DemoStatus, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LivePill()
            Spacer(Modifier.width(10.dp))
            Text(
                text = demo.scoreLabel,
                style = KickoffTextStyles.scoreMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                // Tabular figures: the minute sits still instead of jitter-stepping as it
                // counts, which on a two-second tick is very noticeable.
                text = "${demo.minute}'",
                style = KickoffTextStyles.clock.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { (demo.minute / 90f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        demo.lastEvent?.let { headline ->
            Text(
                text = headline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
