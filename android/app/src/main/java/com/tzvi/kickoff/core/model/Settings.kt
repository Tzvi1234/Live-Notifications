package com.tzvi.kickoff.core.model

/** How the live match card should be rendered, when the device gives us a choice. */
enum class LiveCardStyle {
    /**
     * The match clock as a real progress bar - two halves as segments, a point at every
     * goal, the ball riding the current minute. Falls back to [RICH] on devices too old
     * for ProgressStyle, which is a fallback in name only: both are promoted.
     */
    AUTO,

    /**
     * A running commentary instead of a bar. The stored name is historical - this used to
     * be a hand-drawn scoreboard, which could never be promoted and so could never reach
     * the lock screen or the always-on display. It is now BigTextStyle, whose long text is
     * the only kind Android carries onto an always-on display in full.
     */
    RICH,

    /** Plain system template. Cheapest, most predictable, least interesting. */
    PLAIN,

    /**
     * A hand-drawn dark score card - crest, giant score, crest - modelled on the iOS
     * lock-screen sports widget. The one style that draws its own views, which is
     * exactly why it can never be promoted: it lives in the shade and on the lock
     * screen, and never reaches the status-bar chip or the always-on display. That
     * trade is the user's to make, and this option is how they make it.
     */
    SCOREBOARD,
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
    /** On by default: Material You is the platform default look, not an opt-in. */
    val useDynamicColor: Boolean = true,
    val darkTheme: DarkThemePreference = DarkThemePreference.SYSTEM,
) {
    enum class DarkThemePreference { SYSTEM, LIGHT, DARK }
}
