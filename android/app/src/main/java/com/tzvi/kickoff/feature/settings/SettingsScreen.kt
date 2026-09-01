package com.tzvi.kickoff.feature.settings

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
import androidx.compose.runtime.remember
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
fun SettingsScreen(onBack: () -> Unit, onCalibrateCutout: () -> Unit) {
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
        onSetFloatingIsland = viewModel::setFloatingIslandEnabled,
        onGrantOverlayPermission = {
            runCatching { context.startActivity(viewModel.overlayPermissionIntent()) }
        },
        onCalibrateCutout = onCalibrateCutout,
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
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
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
    onSetFloatingIsland: (Boolean) -> Unit,
    onGrantOverlayPermission: () -> Unit,
    onCalibrateCutout: () -> Unit,
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
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

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
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(SectionGap),
            ) {
                LiveCardSection(
                    style = state.settings.liveCardStyle,
                    status = state.liveUpdate,
                    onSelectStyle = onSelectLiveCardStyle,
                    onOpenPromotionSettings = onOpenPromotionSettings,
                    onPreviewCard = onPreviewCard,
                    onDismissPreview = onDismissPreview,
                )
                DemoSection(
                    demo = state.demo,
                    onSetDemoMode = onSetDemoMode,
                    onPreMatch = onDemoPreMatch,
                    onLive = onDemoLive,
                    onFullTime = onDemoFullTime,
                    onToggleSimulation = onToggleSimulation,
                )
                AlertsSection(
                    settings = state.settings,
                    access = state.notifications,
                    onSetGoals = onSetGoals,
                    onSetCards = onSetCards,
                    onSetSubstitutions = onSetSubstitutions,
                    onSetKickoffAndFullTime = onSetKickoffAndFullTime,
                    onSetLineups = onSetLineups,
                    onSetLeadMinutes = onSetLeadMinutes,
                    onRequestNotifications = onRequestNotifications,
                )
                IslandSection(
                    status = state.island,
                    cutout = state.islandCutout,
                    onSetEnabled = onSetFloatingIsland,
                    onGrantOverlayPermission = onGrantOverlayPermission,
                    onCalibrateCutout = onCalibrateCutout,
                )
                DataSourceSection(
                    form = state.dataSource,
                    pushEnabled = state.settings.pushEnabled,
                    onApiKeyChange = onApiKeyChange,
                    onToggleApiKeyVisibility = onToggleApiKeyVisibility,
                    onSaveApiKey = onSaveApiKey,
                    onBackendUrlChange = onBackendUrlChange,
                    onSaveBackendUrl = onSaveBackendUrl,
                    onSetPushEnabled = onSetPushEnabled,
                )
                AppearanceSection(
                    settings = state.settings,
                    dynamicColorAvailable = state.dynamicColorAvailable,
                    onSelectTheme = onSelectTheme,
                    onSetDynamicColor = onSetDynamicColor,
                )
                AboutSection(version = state.appVersion)
                Spacer(Modifier.height(BottomPadding))
            }
        }
    }
}

private val ScreenPadding = 16.dp
private val SectionGap = 20.dp
private val BottomPadding = 32.dp

// ---- previews -----------------------------------------------------------------------

@Composable
private fun SettingsPreview(state: SettingsUiState) {
    KickoffTheme {
        SettingsContent(
            state = state,
            onBack = {},
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
            onSetFloatingIsland = {},
            onGrantOverlayPermission = {},
            onCalibrateCutout = {},
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
    island: IslandStatus = IslandStatus(overlayPermissionGranted = true, floatingEnabled = true),
    dataSource: DataSourceForm = DataSourceForm(
        apiKeyInput = "0123456789abcdef0123456789abcdef",
        apiKeyStored = true,
        backendUrlInput = "https://kickoff.onrender.com",
        backendUrlStored = true,
        activeSourceName = "Kickoff backend",
    ),
) = SettingsUiState(
    isLoading = isLoading,
    errorMessage = errorMessage,
    settings = AppSettings(),
    liveUpdate = liveUpdate,
    notifications = notifications,
    island = island,
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
            island = IslandStatus(overlayPermissionGranted = false, floatingEnabled = false),
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
