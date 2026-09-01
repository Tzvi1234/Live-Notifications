package com.tzvi.kickoff.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * One settings section, closed until you want it.
 *
 * The screen used to render every group open at once, which meant seven headings, four
 * text fields, a segmented control and a slider all competing on one scroll - you had to
 * read the whole page to find anything. Closed, a card is a title and one line saying what
 * that section is currently doing; that line is the part you usually came to check, so
 * most visits never need to open anything at all.
 */
@Composable
internal fun SettingsCard(
    title: String,
    icon: ImageVector,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** A short state word - "ON", "3 PICKED" - when the section deserves one at a glance. */
    badge: String? = null,
    /** Draws the badge and the icon in the accent colour: something here wants attention. */
    highlighted: Boolean = false,
    /**
     * The long explanation, behind a "?" in the card's header.
     *
     * Every card used to print its full reasoning inline, which meant scrolling past
     * three paragraphs to reach a switch. The reasoning is still worth having - it is
     * what stops a setting being cargo-culted - so it moved one tap away instead of
     * being deleted.
     */
    help: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var showingHelp by remember { mutableStateOf(false) }
    val container by animateColorAsState(
        targetValue = if (expanded) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = Motion.effects(Motion.Duration.MEDIUM),
        label = "settings-card-container",
    )
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.floatSpring(),
        label = "settings-card-chevron",
    )
    val accent = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (expanded) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = CardPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(KickoffShapeTokens.pill)
                    .background(
                        if (highlighted) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (badge != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (highlighted) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clip(KickoffShapeTokens.pill)
                        .background(
                            if (highlighted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            if (help != null) {
                Spacer(Modifier.width(2.dp))
                IconButton(
                    onClick = { showingHelp = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HelpOutline,
                        contentDescription = "What does $title do?",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(chevron),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.sizeSpring()) +
                fadeIn(Motion.effects(Motion.Duration.MEDIUM)),
            exit = shrinkVertically(Motion.sizeSpring()) +
                fadeOut(Motion.effects(Motion.Duration.SHORT)),
        ) {
            Column(
                modifier = Modifier.padding(bottom = CardPadding),
                verticalArrangement = Arrangement.spacedBy(CardRowGap),
                content = content,
            )
        }
    }

    if (showingHelp && help != null) {
        AlertDialog(
            onDismissRequest = { showingHelp = false },
            icon = { Icon(imageVector = icon, contentDescription = null) },
            title = { Text(title) },
            text = { Text(help) },
            confirmButton = {
                TextButton(onClick = { showingHelp = false }) { Text("Got it") }
            },
        )
    }
}

internal val CardPadding = 16.dp
internal val CardRowGap = 12.dp

@Preview(name = "Settings card - closed")
@Composable
private fun SettingsCardClosedPreview() {
    KickoffTheme {
        SettingsCard(
            title = "Dynamic Island",
            icon = Icons.Filled.KeyboardArrowDown,
            summary = "Floating, calibrated 34 dp across",
            badge = "ON",
            highlighted = true,
            expanded = false,
            onToggle = {},
        ) {}
    }
}
