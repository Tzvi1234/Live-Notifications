package com.tzvi.kickoff.feature.teams

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.ui.component.CrestImage
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.ui.component.TeamCrest
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * The star, which bounces every time it is flipped.
 *
 * The bounce is deliberately the only feedback: adding a favourite writes to Room and the
 * list re-sorts a frame later, which on its own reads as the row jumping for no reason.
 */
@Composable
internal fun StarToggle(
    isFavourite: Boolean,
    teamName: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bounce = rememberStarBounce(isFavourite)
    val tint by animateColorAsState(
        targetValue = if (isFavourite) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = Motion.effects(Motion.Duration.SHORT),
        label = "star-tint",
    )

    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (isFavourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (isFavourite) {
                "Remove $teamName from my teams"
            } else {
                "Add $teamName to my teams"
            },
            tint = tint,
            modifier = Modifier.graphicsLayer {
                val scale = bounce.value
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

/**
 * Scale that overshoots once whenever [isFavourite] flips, and never on first
 * composition - a list scrolling into view must not make every star pop.
 */
@Composable
internal fun rememberStarBounce(isFavourite: Boolean): Animatable<Float, AnimationVector1D> {
    val scale = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(isFavourite) }
    LaunchedEffect(isFavourite) {
        if (isFavourite == previous) return@LaunchedEffect
        previous = isFavourite
        scale.animateTo(StarBouncePeak, Motion.spatialFast())
        scale.animateTo(1f, Motion.spatialExpressive())
    }
    return scale
}

@Composable
internal fun FavouriteTeamCard(
    team: Team,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamCrest(team = team, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            TeamLabels(team = team, subtitle = team.countryName, modifier = Modifier.weight(1f))
            StarToggle(isFavourite = true, teamName = team.name, onToggle = onRemove)
        }
    }
}

/** A competition that unfolds into its squads. */
@Composable
internal fun LeagueCard(
    section: LeagueSection,
    favouriteIds: Set<Int>,
    onToggleExpanded: () -> Unit,
    onRetry: () -> Unit,
    onOpenTeam: (Team) -> Unit,
    onToggleFavourite: (Team) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = section.failure
    val chevron by animateFloatAsState(
        targetValue = if (section.expanded) ChevronOpenDegrees else 0f,
        animationSpec = Motion.floatSpring(),
        label = "league-chevron",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CrestImage(
                    url = section.league.logoUrl,
                    fallback = section.league.name,
                    size = 30.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = section.league.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val caption = section.league.countryName
                        ?: "Season ${section.league.season}"
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (section.expanded) {
                        "Collapse ${section.league.name}"
                    } else {
                        "Expand ${section.league.name}"
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevron },
                )
            }

            AnimatedVisibility(
                visible = section.expanded,
                enter = expandVertically(Motion.sizeSpring()) +
                    fadeIn(Motion.effects(Motion.Duration.SHORT)),
                exit = shrinkVertically(Motion.sizeSpring()) +
                    fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ) {
                Column(Modifier.padding(bottom = 6.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    when {
                        section.isLoading -> InlineLoader("Loading squads")

                        failure != null -> TeamsFailureState(
                            failure = failure,
                            subject = "${section.league.name} squads",
                            onRetry = onRetry,
                        )

                        else -> section.teams.forEach { team ->
                            key(team.id) {
                                TeamRow(
                                    team = team,
                                    isFavourite = team.id in favouriteIds,
                                    subtitle = team.venueName ?: team.countryName,
                                    onOpen = { onOpenTeam(team) },
                                    onToggleFavourite = { onToggleFavourite(team) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TeamRow(
    team: Team,
    isFavourite: Boolean,
    subtitle: String?,
    onOpen: () -> Unit,
    onToggleFavourite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamCrest(team = team, size = 30.dp)
        Spacer(Modifier.width(12.dp))
        TeamLabels(team = team, subtitle = subtitle, modifier = Modifier.weight(1f))
        StarToggle(
            isFavourite = isFavourite,
            teamName = team.name,
            onToggle = onToggleFavourite,
        )
    }
}

@Composable
private fun TeamLabels(team: Team, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = team.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
}

/** What the search bar shows once it is open, across all five of its states. */
@Composable
internal fun SearchResults(
    search: SearchState,
    favouriteIds: Set<Int>,
    onOpenTeam: (Team) -> Unit,
    onToggleFavourite: (Team) -> Unit,
    onClearQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = search.failure

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            search.isSearching -> InlineLoader("Searching clubs")

            search.isTooShort -> EmptyState(
                title = "Search by club name",
                body = "Three letters is the shortest the football API will take. Full " +
                    "names work best: \"Tottenham\" finds Spurs, \"Spurs\" does not.",
                icon = Icons.Outlined.Search,
            )

            failure != null -> TeamsFailureState(
                failure = failure,
                subject = "clubs matching \"${search.query}\"",
                onRetry = onClearQuery,
                retryLabel = "Clear search",
                emptyTitle = "Nothing called \"${search.query}\"",
                emptyBody = "No club came back under that name. Try the full club name, " +
                    "or the city it plays in.",
                emptyIcon = Icons.Outlined.SearchOff,
            )

            // Keyed: the star's bounce lives in the row's own state, and without a key a
            // new set of results would inherit the previous row's and pop for no reason.
            else -> search.results.forEach { team ->
                key(team.id) {
                    TeamRow(
                        team = team,
                        isFavourite = team.id in favouriteIds,
                        subtitle = listOfNotNull(team.countryName, team.venueName)
                            .joinToString(" · ")
                            .takeIf { it.isNotBlank() },
                        onOpen = { onOpenTeam(team) },
                        onToggleFavourite = { onToggleFavourite(team) },
                    )
                }
            }
        }
    }
}

/** The three ways a fetch of teams comes back with nothing, each with its own way out. */
@Composable
internal fun TeamsFailureState(
    failure: TeamsFailure,
    subject: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "Try again",
    emptyTitle: String = "Nothing came back",
    emptyBody: String = "The source answered without any $subject. On a free " +
        "API-Football key that usually means the day's 100 requests are already spent.",
    emptyIcon: ImageVector = Icons.Outlined.CloudOff,
) {
    when (failure) {
        TeamsFailure.NO_SOURCE -> EmptyState(
            modifier = modifier,
            title = "No data source yet",
            body = "Kickoff cannot list $subject until Settings holds an API-Football " +
                "key or the URL of a Kickoff backend.",
            icon = Icons.Outlined.Key,
        )

        TeamsFailure.UNREACHABLE -> EmptyState(
            modifier = modifier,
            title = "Could not reach the source",
            body = "The request for $subject failed. Check the connection and that the " +
                "key or URL in Settings is still the right one.",
            icon = Icons.Outlined.CloudOff,
            actionLabel = retryLabel,
            onAction = onRetry,
        )

        TeamsFailure.EMPTY -> EmptyState(
            modifier = modifier,
            title = emptyTitle,
            body = emptyBody,
            icon = emptyIcon,
            actionLabel = retryLabel,
            onAction = onRetry,
        )
    }
}

@Composable
internal fun SourceMissingBanner(modifier: Modifier = Modifier) {
    Banner(
        modifier = modifier,
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        message = "No football source is configured, so searching and browsing have " +
            "nothing to ask. Add an API-Football key or a backend URL in Settings.",
    )
}

@Composable
internal fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Banner(
        modifier = modifier,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        message = message,
        action = "Dismiss",
        onAction = onDismiss,
    )
}

@Composable
private fun Banner(
    container: Color,
    content: Color,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapes.medium)
            .background(container)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
        if (action != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(action, color = content)
            }
        }
    }
}

/** [com.tzvi.kickoff.ui.component.LoadingState] fills the screen; this fills a row. */
@Composable
internal fun InlineLoader(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(InlineLoaderHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KickoffLoader(size = 26.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val StarBouncePeak = 1.32f
private const val ChevronOpenDegrees = 180f
private val InlineLoaderHeight = 88.dp

@Preview(name = "League - expanded")
@Composable
private fun LeagueCardPreview() {
    KickoffTheme {
        Box(Modifier.padding(16.dp)) {
            LeagueCard(
                section = LeagueSection(
                    league = TeamsSamples.premierLeague,
                    expanded = true,
                    teams = listOf(
                        TeamsSamples.arsenal,
                        TeamsSamples.chelsea,
                        TeamsSamples.spurs,
                    ),
                ),
                favouriteIds = setOf(TeamsSamples.arsenal.id),
                onToggleExpanded = {},
                onRetry = {},
                onOpenTeam = {},
                onToggleFavourite = {},
            )
        }
    }
}

@Preview(name = "Favourite team card")
@Composable
private fun FavouriteTeamCardPreview() {
    KickoffTheme {
        Box(Modifier.padding(16.dp)) {
            FavouriteTeamCard(team = TeamsSamples.arsenal, onOpen = {}, onRemove = {})
        }
    }
}
