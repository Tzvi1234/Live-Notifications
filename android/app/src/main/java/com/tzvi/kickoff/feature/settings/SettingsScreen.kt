package com.tzvi.kickoff.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.tzvi.kickoff.ui.component.Avatar
import com.tzvi.kickoff.ui.component.AvatarDefaults
import com.tzvi.kickoff.ui.theme.KickoffShapes
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.core.model.AppSettings
import com.tzvi.kickoff.core.model.LiveCardStyle
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.LoadingState
import com.tzvi.kickoff.ui.motion.TransformKeys
import com.tzvi.kickoff.ui.motion.containerTransform
import com.tzvi.kickoff.ui.theme.KickoffTheme

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenProfile: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Promotion, notifications and the overlay grant are all changed on system screens we
    // hand the user off to, so they are re-read on every resume. That, rather than an
    // activity result, is what makes a change made outside the app show up here.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshDeviceState()
        onPauseOrDispose { }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onNotificationPermissionResult(granted) }

    SettingsContent(
        state = state,
        onBack = onBack,
        onOpenProfile = onOpenProfile,
        onSelectLiveCardStyle = viewModel::setLiveCardStyle,
        onOpenPromotionSettings = {
            viewModel.promotionSettingsIntent()?.let { intent ->
                runCatching { context.startActivity(intent) }
            }
        },
        onPreviewCard = viewModel::previewLiveCard,
        onDismissPreview = viewModel::dismissLiveCardPreview,
        onSetDemoMode = viewModel::setDemoMode,
        onDemoPreMatch = viewModel::showPreMatchCard,
        onDemoLive = viewModel::showLiveCard,
        onDemoFullTime = viewModel::showFullTimeCard,
        onToggleSimulation = viewModel::toggleSimulation,
        onSetGoals = viewModel::setNotifyGoals,
        onSetCards = viewModel::setNotifyCards,
        onSetSubstitutions = viewModel::setNotifySubstitutions,
        onSetKickoffAndFullTime = viewModel::setNotifyKickoffAndFullTime,
        onSetLineups = viewModel::setNotifyLineups,
        onSetLeadMinutes = viewModel::setPreMatchLeadMinutes,
        // Fired only from the button in the alerts group. Asking on cold start would spend
        // the single system dialog on a user who never went looking for notifications, and
        // a second denial silences it for good.
        onRequestNotifications = {
            if (state.notifications.requestSpent) {
                runCatching { context.startActivity(viewModel.notificationSettingsIntent()) }
            } else {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onApiKeyChange = viewModel::onApiKeyChange,
        onToggleApiKeyVisibility = viewModel::toggleApiKeyVisibility,
        onSaveApiKey = viewModel::saveApiKey,
        onBackendUrlChange = viewModel::onBackendUrlChange,
        onSaveBackendUrl = viewModel::saveBackendUrl,
        onSetPushEnabled = viewModel::setPushEnabled,
        onSelectTheme = viewModel::setDarkTheme,
        onSetDynamicColor = viewModel::setDynamicColor,
        onRetry = viewModel::retry,
        onDismissMessage = viewModel::dismissMessage,
        onEraseEverything = viewModel::eraseEverything,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onSelectLiveCardStyle: (LiveCardStyle) -> Unit,
    onOpenPromotionSettings: () -> Unit,
    onPreviewCard: () -> Unit,
    onDismissPreview: () -> Unit,
    onSetDemoMode: (Boolean) -> Unit,
    onDemoPreMatch: () -> Unit,
    onDemoLive: () -> Unit,
    onDemoFullTime: () -> Unit,
    onToggleSimulation: () -> Unit,
    onSetGoals: (Boolean) -> Unit,
    onSetCards: (Boolean) -> Unit,
    onSetSubstitutions: (Boolean) -> Unit,
    onSetKickoffAndFullTime: (Boolean) -> Unit,
    onSetLineups: (Boolean) -> Unit,
    onSetLeadMinutes: (Int) -> Unit,
    onRequestNotifications: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onSaveApiKey: () -> Unit,
    onBackendUrlChange: (String) -> Unit,
    onSaveBackendUrl: () -> Unit,
    onSetPushEnabled: (Boolean) -> Unit,
    onSelectTheme: (AppSettings.DarkThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
    onEraseEverything: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    var openCard by rememberSaveable { mutableStateOf<SettingsCardId?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissMessage()
    }

    Scaffold(
        // The settings button on Today hands its bounds over, so this screen grows out of
        // that button instead of sliding in from the edge.
        modifier = Modifier
            .fillMaxSize()
            .containerTransform(TransformKeys.SETTINGS)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        when {
            state.isLoading -> LoadingState(
                modifier = Modifier.padding(insets),
                label = "Reading your settings",
            )

            state.errorMessage != null -> EmptyState(
                title = "Your settings could not be read",
                body = "${state.errorMessage} Nothing has been lost - the file is still " +
                    "there, and reopening this screen usually reads it fine.",
                icon = Icons.Outlined.Settings,
                actionLabel = "Try again",
                onAction = onRetry,
                modifier = Modifier.padding(insets),
            )

            // A plain scrolling column rather than a lazy list: the two text fields must
            // keep their focus and their contents when the group scrolls out of view.
            // One card open at a time. Seven sections expanded at once is what made this
            // screen a wall; an accordion keeps the whole list of what matchUP can do on
            // one screenful, and opens only the thing you came for.
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(SectionGap),
            ) {
                // The person, before the settings. Every professional app that has an
                // account puts the account at the top - it is the one row on the screen
                // that answers "whose app is this", and burying it three cards down under
                // "Account" made it look like a setting rather than like you.
                ProfileHeader(
                    profile = state.profile,
                    onOpenProfile = onOpenProfile,
                )

                val toggle: (SettingsCardId) -> Unit = { id ->
                    openCard = if (openCard == id) null else id
                }

                DataSourceSection(
                    form = state.dataSource,
                    pushEnabled = state.settings.pushEnabled,
                    expanded = openCard == SettingsCardId.DATA_SOURCE,
                    onToggle = { toggle(SettingsCardId.DATA_SOURCE) },
                    onApiKeyChange = onApiKeyChange,
                    onToggleApiKeyVisibility = onToggleApiKeyVisibility,
                    onSaveApiKey = onSaveApiKey,
                    onBackendUrlChange = onBackendUrlChange,
                    onSaveBackendUrl = onSaveBackendUrl,
                    onSetPushEnabled = onSetPushEnabled,
                )
                LiveCardSection(
                    style = state.settings.liveCardStyle,
                    status = state.liveUpdate,
                    expanded = openCard == SettingsCardId.LIVE_CARD,
                    onToggle = { toggle(SettingsCardId.LIVE_CARD) },
                    onSelectStyle = onSelectLiveCardStyle,
                    onOpenPromotionSettings = onOpenPromotionSettings,
                    onPreviewCard = onPreviewCard,
                    onDismissPreview = onDismissPreview,
                )
                AlertsSection(
                    settings = state.settings,
                    access = state.notifications,
                    expanded = openCard == SettingsCardId.ALERTS,
                    onToggle = { toggle(SettingsCardId.ALERTS) },
                    onSetGoals = onSetGoals,
                    onSetCards = onSetCards,
                    onSetSubstitutions = onSetSubstitutions,
                    onSetKickoffAndFullTime = onSetKickoffAndFullTime,
                    onSetLineups = onSetLineups,
                    onSetLeadMinutes = onSetLeadMinutes,
                    onRequestNotifications = onRequestNotifications,
                )
                DemoSection(
                    demo = state.demo,
                    expanded = openCard == SettingsCardId.DEMO,
                    onToggle = { toggle(SettingsCardId.DEMO) },
                    onSetDemoMode = onSetDemoMode,
                    onPreMatch = onDemoPreMatch,
                    onLive = onDemoLive,
                    onFullTime = onDemoFullTime,
                    onToggleSimulation = onToggleSimulation,
                )
                AppearanceSection(
                    settings = state.settings,
                    dynamicColorAvailable = state.dynamicColorAvailable,
                    expanded = openCard == SettingsCardId.APPEARANCE,
                    onToggle = { toggle(SettingsCardId.APPEARANCE) },
                    onSelectTheme = onSelectTheme,
                    onSetDynamicColor = onSetDynamicColor,
                )
                AccountSection(
                    expanded = openCard == SettingsCardId.ACCOUNT,
                    onToggle = { toggle(SettingsCardId.ACCOUNT) },
                    onOpenProfile = onOpenProfile,
                )
                AboutSection(
                    version = state.appVersion,
                    expanded = openCard == SettingsCardId.ABOUT,
                    onToggle = { toggle(SettingsCardId.ABOUT) },
                    onEraseEverything = onEraseEverything,
                )
                Spacer(Modifier.height(BottomPadding))
            }
        }
    }
}

/** Identifies which card the accordion currently has open. */
internal enum class SettingsCardId {
    DATA_SOURCE, LIVE_CARD, ALERTS, DEMO, APPEARANCE, ACCOUNT, ABOUT
}

private val ScreenPadding = 16.dp
private val SectionGap = 10.dp
private val BottomPadding = 32.dp

// ---- previews -----------------------------------------------------------------------

@Composable
private fun SettingsPreview(state: SettingsUiState) {
    KickoffTheme {
        SettingsContent(
            state = state,
            onBack = {},
            onOpenProfile = {},
            onSelectLiveCardStyle = {},
            onOpenPromotionSettings = {},
            onPreviewCard = {},
            onDismissPreview = {},
            onSetDemoMode = {},
            onDemoPreMatch = {},
            onDemoLive = {},
            onDemoFullTime = {},
            onToggleSimulation = {},
            onSetGoals = {},
            onSetCards = {},
            onSetSubstitutions = {},
            onSetKickoffAndFullTime = {},
            onSetLineups = {},
            onSetLeadMinutes = {},
            onRequestNotifications = {},
            onApiKeyChange = {},
            onToggleApiKeyVisibility = {},
            onSaveApiKey = {},
            onBackendUrlChange = {},
            onSaveBackendUrl = {},
            onSetPushEnabled = {},
            onSelectTheme = {},
            onSetDynamicColor = {},
            onRetry = {},
            onDismissMessage = {},
        )
    }
}

private fun previewState(
    isLoading: Boolean = false,
    errorMessage: String? = null,
    liveUpdate: LiveUpdateStatus = LiveUpdateStatus(
        supportsProgressStyle = true,
        supportsPromotion = true,
        promotionAllowed = true,
        canOpenPromotionSettings = true,
    ),
    notifications: NotificationAccess = NotificationAccess(granted = true),
    dataSource: DataSourceForm = DataSourceForm(
        apiKeyInput = "0123456789abcdef0123456789abcdef",
        apiKeyStored = true,
        backendUrlInput = "https://kickoff.onrender.com",
        backendUrlStored = true,
        activeSourceName = "matchUP backend",
    ),
) = SettingsUiState(
    isLoading = isLoading,
    errorMessage = errorMessage,
    settings = AppSettings(),
    liveUpdate = liveUpdate,
    notifications = notifications,
    dataSource = dataSource,
    appVersion = "1.0.0 (1)",
)

@Preview(name = "Settings - everything on", heightDp = 1800)
@Composable
private fun SettingsContentPreview() {
    SettingsPreview(previewState())
}

@Preview(name = "Settings - promotion off", heightDp = 1800)
@Composable
private fun SettingsPromotionOffPreview() {
    SettingsPreview(
        previewState(
            liveUpdate = LiveUpdateStatus(
                supportsProgressStyle = true,
                supportsPromotion = true,
                promotionAllowed = false,
                canOpenPromotionSettings = true,
            ),
            notifications = NotificationAccess(granted = false),
        ),
    )
}

@Preview(name = "Settings - no source", heightDp = 1800)
@Composable
private fun SettingsNoSourcePreview() {
    SettingsPreview(previewState(dataSource = DataSourceForm()))
}

@Preview(name = "Settings - loading", heightDp = 700)
@Composable
private fun SettingsLoadingPreview() {
    SettingsPreview(previewState(isLoading = true))
}

@Preview(name = "Settings - unreadable", heightDp = 700)
@Composable
private fun SettingsErrorPreview() {
    SettingsPreview(
        previewState(errorMessage = "Your preferences could not be read from storage."),
    )
}

/**
 * Who you are, large, above the cards.
 *
 * Two states and they are genuinely different, so the header is not one layout with a
 * blank in it. Signed in, it is a picture, a name and the address, and tapping it edits
 * them. Signed out, it is an offer - because a header that shows an empty circle and no
 * name reads as a bug rather than as an invitation.
 */
@Composable
private fun ProfileHeader(
    profile: ProfileSummary,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onOpenProfile,
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(
                name = profile.label,
                url = profile.avatarUrl,
                size = AvatarDefaults.large,
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (profile.signedIn) profile.label else "Sign in to matchUP",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (profile.signedIn) {
                        profile.subtitle.ifBlank { "Tap to add your name and picture" }
                    } else {
                        "Carry your teams and predictions between devices"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
