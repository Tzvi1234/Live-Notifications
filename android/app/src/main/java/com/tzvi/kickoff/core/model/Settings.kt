package com.tzvi.kickoff.core.model

/** How the live match card should be rendered, when the device gives us a choice. */
enum class LiveCardStyle {
    /**
     * Prefer a promoted Live Update (status-bar chip, top of the shade, lock screen,
     * and on devices that support it the always-on display). Falls back to [RICH]
     * automatically wherever promotion is unavailable.
     */
    AUTO,

    /**
     * Always use the custom scoreboard layout. Richer looking, but a custom RemoteViews
     * is a hard disqualifier for promotion, so this trades the chip and AOD away.
     */
    RICH,

    /** Plain system template. Cheapest, most predictable, least interesting. */
    PLAIN,
}

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val liveCardStyle: LiveCardStyle = LiveCardStyle.AUTO,
    /** Minutes before kick-off the pre-match card appears. Product default is 60. */
    val preMatchLeadMinutes: Int = 60,
    val notifyGoals: Boolean = true,
    val notifyCards: Boolean = true,
    val notifySubstitutions: Boolean = false,
    val notifyKickoffAndFullTime: Boolean = true,
    val notifyLineups: Boolean = true,
    val calendarSyncEnabled: Boolean = false,
    val calendarLeadMinutes: Int = 30,
    val enabledCalendarIds: Set<Long> = emptySet(),
    val pushEnabled: Boolean = true,
    val useDynamicColor: Boolean = false,
    val darkTheme: DarkThemePreference = DarkThemePreference.SYSTEM,
) {
    enum class DarkThemePreference { SYSTEM, LIGHT, DARK }
}
