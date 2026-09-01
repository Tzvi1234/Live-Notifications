package com.tzvi.kickoff.feature.predict

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.ui.component.CrestImage
import com.tzvi.kickoff.ui.component.TeamCrest
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * The one thing about the game that has to be said in words.
 *
 * Everything else on the form is a list; this is the rule that makes picking one club
 * enough, and without it the obvious reading of "choose your teams" is that both sides of
 * a match have to be chosen for it to appear.
 */
@Composable
internal fun EitherSideNote(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "A match lands on the card if either side is one of your teams. " +
                    "Pick Arsenal and every Arsenal game is in, whoever they are playing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A cap that has been hit, or clubs that went out with their competition. */
@Composable
internal fun SetupNoticeLine(
    notice: SetupNotice?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = notice != null,
        enter = expandVertically(Motion.sizeSpring()) + fadeIn(Motion.effects(Motion.Duration.SHORT)),
        exit = shrinkVertically(Motion.sizeSpring()) + fadeOut(Motion.effects(Motion.Duration.SHORT)),
        modifier = modifier,
    ) {
        Surface(
            shape = KickoffShapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // Held while the visibility animation runs out, so the sentence does
                    // not blank out a frame before the row has finished collapsing.
                    text = notice?.message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SetupLeagueChip(
    league: League,
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
        label = "setup-league-container",
    )

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.medium,
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CrestImage(url = league.logoUrl, fallback = league.name, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = league.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val country = league.countryName
                if (country != null) {
                    Text(
                        text = country,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedVisibility(
                visible = selected,
                enter = scaleIn(Motion.spatial()) + fadeIn(Motion.effects(Motion.Duration.SHORT)),
                exit = scaleOut(Motion.spatial()) + fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
internal fun SetupTeamRow(
    option: SquadOption,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapes.medium)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamCrest(team = option.team, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = option.team.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = option.leagueName ?: option.team.countryName
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Checkbox(checked = selected, onCheckedChange = null)
    }
}

@Composable
internal fun PickedTeamsRow(
    teams: List<Team>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items = teams, key = { it.id }) { team ->
            PickedTeamChip(
                team = team,
                onRemove = { onRemove(team.id) },
                modifier = Modifier.animateItem(
                    fadeInSpec = Motion.effects(Motion.Duration.SHORT),
                    placementSpec = Motion.offsetSpring(),
                    fadeOutSpec = Motion.effects(Motion.Duration.SHORT),
                ),
            )
        }
    }
}

@Composable
private fun PickedTeamChip(team: Team, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onRemove,
        modifier = modifier,
        shape = KickoffShapeTokens.pill,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamCrest(team = team, size = 22.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = team.shortName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove ${team.name}",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** One squad is in flight, named, so a slow list reads as progress rather than a stall. */
@Composable
internal fun SquadProgressLine(leagueName: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = leagueName?.let { "Loading the $it squads" } ?: "Loading squads",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A squad that never came, and the one action that can change that. */
@Composable
internal fun SquadFailureLine(
    names: List<String>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = when (names.size) {
                1 -> "No squad came back for ${names.first()}, so its clubs are missing here."
                else -> "No squad came back for ${names.size} competitions, so their clubs " +
                    "are missing here."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = onRetry) { Text("Try those again") }
    }
}

@Preview(name = "Setup pieces", widthDp = 380)
@Composable
private fun GroupSetupSectionsPreview() {
    KickoffTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EitherSideNote()
            SetupNoticeLine(
                notice = SetupNotice(
                    SetupNoticeKind.TEAMS_DROPPED,
                    "The Bundesliga came out, and took 2 teams with it.",
                ),
                onDismiss = {},
            )
            SetupLeagueChip(
                league = League(39, "Premier League", "England", null, 2025),
                selected = true,
                onClick = {},
            )
            SetupTeamRow(
                option = SquadOption(Team(42, "Arsenal", "ARS", null), "Premier League"),
                selected = true,
                onToggle = {},
            )
            PickedTeamsRow(
                teams = listOf(
                    Team(42, "Arsenal", "ARS", null),
                    Team(49, "Chelsea", "CHE", null),
                ),
                onRemove = {},
            )
            SquadProgressLine(leagueName = "Serie A")
        }
    }
}
