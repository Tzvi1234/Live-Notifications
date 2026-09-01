package com.tzvi.kickoff.feature.predict

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.LoadingState
import com.tzvi.kickoff.ui.component.SectionHeader
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * Naming a group and deciding what it plays for.
 *
 * Two panes rather than one long form: the competitions are a short grid and the clubs
 * inside them are hundreds of rows with a search box over them, and stacking those in one
 * scroll would put the search box a screen and a half below the thing it filters. The
 * clubs pane is a drill-down, so the name and the competitions are still there behind it.
 *
 * It is drawn inside the predictions screen rather than given a route of its own: the form
 * is only reachable from a group, its result is that group, and a route would have to
 * carry the whole half-filled selection through a back stack to survive rotation anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupSetupScreen(
    setup: GroupSetup,
    onClose: () -> Unit,
    onName: (String) -> Unit,
    onToggleLeague: (League) -> Unit,
    onRetryLeagues: () -> Unit,
    onPickTeams: (Boolean) -> Unit,
    onToggleTeam: (Team) -> Unit,
    onRemoveTeam: (Int) -> Unit,
    onSearch: (String) -> Unit,
    onRetrySquads: () -> Unit,
    onDismissNotice: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { if (setup.pickingTeams) onPickTeams(false) else onClose() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when {
                            setup.pickingTeams -> "Choose teams"
                            setup.isEditing -> "Edit group"
                            else -> "New group"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (setup.pickingTeams) onPickTeams(false) else onClose() },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        bottomBar = {
            SetupBar(
                setup = setup,
                onDone = { onPickTeams(false) },
                onSave = onSave,
            )
        },
    ) { insets ->
        AnimatedContent(
            targetState = setup.pickingTeams,
            transitionSpec = {
                // The clubs pane arrives from the trailing edge and leaves back through
                // it, the same way the app's own destinations move.
                val direction = if (targetState) 1 else -1
                (
                    slideInHorizontally(Motion.offsetSpring()) { width -> direction * width / 5 } +
                        fadeIn(Motion.effects(Motion.Duration.MEDIUM))
                    ).togetherWith(
                    slideOutHorizontally(Motion.offsetSpring()) { width -> -direction * width / 5 } +
                        fadeOut(Motion.effects(Motion.Duration.SHORT)),
                )
            },
            label = "group-setup-pane",
            modifier = Modifier.fillMaxSize().padding(insets),
        ) { picking ->
            if (picking) {
                TeamsPane(
                    setup = setup,
                    onSearch = onSearch,
                    onToggleTeam = onToggleTeam,
                    onRemoveTeam = onRemoveTeam,
                    onRetrySquads = onRetrySquads,
                    onDismissNotice = onDismissNotice,
                )
            } else {
                DetailsPane(
                    setup = setup,
                    onName = onName,
                    onToggleLeague = onToggleLeague,
                    onRetryLeagues = onRetryLeagues,
                    onPickTeams = { onPickTeams(true) },
                    onRemoveTeam = onRemoveTeam,
                    onRetrySquads = onRetrySquads,
                    onDismissNotice = onDismissNotice,
                )
            }
        }
    }
}

@Composable
private fun DetailsPane(
    setup: GroupSetup,
    onName: (String) -> Unit,
    onToggleLeague: (League) -> Unit,
    onRetryLeagues: () -> Unit,
    onPickTeams: () -> Unit,
    onRemoveTeam: (Int) -> Unit,
    onRetrySquads: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    // Two per row rather than an adaptive grid: a lazy grid cannot be nested in the lazy
    // list that carries the rest of the form, and the form has to scroll as one thing.
    val rows = remember(setup.leagues) { setup.leagues.chunked(2) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = SetupPadding,
            end = SetupPadding,
            top = 8.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "name") {
            OutlinedTextField(
                value = setup.name,
                onValueChange = onName,
                label = { Text("Group name") },
                singleLine = true,
                supportingText = {
                    // Only near the end: a counter on an empty field is noise, and one
                    // that appears at 50 characters explains why typing stops at 60.
                    if (setup.name.length >= GroupLimits.NAME - NAME_COUNTER_FROM) {
                        Text("${setup.name.length}/${GroupLimits.NAME}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "rule") { EitherSideNote() }

        item(key = "leagues-header") {
            SetupSection(
                title = "Competitions",
                caption = "${setup.leagueIds.size} of ${GroupLimits.LEAGUES} chosen",
            )
        }

        item(key = "leagues-notice") {
            SetupNoticeLine(
                notice = setup.notice?.takeIf { it.kind != SetupNoticeKind.TEAM_CAP },
                onDismiss = onDismissNotice,
            )
        }

        when {
            setup.leaguesLoading -> item(key = "leagues-loading") {
                LoadingState(
                    modifier = Modifier.height(CatalogueSlot),
                    label = "Loading competitions",
                )
            }

            setup.leaguesError != null -> item(key = "leagues-error") {
                EmptyState(
                    title = "Couldn't list the competitions",
                    body = setup.leaguesError,
                    icon = Icons.Outlined.CloudOff,
                    actionLabel = "Try again",
                    onAction = onRetryLeagues,
                )
            }

            else -> items(rows, key = { row -> row.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { league ->
                        SetupLeagueChip(
                            league = league,
                            selected = league.id in setup.leagueIds,
                            onClick = { onToggleLeague(league) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps a lone chip in the last row the width of the ones above it.
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        item(key = "teams-header") {
            SetupSection(
                title = "Teams",
                caption = "${setup.pickedCount} of ${GroupLimits.TEAMS} picked",
            )
        }

        item(key = "teams-picked") {
            AnimatedVisibility(
                visible = setup.pickedTeams.isNotEmpty(),
                enter = expandVertically(Motion.sizeSpring()) +
                    fadeIn(Motion.effects(Motion.Duration.SHORT)),
                exit = shrinkVertically(Motion.sizeSpring()) +
                    fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ) {
                PickedTeamsRow(teams = setup.pickedTeams, onRemove = onRemoveTeam)
            }
        }

        item(key = "teams-open") {
            OutlinedButton(
                onClick = onPickTeams,
                enabled = setup.leagueIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        setup.leagueIds.isEmpty() -> "Pick a competition first"
                        setup.pickedCount == 0 -> "Choose teams"
                        else -> "Change teams"
                    },
                )
            }
        }

        if (setup.squadsLoading) {
            item(key = "squad-progress") { SquadProgressLine(setup.loadingLeagueName) }
        }
        if (setup.failedLeagueIds.isNotEmpty()) {
            item(key = "squad-failure") {
                SquadFailureLine(names = setup.failedLeagueNames, onRetry = onRetrySquads)
            }
        }
    }
}

@Composable
private fun TeamsPane(
    setup: GroupSetup,
    onSearch: (String) -> Unit,
    onToggleTeam: (Team) -> Unit,
    onRemoveTeam: (Int) -> Unit,
    onRetrySquads: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val squad = remember(setup.teamsByLeague, setup.leagueIds) { setup.squad }
    val matching = remember(squad, setup.teamQuery) { setup.matchingSquad() }

    Column(Modifier.fillMaxSize()) {
        // What is picked and the search box are both pinned: the two things being acted
        // on stay put while the list under them scrolls.
        Column(
            modifier = Modifier.padding(horizontal = SetupPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnimatedVisibility(
                visible = setup.pickedTeams.isNotEmpty(),
                enter = expandVertically(Motion.sizeSpring()) +
                    fadeIn(Motion.effects(Motion.Duration.SHORT)),
                exit = shrinkVertically(Motion.sizeSpring()) +
                    fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ) {
                PickedTeamsRow(teams = setup.pickedTeams, onRemove = onRemoveTeam)
            }
            OutlinedTextField(
                value = setup.teamQuery,
                onValueChange = onSearch,
                placeholder = { Text("Search teams") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            SetupNoticeLine(
                notice = setup.notice?.takeIf { it.kind == SetupNoticeKind.TEAM_CAP },
                onDismiss = onDismissNotice,
            )
        }
        Spacer(Modifier.height(6.dp))

        when {
            squad.isEmpty() && setup.squadsLoading -> LoadingState(
                modifier = Modifier.weight(1f),
                label = setup.loadingLeagueName?.let { "Loading the $it squads" }
                    ?: "Loading squads",
            )

            squad.isEmpty() && setup.failedLeagueIds.isNotEmpty() -> Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = "No squads came back",
                    body = "The source answered without any clubs for the competitions " +
                        "you picked, so there is nothing to choose from yet.",
                    icon = Icons.Outlined.CloudOff,
                    actionLabel = "Try again",
                    onAction = onRetrySquads,
                )
            }

            matching.isEmpty() -> Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = "No team matches that",
                    body = "Nothing in the competitions you picked is called " +
                        "\"${setup.teamQuery.trim()}\". Try a shorter search, or go back " +
                        "and add another competition.",
                    icon = Icons.Outlined.Search,
                    actionLabel = "Clear search",
                    onAction = { onSearch("") },
                )
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = SetupPadding - 12.dp,
                    end = SetupPadding - 12.dp,
                    top = 4.dp,
                    bottom = 24.dp,
                ),
            ) {
                items(matching, key = { it.team.id }) { option ->
                    SetupTeamRow(
                        option = option,
                        selected = setup.isPicked(option.team.id),
                        onToggle = { onToggleTeam(option.team) },
                    )
                }
                // Below the rows, not above them: a squad still arriving must not push
                // the list the user is reading down the screen.
                if (setup.squadsLoading) {
                    item(key = "squad-progress") {
                        SquadProgressLine(
                            leagueName = setup.loadingLeagueName,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                if (setup.failedLeagueIds.isNotEmpty()) {
                    item(key = "squad-failure") {
                        SquadFailureLine(
                            names = setup.failedLeagueNames,
                            onRetry = onRetrySquads,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The heading of one part of the form, with what it has to show for itself so far. */
@Composable
private fun SetupSection(title: String, caption: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(title = title, modifier = Modifier.weight(1f))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupBar(
    setup: GroupSetup,
    onDone: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SetupPadding, vertical = 10.dp),
    ) {
        if (setup.pickingTeams) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when (setup.pickedCount) {
                        0 -> "Done"
                        1 -> "Done, 1 team"
                        else -> "Done, ${setup.pickedCount} teams"
                    },
                )
            }
            return@Column
        }
        setup.saveError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The reason sits beside the button rather than replacing it: a grey button
            // with nothing next to it is the most confusing thing a form can do.
            setup.blockedReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
            }
            Button(
                onClick = onSave,
                enabled = setup.canSave,
                modifier = if (setup.blockedReason == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                },
            ) {
                Text(
                    text = if (setup.isEditing) "Save changes" else "Create group",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private val SetupPadding = 16.dp

/** Enough of the screen for a loading state to read as a section rather than a bar. */
private val CatalogueSlot = 180.dp

private const val NAME_COUNTER_FROM = 10

private val PreviewLeagues = listOf(
    League(39, "Premier League", "England", null, 2025),
    League(140, "La Liga", "Spain", null, 2025),
    League(78, "Bundesliga", "Germany", null, 2025),
    League(135, "Serie A", "Italy", null, 2025),
)

@Preview(name = "Group setup", heightDp = 820)
@Composable
private fun GroupSetupPreview() {
    KickoffTheme {
        GroupSetupScreen(
            setup = GroupSetup(
                name = "The Sunday League",
                leagues = PreviewLeagues,
                leagueIds = setOf(39, 140),
                selectedTeams = mapOf(42 to Team(42, "Arsenal", "ARS", null)),
                teamsByLeague = mapOf(39 to listOf(Team(42, "Arsenal", "ARS", null))),
            ),
            onClose = {},
            onName = {},
            onToggleLeague = {},
            onRetryLeagues = {},
            onPickTeams = {},
            onToggleTeam = {},
            onRemoveTeam = {},
            onSearch = {},
            onRetrySquads = {},
            onDismissNotice = {},
            onSave = {},
        )
    }
}

@Preview(name = "Group setup - teams", heightDp = 820)
@Composable
private fun GroupSetupTeamsPreview() {
    KickoffTheme {
        GroupSetupScreen(
            setup = GroupSetup(
                name = "The Sunday League",
                leagues = PreviewLeagues,
                leagueIds = setOf(39),
                pickingTeams = true,
                teamsByLeague = mapOf(
                    39 to listOf(
                        Team(42, "Arsenal", "ARS", null),
                        Team(49, "Chelsea", "CHE", null),
                        Team(33, "Manchester United", "MUN", null),
                    ),
                ),
                selectedTeams = mapOf(42 to Team(42, "Arsenal", "ARS", null)),
            ),
            onClose = {},
            onName = {},
            onToggleLeague = {},
            onRetryLeagues = {},
            onPickTeams = {},
            onToggleTeam = {},
            onRemoveTeam = {},
            onSearch = {},
            onRetrySquads = {},
            onDismissNotice = {},
            onSave = {},
        )
    }
}
