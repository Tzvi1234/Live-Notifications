package com.tzvi.kickoff.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    onSelectStyle: (LiveCardStyle) -> Unit,
    onOpenPromotionSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(title = "Live card", modifier = modifier) {
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

            Column(verticalArrangement = Arrangement.spacedBy(TightGap)) {
                LiveCardStyle.entries.forEach { option ->
                    StyleExplanation(option = option, selected = option == style)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "On this device",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StatusLine(
                tone = if (status.supportsProgressStyle) StatusTone.OK else StatusTone.INFO,
                text = if (status.supportsProgressStyle) {
                    "ProgressStyle is supported, so the card can draw a real match clock."
                } else {
                    "ProgressStyle needs Android 16. Below that the card falls back to a " +
                        "plain layout with the score in the text."
                },
            )

            StatusLine(
                tone = when {
                    status.reachesAmbientSurfaces -> StatusTone.OK
                    status.supportsPromotion -> StatusTone.ACTION
                    else -> StatusTone.INFO
                },
                text = when {
                    status.reachesAmbientSurfaces ->
                        "Promoted notifications are allowed, so a live card can reach the " +
                            "status-bar chip, the lock screen and the always-on display."
                    status.supportsPromotion ->
                        "Promoted notifications are turned off for Kickoff. Live cards stay " +
                            "inside the shade: no chip, no lock screen, no always-on display."
                    else ->
                        "This device cannot promote notifications - that arrived in Android 16 " +
                            "QPR1. Live cards will appear in the shade only."
                },
            )

            if (status.supportsPromotion && !status.promotionAllowed) {
                if (status.canOpenPromotionSettings) {
                    FilledTonalButton(onClick = onOpenPromotionSettings) {
                        Text("Turn promoted notifications on")
                    }
                } else {
                    Text(
                        text = "This build has no screen for that switch. Look for it under " +
                            "Kickoff's notification settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    onSetGoals: (Boolean) -> Unit,
    onSetCards: (Boolean) -> Unit,
    onSetSubstitutions: (Boolean) -> Unit,
    onSetKickoffAndFullTime: (Boolean) -> Unit,
    onSetLineups: (Boolean) -> Unit,
    onSetLeadMinutes: (Int) -> Unit,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(title = "Alerts", modifier = modifier) {
        if (!access.granted) {
            Notice(
                text = "Notifications are switched off for Kickoff, so none of these alerts " +
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

// ---- 3. dynamic island --------------------------------------------------------------

@Composable
internal fun IslandSection(
    status: IslandStatus,
    onSetEnabled: (Boolean) -> Unit,
    onGrantOverlayPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(title = "Dynamic Island", modifier = modifier) {
        SettingsToggleRow(
            title = "Float over other apps",
            subtitle = "Keeps the live island on screen while you are somewhere else.",
            icon = Icons.Outlined.Layers,
            checked = status.floatingEnabled,
            enabled = status.overlayPermissionGranted,
            onCheckedChange = onSetEnabled,
        )
        Column(
            modifier = Modifier.padding(horizontal = GroupPadding, vertical = TightGap),
            verticalArrangement = Arrangement.spacedBy(TightGap),
        ) {
            Text(
                text = "Floating needs Android's \"display over other apps\" permission, which " +
                    "is granted on a system page rather than in a dialog. The island inside " +
                    "Kickoff needs no permission at all and is always available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!status.overlayPermissionGranted) {
                FilledTonalButton(onClick = onGrantOverlayPermission) {
                    Text("Grant permission")
                }
            }
        }
    }
}

// ---- 4. data source -----------------------------------------------------------------

@Composable
internal fun DataSourceSection(
    form: DataSourceForm,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onSaveApiKey: () -> Unit,
    onBackendUrlChange: (String) -> Unit,
    onSaveBackendUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    SettingsGroup(title = "Data source", modifier = modifier) {
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
                label = { Text("Kickoff backend URL") },
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
    }
}

// ---- 5. appearance ------------------------------------------------------------------

@Composable
internal fun AppearanceSection(
    settings: AppSettings,
    dynamicColorAvailable: Boolean,
    onSelectTheme: (AppSettings.DarkThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(title = "Appearance", modifier = modifier) {
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
            text = "Dynamic colour replaces Kickoff's own palette with hues derived from your " +
                "wallpaper. Club colours, the live pill and the card colours stay as they are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = GroupPadding, vertical = TightGap),
        )
    }
}

// ---- 6. about -----------------------------------------------------------------------

@Composable
internal fun AboutSection(version: String, modifier: Modifier = Modifier) {
    SettingsGroup(title = "About", modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = GroupPadding, vertical = TightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KickoffLogo(size = LogoSize)
            Spacer(Modifier.width(RowGap))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Kickoff",
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
    }
}

// ---- shared pieces ------------------------------------------------------------------

@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = title, modifier = Modifier.padding(horizontal = GroupPadding))
        Card(
            shape = KickoffShapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(vertical = GroupPadding),
                content = content,
            )
        }
    }
}

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

private val GroupPadding = 16.dp
private val RowGap = 12.dp
private val TightGap = 6.dp
private val LogoSize = 44.dp
private val StatusIconSize = 16.dp
