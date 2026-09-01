package com.tzvi.kickoff.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.BuildConfig
import com.tzvi.kickoff.core.model.AppSettings
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.core.model.LiveCardStyle
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.SettingsRepository
import com.tzvi.kickoff.data.demo.DemoCatalogue
import com.tzvi.kickoff.notifications.LiveCardPreview
import com.tzvi.kickoff.notifications.MatchSimulator
import com.tzvi.kickoff.notifications.LiveUpdateCapability
import com.tzvi.kickoff.notifications.MatchNotificationBuilder
import com.tzvi.kickoff.ui.island.IslandOverlayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.URI
import javax.inject.Inject

/**
 * Every toggle writes straight through to [SettingsRepository]; there is no save button
 * and no draft copy of the preferences, so what is on screen is what is stored.
 *
 * The two text fields are the exception: a key or a URL is only meaningful once it is
 * complete, so those are held as edits until they are explicitly saved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val footballRepository: FootballRepository,
    private val capability: LiveUpdateCapability,
    private val liveCardPreview: LiveCardPreview,
    private val simulator: MatchSimulator,
) : ViewModel() {

    /**
     * Resolving the promotion-settings activity is a PackageManager round trip, and the
     * answer cannot change while this process lives, so it is paid for once rather than on
     * every resume and every tap.
     *
     * Declared first: [session] reads it while the constructor is still running.
     */
    private val promotionSettings: Intent? by lazy { capability.promotionSettingsIntent() }

    private val reloads = MutableStateFlow(0)
    private val session = MutableStateFlow(readSession())
    private val editor = MutableStateFlow(Editor())

    // Shared rather than cold: this is collected both directly and through
    // [activeSourceName], and a second collection would open a second read of every
    // DataStore key behind it.
    private val stored: Flow<Stored> = reloads.flatMapLatest {
        combine(
            settingsRepository.settings,
            settingsRepository.apiFootballKey,
            settingsRepository.backendUrl,
        ) { settings, key, url -> Stored.Loaded(settings, key, url) as Stored }
            .catch { error -> emit(Stored.Failed(error.userMessage())) }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), replay = 1)

    // sourceName() resolves the provider from the stored key and URL, so it is recomputed
    // when either of those changes and not when some unrelated switch is flipped.
    private val activeSourceName: Flow<String> = stored
        .map { current -> (current as? Stored.Loaded)?.let { it.apiKey to it.backendUrl } }
        .distinctUntilChanged()
        .mapLatest { credentials ->
            if (credentials == null) DataSourceForm.NO_SOURCE else footballRepository.sourceName()
        }

    /** Demo mode and the simulator's progress, folded into one value for the panel. */
    private val demo: Flow<DemoStatus> = combine(
        settingsRepository.demoMode,
        simulator.state,
    ) { enabled, run ->
        DemoStatus(
            enabled = enabled,
            simulating = run.running,
            minute = run.minute,
            scoreLabel = "${run.score.home} – ${run.score.away}",
            lastEvent = run.lastEvent,
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        stored,
        activeSourceName,
        session,
        editor,
        demo,
    ) { current, sourceName, device, form, demoStatus ->
        val loaded = current as? Stored.Loaded
        SettingsUiState(
            isLoading = false,
            errorMessage = (current as? Stored.Failed)?.message,
            settings = loaded?.settings ?: AppSettings(),
            liveUpdate = device.liveUpdate,
            demo = demoStatus,
            notifications = device.notifications,
            island = IslandStatus(
                overlayPermissionGranted = device.overlayPermissionGranted,
                floatingEnabled = device.floatingIslandEnabled,
            ),
            dataSource = DataSourceForm(
                apiKeyInput = form.apiKeyInput ?: loaded?.apiKey.orEmpty(),
                apiKeyStored = !loaded?.apiKey.isNullOrBlank(),
                apiKeyRevealed = form.apiKeyRevealed,
                backendUrlInput = form.backendUrlInput ?: loaded?.backendUrl.orEmpty(),
                backendUrlStored = !loaded?.backendUrl.isNullOrBlank(),
                backendUrlError = form.backendUrlError,
                activeSourceName = sourceName,
            ),
            appVersion = APP_VERSION,
            dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            message = form.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), SettingsUiState())

    fun retry() {
        reloads.update { it + 1 }
    }

    /**
     * Re-reads everything the platform owns rather than the app.
     *
     * Promotion, notifications and the overlay grant are all changed in system settings,
     * which means they can differ from what this screen last drew; the screen calls this
     * on every resume and after returning from any of those settings pages.
     */
    fun refreshDeviceState() {
        session.update { readSession(it) }
    }

    // ---- live card -----------------------------------------------------------

    fun setLiveCardStyle(style: LiveCardStyle) = write { it.setLiveCardStyle(style) }

    // ---- alerts --------------------------------------------------------------

    fun setNotifyGoals(value: Boolean) = write { it.setNotifyGoals(value) }
    fun setNotifyCards(value: Boolean) = write { it.setNotifyCards(value) }
    fun setNotifySubstitutions(value: Boolean) = write { it.setNotifySubstitutions(value) }
    fun setNotifyKickoffAndFullTime(value: Boolean) = write { it.setNotifyKickoffAndFullTime(value) }
    fun setNotifyLineups(value: Boolean) = write { it.setNotifyLineups(value) }

    fun setPreMatchLeadMinutes(minutes: Int) = write {
        it.setPreMatchLeadMinutes(minutes.coerceIn(PRE_MATCH_LEAD_MIN, PRE_MATCH_LEAD_MAX))
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        // A second denial silences the dialog for good, so once one has been spent the UI
        // has to offer the settings screen instead of asking again.
        session.update {
            it.copy(notifications = NotificationAccess(granted = granted, requestSpent = !granted))
        }
    }

    // ---- dynamic island ------------------------------------------------------

    fun setFloatingIslandEnabled(enabled: Boolean) {
        val granted = IslandOverlayService.canDrawOverlay(context)
        if (enabled && !granted) {
            session.update {
                it.copy(overlayPermissionGranted = false, floatingIslandEnabled = false)
            }
            return
        }
        if (enabled) IslandOverlayService.show(context) else IslandOverlayService.hide(context)
        session.update {
            it.copy(overlayPermissionGranted = granted, floatingIslandEnabled = enabled)
        }
    }

    // ---- data source ---------------------------------------------------------

    fun onApiKeyChange(value: String) {
        editor.update { it.copy(apiKeyInput = value, message = null) }
    }

    fun toggleApiKeyVisibility() {
        editor.update { it.copy(apiKeyRevealed = !it.apiKeyRevealed) }
    }

    fun saveApiKey() {
        viewModelScope.launch {
            val value = (editor.value.apiKeyInput ?: settingsRepository.apiFootballKey.first()).trim()
            settingsRepository.setApiFootballKey(value)
            editor.update {
                it.copy(
                    apiKeyInput = null,
                    message = if (value.isBlank()) "API key cleared." else "API key saved.",
                )
            }
        }
    }

    fun onBackendUrlChange(value: String) {
        editor.update { it.copy(backendUrlInput = value, backendUrlError = null, message = null) }
    }

    fun saveBackendUrl() {
        viewModelScope.launch {
            val raw = (editor.value.backendUrlInput ?: settingsRepository.backendUrl.first()).trim()
            if (raw.isBlank()) {
                settingsRepository.setBackendUrl("")
                editor.update {
                    it.copy(backendUrlInput = null, backendUrlError = null, message = "Backend cleared.")
                }
                return@launch
            }
            val normalised = normaliseBackendUrl(raw)
            if (normalised == null) {
                editor.update { it.copy(backendUrlError = INVALID_URL_MESSAGE, message = null) }
                return@launch
            }
            settingsRepository.setBackendUrl(normalised)
            editor.update {
                it.copy(backendUrlInput = null, backendUrlError = null, message = "Backend saved.")
            }
        }
    }

    /**
     * Posts a fabricated match card so the setting above can be judged on this device
     * rather than in the abstract.
     *
     * Promotion has nine independent conditions and fails silently, so the result is read
     * back from the posted notification and reported verbatim: whether the system actually
     * promoted the card is the only honest answer to "will this show on my lock screen".
     */
    fun previewLiveCard() {
        viewModelScope.launch {
            val style = uiState.value.settings.liveCardStyle
            val rendering = runCatching { liveCardPreview.show(style) }.getOrNull()
            editor.update { it.copy(message = previewMessage(rendering)) }
        }
    }

    fun dismissLiveCardPreview() {
        liveCardPreview.hide()
        editor.update { it.copy(message = null) }
    }

    private fun previewMessage(rendering: MatchNotificationBuilder.Rendering?): String =
        when (rendering) {
            MatchNotificationBuilder.Rendering.PROMOTED ->
                "Posted as a Live Update - check the status bar and lock screen."
            MatchNotificationBuilder.Rendering.RICH ->
                "Posted as the rich scoreboard. It will not appear in the status bar or on the always-on display."
            MatchNotificationBuilder.Rendering.PLAIN ->
                "Posted using the plain system template."
            // A refused post and a post throttled behind the previous one are
            // indistinguishable from here, so the copy has to cover both.
            null ->
                "Nothing was posted. Check that notifications are allowed for Kickoff, " +
                    "and give the last card a couple of seconds before asking again."
        }

    // ---- demo ----------------------------------------------------------------

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) simulator.stop()
            settingsRepository.setDemoMode(enabled)
            if (enabled) seedDemoFavourites()
            reloads.update { it + 1 }
            editor.update {
                it.copy(
                    message = if (enabled) {
                        "Demo data is on. Fixtures, teams and the live card are generated."
                    } else {
                        "Demo data is off."
                    },
                )
            }
        }
    }

    /**
     * Gives the demo something to be about. Without a follow list every screen is a valid
     * but empty state, which is the one thing a demo must not open on.
     */
    private suspend fun seedDemoFavourites() {
        if (footballRepository.favouriteIdsNow().isNotEmpty()) return
        footballRepository.setFavourites(
            listOf(
                DemoCatalogue.arsenal to DemoCatalogue.premierLeague,
                DemoCatalogue.barcelona to DemoCatalogue.laLiga,
                DemoCatalogue.liverpool to DemoCatalogue.premierLeague,
                DemoCatalogue.bayern to DemoCatalogue.bundesliga,
            ),
        )
    }

    fun toggleSimulation() {
        if (uiState.value.demo.simulating) simulator.stop() else simulator.start()
    }

    fun showPreMatchCard() = preview(LiveActivity.MatchActivity.Stage.PRE_MATCH)
    fun showLiveCard() = preview(LiveActivity.MatchActivity.Stage.LIVE)
    fun showFullTimeCard() = preview(LiveActivity.MatchActivity.Stage.FULL_TIME)

    private fun preview(stage: LiveActivity.MatchActivity.Stage) {
        viewModelScope.launch {
            val style = uiState.value.settings.liveCardStyle
            val rendering = runCatching { liveCardPreview.show(style, stage) }.getOrNull()
            editor.update { it.copy(message = previewMessage(rendering)) }
        }
    }

    fun dismissMessage() {
        editor.update { it.copy(message = null) }
    }

    // ---- appearance ----------------------------------------------------------

    fun setDarkTheme(preference: AppSettings.DarkThemePreference) =
        write { it.setDarkTheme(preference) }

    fun setDynamicColor(value: Boolean) = write { it.setDynamicColor(value) }

    // ---- push ----------------------------------------------------------------

    fun setPushEnabled(value: Boolean) = write { it.setPushEnabled(value) }

    // ---- intents the screen launches ----------------------------------------

    /** Null when this build has no promoted-notification settings activity to open. */
    fun promotionSettingsIntent(): Intent? = promotionSettings

    fun overlayPermissionIntent(): Intent = IslandOverlayService.permissionIntent(context)

    fun notificationSettingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- internals -----------------------------------------------------------

    /**
     * The repository is passed in rather than made the lambda receiver: several of these
     * setters share a name with the one on this ViewModel, and an implicit receiver would
     * make a missing repository method compile into silent recursion.
     */
    private fun write(block: suspend (SettingsRepository) -> Unit) {
        viewModelScope.launch { block(settingsRepository) }
    }

    private fun readSession(previous: Session? = null): Session {
        val overlayGranted = IslandOverlayService.canDrawOverlay(context)
        return Session(
            liveUpdate = LiveUpdateStatus(
                supportsProgressStyle = capability.supportsProgressStyle,
                supportsPromotion = capability.supportsPromotion,
                promotionAllowed = capability.canPostPromoted(),
                canOpenPromotionSettings = promotionSettings != null,
            ),
            notifications = NotificationAccess(
                granted = hasNotificationPermission(),
                requestSpent = previous?.notifications?.requestSpent == true,
            ),
            overlayPermissionGranted = overlayGranted,
            // Losing the grant while the app was away also takes the overlay down.
            floatingIslandEnabled = previous?.floatingIslandEnabled == true && overlayGranted,
        )
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun Throwable.userMessage(): String = when (this) {
        is IOException -> "Your preferences could not be read from storage."
        else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong loading settings."
    }

    private sealed interface Stored {
        data class Loaded(
            val settings: AppSettings,
            val apiKey: String,
            val backendUrl: String,
        ) : Stored

        data class Failed(val message: String) : Stored
    }

    /** Everything the platform owns, plus the one toggle that has nowhere to be persisted. */
    private data class Session(
        val liveUpdate: LiveUpdateStatus,
        val notifications: NotificationAccess,
        val overlayPermissionGranted: Boolean,
        val floatingIslandEnabled: Boolean,
    )

    private data class Editor(
        /** Null means "show whatever is stored"; saving resets the field to that. */
        val apiKeyInput: String? = null,
        val apiKeyRevealed: Boolean = false,
        val backendUrlInput: String? = null,
        val backendUrlError: String? = null,
        val message: String? = null,
    )

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        const val INVALID_URL_MESSAGE =
            "That does not look like a URL. Try https://your-app.onrender.com"
        val APP_VERSION = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }
}

private val ALLOWED_SCHEMES = setOf("http", "https")

private fun normaliseBackendUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in ALLOWED_SCHEMES) return null
    val host = uri.host ?: return null
    // A single-label host is almost always a typo; localhost is the one real exception.
    if (!host.contains('.') && !host.equals("localhost", ignoreCase = true)) return null
    return withScheme
}
