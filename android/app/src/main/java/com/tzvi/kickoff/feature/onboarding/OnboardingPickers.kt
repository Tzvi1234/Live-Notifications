package com.tzvi.kickoff.feature.onboarding

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.ui.component.CrestImage
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.LoadingState
import com.tzvi.kickoff.ui.component.TeamCrest
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

@Composable
internal fun LeaguesPage(
    state: OnboardingUiState,
    onToggleLeague: (League) -> Unit,
    onRetry: () -> Unit,
    onFixSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leaguesFailure = state.leaguesFailure

    // The title, the count and "pick at least one" all live in the fixed frame now, so the
    // page itself is nothing but the grid - one job per screen region.
    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.leaguesPending -> LoadingState(
                modifier = Modifier.weight(1f),
                label = "Loading competitions",
            )

            leaguesFailure != null -> CatalogueFailureState(
                failure = leaguesFailure,
                subject = "competitions",
                onRetry = onRetry,
                onFixSource = onFixSource,
                modifier = Modifier.weight(1f),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 164.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = OnboardingSpacing.screen,
                    end = OnboardingSpacing.screen,
                    top = 2.dp,
                    bottom = OnboardingSpacing.block,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = state.leagues, key = { it.id }) { league ->
                    LeagueChip(
                        league = league,
                        selected = league.id in state.selectedLeagueIds,
                        onClick = { onToggleLeague(league) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LeagueChip(
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
        label = "league-chip-container",
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
internal fun TeamsPage(
    state: OnboardingUiState,
    onQueryChange: (String) -> Unit,
    onToggleTeam: (TeamOption) -> Unit,
    onRemoveTeam: (Int) -> Unit,
    onRetry: () -> Unit,
    onFixSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val matching = remember(state.teams, state.teamQuery) { state.matchingTeams() }
    val chosen = remember(state.selected) { state.selected.values.toList() }
    val teamsFailure = state.teamsFailure

    Column(modifier = modifier.fillMaxSize()) {
        // Picked teams then the search box, both pinned above the list: the two things you
        // act on stay put while the list under them scrolls.
        Column(
            modifier = Modifier.padding(horizontal = OnboardingSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.tight),
        ) {
            AnimatedVisibility(
                visible = chosen.isNotEmpty(),
                enter = expandVertically(Motion.sizeSpring()) +
                    fadeIn(Motion.effects(Motion.Duration.SHORT)),
                exit = shrinkVertically(Motion.sizeSpring()) +
                    fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ) {
                SelectedTeamsRow(teams = chosen, onRemove = onRemoveTeam)
            }
            OutlinedTextField(
                value = state.teamQuery,
                onValueChange = onQueryChange,
                placeholder = { Text("Search teams") },
                singleLine = true,
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (state.teamQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Clear search",
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(OnboardingSpacing.tight))

        // A quiet line rather than a spinner, once anything has arrived: the clubs that
        // answered are already selectable underneath it while the rest are still coming.
        AnimatedVisibility(visible = !state.teamsBlocked && state.teamsRemaining > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = OnboardingSpacing.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KickoffLoader(size = 14.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (state.teamsRemaining == 1) {
                        "Still loading one more competition…"
                    } else {
                        "Still loading ${state.teamsRemaining} more competitions…"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            state.teamsBlocked -> LoadingState(
                modifier = Modifier.weight(1f),
                label = "Loading squads",
            )

            teamsFailure != null -> CatalogueFailureState(
                failure = teamsFailure,
                subject = "teams",
                onRetry = onRetry,
                onFixSource = onFixSource,
                modifier = Modifier.weight(1f),
            )

            matching.isEmpty() -> Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = "No team matches that",
                    body = "Nothing in the competitions you picked is called " +
                        "\"${state.teamQuery.trim()}\". Try a shorter search, or go back " +
                        "and add another competition.",
                    icon = Icons.Outlined.Search,
                    actionLabel = "Clear search",
                    onAction = { onQueryChange("") },
                )
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = OnboardingSpacing.screen - 12.dp,
                    end = OnboardingSpacing.screen - 12.dp,
                    top = 4.dp,
                    bottom = OnboardingSpacing.block,
                ),
            ) {
                items(items = matching, key = { it.team.id }) { option ->
                    TeamRow(
                        option = option,
                        selected = state.selected.containsKey(option.team.id),
                        onToggle = { onToggleTeam(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedTeamsRow(
    teams: List<TeamOption>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OnboardingSpacing.tight),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items = teams, key = { it.team.id }) { option ->
            SelectedTeamChip(
                option = option,
                onRemove = { onRemove(option.team.id) },
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
private fun SelectedTeamChip(
    option: TeamOption,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            TeamCrest(team = option.team, size = 22.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = option.team.shortName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove ${option.team.name}",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun TeamRow(
    option: TeamOption,
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
            val subtitle = option.league?.name ?: option.team.countryName
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

/** One place for the three ways a catalogue fetch can come back with nothing. */
@Composable
private fun CatalogueFailureState(
    failure: CatalogueError,
    subject: String,
    onRetry: () -> Unit,
    onFixSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (failure.kind) {
            CatalogueFailure.NO_SOURCE -> EmptyState(
                title = "No data source yet",
                body = "matchUP cannot list $subject until it has an API-Football key or " +
                    "the URL of a backend. That is the previous step.",
                icon = Icons.Outlined.Key,
                actionLabel = "Back to the source step",
                onAction = onFixSource,
            )

            CatalogueFailure.UNREACHABLE -> EmptyState(
                title = "Could not reach the source",
                body = "The request for $subject failed. Check the connection, and that " +
                    "the key or URL you entered is the right one.",
                icon = Icons.Outlined.CloudOff,
                actionLabel = "Try again",
                onAction = onRetry,
            )

            CatalogueFailure.EMPTY -> EmptyState(
                title = "Nothing came back",
                body = "The source answered without any $subject. On a free API-Football " +
                    "key that usually means the day's 100 requests are already spent.",
                icon = Icons.Outlined.CloudOff,
                actionLabel = "Try again",
                onAction = onRetry,
            )
        }

        // The exact words the source used. Without them "could not reach the source"
        // covers a dead network, a wrong key and a plan restriction identically.
        failure.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        }
    }
}

@Preview(name = "Leagues")
@Composable
private fun LeaguesPagePreview() {
    KickoffTheme {
        LeaguesPage(
            state = OnboardingUiState(
                leagues = OnboardingSamples.leagues,
                selectedLeagueIds = setOf(39, 140),
            ),
            onToggleLeague = {},
            onRetry = {},
            onFixSource = {},
        )
    }
}

@Preview(name = "Leagues - no source")
@Composable
private fun LeaguesNoSourcePreview() {
    KickoffTheme {
        LeaguesPage(
            state = OnboardingUiState(
                leaguesFailure = CatalogueError(CatalogueFailure.NO_SOURCE),
            ),
            onToggleLeague = {},
            onRetry = {},
            onFixSource = {},
        )
    }
}

@Preview(name = "Teams")
@Composable
private fun TeamsPagePreview() {
    KickoffTheme {
        TeamsPage(
            state = OnboardingUiState(
                teams = OnboardingSamples.teams,
                selected = mapOf(
                    OnboardingSamples.teams[0].team.id to OnboardingSamples.teams[0],
                    OnboardingSamples.teams[2].team.id to OnboardingSamples.teams[2],
                ),
            ),
            onQueryChange = {},
            onToggleTeam = {},
            onRemoveTeam = {},
            onRetry = {},
            onFixSource = {},
        )
    }
}
