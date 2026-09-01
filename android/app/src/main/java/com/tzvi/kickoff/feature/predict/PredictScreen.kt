package com.tzvi.kickoff.feature.predict

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.core.model.GroupFixture
import com.tzvi.kickoff.feature.auth.AccountRequired
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.LoadingState
import com.tzvi.kickoff.ui.component.SectionHeader
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * The guessing game: a group, its fixtures, its table and its chat.
 *
 * The one screen worth opening when there is no match on - everywhere else in the app is
 * reporting what happened, and this is the only place the user has a stake in it.
 */
@Composable
fun PredictScreen(onSignIn: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val viewModel: PredictViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PredictContent(
        state = state,
        onRefresh = viewModel::refresh,
        onSelectGroup = viewModel::selectGroup,
        onSelectTab = viewModel::selectTab,
        onAdjust = viewModel::adjust,
        onSubmit = viewModel::submit,
        onCreate = viewModel::createGroup,
        onJoin = viewModel::joinGroup,
        onSendChat = viewModel::sendChat,
        onSignIn = onSignIn,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PredictContent(
    state: PredictUiState,
    onRefresh: () -> Unit,
    onSelectGroup: (Long) -> Unit,
    onSelectTab: (PredictTab) -> Unit,
    onAdjust: (Long, Int, Int) -> Unit,
    onSubmit: (Long) -> Unit,
    onCreate: (String, List<Int>, List<Int>) -> Unit,
    onJoin: (String) -> Unit,
    onSendChat: (String) -> Unit,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(state.selected?.name ?: "Predictions") },
                actions = {
                    val group = state.selected
                    if (group != null) InviteAction(code = group.inviteCode)
                },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            when {
                state.isLoading -> LoadingState(label = "Loading your groups")

                state.blocker == PredictBlocker.NEEDS_ACCOUNT -> AccountRequired(
                    title = "Sign in to play",
                    body = "Guessing against other people needs an account, so nobody's " +
                        "call can be seen before kick-off and everybody's survives a " +
                        "reinstall. Everything else in matchUP works without one.",
                    onAction = onSignIn,
                    modifier = Modifier.fillMaxSize(),
                )

                state.blocker == PredictBlocker.NEEDS_SERVER -> EmptyState(
                    title = "The game needs the server",
                    body = "You're set to talk to API-Football directly, which has " +
                        "nowhere to keep anybody else's guesses. Switch back to the " +
                        "matchUP server in Settings to play.",
                    icon = Icons.Outlined.CloudOff,
                    actionLabel = "Open Settings",
                    onAction = onOpenSettings,
                    modifier = Modifier.fillMaxSize(),
                )

                state.blocker == PredictBlocker.NO_GROUPS -> StartGroup(
                    creating = state.creating,
                    joining = state.joining,
                    errorMessage = state.errorMessage,
                    onCreate = { name -> onCreate(name, emptyList(), emptyList()) },
                    onJoin = onJoin,
                )

                else -> {
                    if (state.groups.size > 1) {
                        GroupSwitcher(state = state, onSelectGroup = onSelectGroup)
                    }
                    PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                        PredictTab.entries.forEach { entry ->
                            Tab(
                                selected = entry == state.tab,
                                onClick = { onSelectTab(entry) },
                                text = { Text(entry.label) },
                            )
                        }
                    }
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when (state.tab) {
                            PredictTab.FIXTURES -> FixturesTab(state, onAdjust, onSubmit)
                            PredictTab.TABLE -> TableTab(state)
                            PredictTab.CHAT -> ChatTab(state, onSendChat)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteAction(code: String) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            // A share sheet rather than a copy-to-clipboard: the code is useless on its
            // own and the sentence around it is what makes a friend able to act on it.
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Join my matchUP predictions group with the code $code.",
                )
            }
            context.startActivity(Intent.createChooser(share, "Invite a friend"))
        },
    ) {
        Text("Invite")
    }
}

@Composable
private fun GroupSwitcher(state: PredictUiState, onSelectGroup: (Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.groups.forEach { group ->
            AssistChip(
                onClick = { onSelectGroup(group.id) },
                label = { Text(group.name) },
                leadingIcon = if (group.id == state.selected?.id) {
                    { Icon(Icons.Outlined.Group, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun FixturesTab(
    state: PredictUiState,
    onAdjust: (Long, Int, Int) -> Unit,
    onSubmit: (Long) -> Unit,
) {
    val open = state.openFixtures
    val settled = state.settledFixtures
    if (open.isEmpty() && settled.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                EmptyState(
                    title = "No matches to guess yet",
                    body = "This group's teams have nothing scheduled. Pull down once " +
                        "the fixtures are out.",
                    icon = Icons.Outlined.Group,
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = 8.dp,
            bottom = 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (open.isNotEmpty()) {
            item(key = "open-header") { SectionHeader(title = "Open") }
            items(open, key = { it.matchId }) { fixture ->
                GuessCard(
                    fixture = fixture,
                    draft = state.draftFor(fixture),
                    isDirty = state.isDirty(fixture),
                    isSaving = fixture.matchId in state.saving,
                    onAdjust = { home, away -> onAdjust(fixture.matchId, home, away) },
                    onSubmit = { onSubmit(fixture.matchId) },
                )
            }
        }
        if (settled.isNotEmpty()) {
            item(key = "settled-header") { SectionHeader(title = "Locked") }
            items(settled, key = { it.matchId }) { fixture ->
                GuessCard(
                    fixture = fixture,
                    draft = state.draftFor(fixture),
                    isDirty = false,
                    isSaving = false,
                    onAdjust = { _, _ -> },
                    onSubmit = {},
                )
            }
        }
    }
}

@Composable
private fun TableTab(state: PredictUiState) {
    val live = state.liveFixture
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = 8.dp,
            bottom = 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The match in play goes above the table, because during a match the table is
        // only interesting in relation to it.
        if (live != null) {
            item(key = "live") {
                LiveHeader(fixture = live)
            }
        }
        if (state.members.isEmpty()) {
            item(key = "table-empty") {
                EmptyState(
                    title = "Nothing settled yet",
                    body = "Points land when a match finishes: 3 for the exact score, " +
                        "1 for calling it the right way.",
                    icon = Icons.Outlined.Group,
                )
            }
        } else {
            itemsIndexed(state.members, key = { _, m -> m.userId }) { index, member ->
                LeaderboardRow(
                    position = index + 1,
                    member = member,
                    predicted = live?.let { fixture ->
                        (listOfNotNull(fixture.myPrediction) + fixture.others)
                            .firstOrNull { it.userId == member.userId }
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveHeader(fixture: GroupFixture) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(
            text = fixture.match.leagueName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${fixture.match.home.name} ${fixture.actualScoreLabel() ?: ""} ${fixture.match.away.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = fixture.statusLabel(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ChatTab(state: PredictUiState, onSend: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 8.dp,
                bottom = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.chat.isEmpty()) {
                item(key = "chat-empty") {
                    EmptyState(
                        title = "Nothing said yet",
                        body = "Messages here are kept for a day and then gone.",
                        icon = Icons.Outlined.Group,
                    )
                }
            }
            items(state.chat, key = { it.id }) { message ->
                Row(verticalAlignment = Alignment.Top) {
                    Avatar(name = message.displayName, url = message.avatarUrl)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(
                                text = message.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = message.sentAt.chatTime(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(CHAT_LIMIT) },
                placeholder = { Text("Say something") },
                singleLine = true,
                keyboardActions = KeyboardActions(onSend = { }),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    onSend(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/** The first thing anyone sees: make one, or join the one a friend already made. */
@Composable
private fun StartGroup(
    creating: Boolean,
    joining: Boolean,
    errorMessage: String?,
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Guess the scores",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Pick your teams, call every score before kick-off, and see who was " +
                "closest. Three points for the exact score, one for calling it right.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Group name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onCreate(name) },
            enabled = name.isNotBlank() && !creating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (creating) "Creating" else "Create a group")
        }
        Spacer(Modifier.height(26.dp))
        Text(
            text = "Got a code from a friend?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Invite code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { onJoin(code) },
            enabled = code.isNotBlank() && !joining,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (joining) "Joining" else "Join")
        }
        if (errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private const val CHAT_LIMIT = 500
private val ScreenPadding = 16.dp

@Preview(name = "Predictions - start", heightDp = 800)
@Composable
private fun PredictStartPreview() {
    KickoffTheme {
        PredictContent(
            state = PredictUiState(isLoading = false, blocker = PredictBlocker.NO_GROUPS),
            onRefresh = {},
            onSelectGroup = {},
            onSelectTab = {},
            onAdjust = { _, _, _ -> },
            onSubmit = {},
            onCreate = { _, _, _ -> },
            onJoin = {},
            onSendChat = {},
            onSignIn = {},
            onOpenSettings = {},
        )
    }
}
