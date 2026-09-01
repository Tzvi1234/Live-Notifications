package com.tzvi.kickoff.feature.teams

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.Stadium
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.LineupPlayer
import com.tzvi.kickoff.feature.player.PlayerRequest
import com.tzvi.kickoff.feature.player.PlayerSheet
import com.tzvi.kickoff.ui.component.CrestImage
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.MetaChip
import com.tzvi.kickoff.ui.component.SectionHeader
import com.tzvi.kickoff.ui.component.TeamCrest
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The team sheet.
 *
 * A fixture row deliberately carries no container transform. The sheet is a separate
 * window, so a shared-bounds element inside it has no coordinate space in common with
 * the destination; the sheet is animated away first and the match screen opens after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamSheet(
    state: TeamSheetState,
    onDismiss: () -> Unit,
    onToggleFavourite: () -> Unit,
    onOpenMatch: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Sheet over sheet is deliberate: dismissing the player drops you back onto the squad
    // you were browsing, which is exactly where the next tap is going.
    var playerRequest by remember { mutableStateOf<PlayerRequest?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        TeamSheetBody(
            state = state,
            onToggleFavourite = onToggleFavourite,
            onOpenMatch = { matchId ->
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                    onOpenMatch(matchId)
                }
            },
            onPlayerTap = { player ->
                player.id?.let { id ->
                    playerRequest = PlayerRequest(
                        playerId = id,
                        name = player.name,
                        photoUrl = player.photoUrl,
                        teamName = state.team.name,
                        matchId = null,
                    )
                }
            },
        )
    }

    playerRequest?.let { request ->
        PlayerSheet(request = request, onDismiss = { playerRequest = null })
    }
}

@Composable
internal fun TeamSheetBody(
    state: TeamSheetState,
    onToggleFavourite: () -> Unit,
    onOpenMatch: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onPlayerTap: (LineupPlayer) -> Unit = {},
) {
    val team = state.team

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = SheetPadding)
            .padding(bottom = SheetBottomPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamCrest(team = team, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = team.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    state.leagueName,
                    team.founded?.let { "Founded $it" },
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        DetailRow(
            icon = Icons.Outlined.Stadium,
            text = team.venueName ?: "Home ground not listed by the provider",
        )
        DetailRow(
            icon = Icons.Outlined.Public,
            text = team.countryName ?: "Country not listed by the provider",
        )

        Spacer(Modifier.height(18.dp))
        FavouriteButton(isFavourite = state.isFavourite, onClick = onToggleFavourite)

        when {
            state.squad.isNotEmpty() -> {
                Spacer(Modifier.height(6.dp))
                SectionHeader(title = "Squad")
                SquadGrid(players = state.squad, onPlayerTap = onPlayerTap)
                Spacer(Modifier.height(10.dp))
            }

            state.squadLoading -> {
                Spacer(Modifier.height(6.dp))
                SectionHeader(title = "Squad")
                InlineLoader("Pulling in the squad")
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(6.dp))
        SectionHeader(title = "Next fixtures")
        FixtureBlock(
            state = state,
            matches = state.fixtures,
            emptyTitle = "Nothing scheduled",
            emptyBody = "${state.team.name} has no fixture on the books yet.",
            onOpenMatch = onOpenMatch,
        )

        if (state.results.isNotEmpty() || state.fixturesLoading) {
            Spacer(Modifier.height(10.dp))
            SectionHeader(title = "Recent results")
            FixtureBlock(
                state = state,
                matches = state.results,
                emptyTitle = "No results yet",
                emptyBody = "${state.team.name} has not played a match the source knows about.",
                onOpenMatch = onOpenMatch,
            )
        }
    }
}

@Composable
private fun FixtureBlock(
    state: TeamSheetState,
    matches: List<Match>,
    emptyTitle: String,
    emptyBody: String,
    onOpenMatch: (Long) -> Unit,
) {
    when {
        matches.isNotEmpty() -> matches.forEach { match ->
            FixtureRow(
                match = match,
                teamId = state.team.id,
                onClick = { onOpenMatch(match.id) },
            )
        }

        state.fixturesLoading -> InlineLoader("Pulling in fixtures")

        state.fixturesFailed -> EmptyState(
            title = "Couldn't reach the source",
            body = "${state.team.name}'s matches did not come back this time.",
            icon = Icons.Outlined.SportsSoccer,
        )

        else -> EmptyState(
            title = emptyTitle,
            body = emptyBody,
            icon = Icons.Outlined.SportsSoccer,
        )
    }
}

@Composable
private fun FavouriteButton(
    isFavourite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bounce = rememberStarBounce(isFavourite)
    // One Button whose colours cross-fade, rather than swapping in a different button:
    // a swap would remount the star and swallow the bounce that marks the change.
    val container by animateColorAsState(
        targetValue = if (isFavourite) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = Motion.effects(Motion.Duration.MEDIUM),
        label = "favourite-button-container",
    )
    val content by animateColorAsState(
        targetValue = if (isFavourite) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        animationSpec = Motion.effects(Motion.Duration.MEDIUM),
        label = "favourite-button-content",
    )

    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Icon(
            imageVector = if (isFavourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    val scale = bounce.value
                    scaleX = scale
                    scaleY = scale
                },
        )
        Spacer(Modifier.width(10.dp))
        Text(if (isFavourite) "In my teams" else "Add to my teams")
    }
}

@Composable
private fun FixtureRow(
    match: Match,
    teamId: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val atHome = match.home.id == teamId
    val opponent = if (atHome) match.away else match.home
    val kickoff = match.kickoffAt.atZone(ZoneId.systemDefault())

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.width(58.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = DAY_FORMAT.format(kickoff),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = TIME_FORMAT.format(kickoff),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        TeamCrest(team = opponent, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = opponent.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = match.leagueName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        MetaChip(text = if (atHome) "HOME" else "AWAY")
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val SheetPadding = 20.dp
private val SheetBottomPadding = 28.dp
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Preview(name = "Team sheet - followed")
@Composable
private fun TeamSheetFollowedPreview() {
    KickoffTheme {
        TeamSheetBody(
            state = TeamsSamples.sheetState(),
            onToggleFavourite = {},
            onOpenMatch = {},
        )
    }
}

/**
 * The roster as faces.
 *
 * A FlowRow rather than a lazy grid: the sheet already scrolls, and a nested lazy
 * container inside a scrollable column is the classic infinite-height crash.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SquadGrid(
    players: List<LineupPlayer>,
    onPlayerTap: (LineupPlayer) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        players.forEach { player ->
            SquadFace(player = player, onTap = { onPlayerTap(player) })
        }
    }
}

@Composable
private fun SquadFace(player: LineupPlayer, onTap: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(64.dp)
            .clip(KickoffShapes.small)
            .clickable(onClick = onTap)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CrestImage(url = player.photoUrl, fallback = player.surname, size = 44.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = player.surname,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        player.number?.let {
            Text(
                text = "#$it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Team sheet - not followed")
@Composable
private fun TeamSheetUnfollowedPreview() {
    KickoffTheme {
        TeamSheetBody(
            state = TeamSheetState(
                team = TeamsSamples.barcelona,
                leagueId = TeamsSamples.laLiga.id,
                leagueName = TeamsSamples.laLiga.name,
            ),
            onToggleFavourite = {},
            onOpenMatch = {},
        )
    }
}
