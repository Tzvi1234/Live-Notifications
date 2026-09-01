package com.tzvi.kickoff.feature.settings

import com.tzvi.kickoff.core.model.AppSettings
import com.tzvi.kickoff.core.model.LiveCardStyle

/**
 * What this device will actually do with a live card.
 *
 * Every value is read back from [com.tzvi.kickoff.notifications.LiveUpdateCapability] rather
 * than assumed, because promotion fails silently: a card that is refused simply stays in
 * the shade, with nothing to tell the user why.
 */
data class LiveUpdateStatus(
    val supportsProgressStyle: Boolean = false,
    val supportsPromotion: Boolean = false,
    val promotionAllowed: Boolean = false,
    /** False when no activity on this build handles the promotion settings action. */
    val canOpenPromotionSettings: Boolean = false,
) {
    val reachesAmbientSurfaces: Boolean get() = supportsPromotion && promotionAllowed
}

/** POST_NOTIFICATIONS, which every alert in this screen ultimately depends on. */
data class NotificationAccess(
    val granted: Boolean = true,
    /** Android grants one dialog; after a denial only the system settings screen is left. */
    val requestSpent: Boolean = false,
)

data class IslandStatus(
    val overlayPermissionGranted: Boolean = false,
    val floatingEnabled: Boolean = false,
)

data class DataSourceForm(
    val apiKeyInput: String = "",
    val apiKeyStored: Boolean = false,
    val apiKeyRevealed: Boolean = false,
    val backendUrlInput: String = "",
    val backendUrlStored: Boolean = false,
    val backendUrlError: String? = null,
    /** `FootballRepository.sourceName()`, which reports [NO_SOURCE] until one is set. */
    val activeSourceName: String = NO_SOURCE,
) {
    val hasSource: Boolean get() = activeSourceName != NO_SOURCE

    companion object {
        const val NO_SOURCE = "none"
    }
}

data class SettingsUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val settings: AppSettings = AppSettings(),
    val liveUpdate: LiveUpdateStatus = LiveUpdateStatus(),
    val notifications: NotificationAccess = NotificationAccess(),
    val island: IslandStatus = IslandStatus(),
    val dataSource: DataSourceForm = DataSourceForm(),
    val appVersion: String = "",
    /** Material You only exists from Android 12; below that the switch is inert. */
    val dynamicColorAvailable: Boolean = true,
    /** Transient confirmation shown after a save; cleared by the next edit. */
    val message: String? = null,
)

/** The word on the segmented button. */
val LiveCardStyle.label: String
    get() = when (this) {
        LiveCardStyle.AUTO -> "Auto"
        LiveCardStyle.RICH -> "Rich"
        LiveCardStyle.PLAIN -> "Plain"
    }

/**
 * What each choice actually costs, in the user's terms rather than the platform's.
 *
 * A custom RemoteViews is a hard disqualifier for promotion, so "richer" and "on the
 * lock screen" are genuinely mutually exclusive - saying so is the only honest option.
 */
val LiveCardStyle.explanation: String
    get() = when (this) {
        LiveCardStyle.AUTO ->
            "Prefers a promoted Live Update - the only kind of notification that reaches " +
                "the status-bar chip, the lock screen and, where the hardware supports it, " +
                "the always-on display. Falls back to Rich where promotion is unavailable."
        LiveCardStyle.RICH ->
            "Draws a full scoreboard with crests inside the shade. A custom layout is never " +
                "promoted, so this trades away the chip, the lock-screen card and the " +
                "always-on display."
        LiveCardStyle.PLAIN ->
            "The system's own template. Nothing custom is drawn and no promotion is asked " +
                "for, which makes it the most predictable of the three."
    }

val AppSettings.DarkThemePreference.label: String
    get() = when (this) {
        AppSettings.DarkThemePreference.SYSTEM -> "System"
        AppSettings.DarkThemePreference.LIGHT -> "Light"
        AppSettings.DarkThemePreference.DARK -> "Dark"
    }

/** The pre-match card can appear between a quarter of an hour and two hours out. */
const val PRE_MATCH_LEAD_MIN = 15
const val PRE_MATCH_LEAD_MAX = 120
const val PRE_MATCH_LEAD_STEP = 15
