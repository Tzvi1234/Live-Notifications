package com.tzvi.kickoff.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tzvi.kickoff.core.model.AppSettings
import com.tzvi.kickoff.core.model.IslandCutout
import com.tzvi.kickoff.core.model.LiveCardStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("kickoff_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val liveCardStyle = stringPreferencesKey("live_card_style")
        val preMatchLead = intPreferencesKey("pre_match_lead_minutes")
        val notifyGoals = booleanPreferencesKey("notify_goals")
        val notifyCards = booleanPreferencesKey("notify_cards")
        val notifySubs = booleanPreferencesKey("notify_subs")
        val notifyKickoff = booleanPreferencesKey("notify_kickoff")
        val notifyLineups = booleanPreferencesKey("notify_lineups")
        val calendarSync = booleanPreferencesKey("calendar_sync")
        val calendarLead = intPreferencesKey("calendar_lead_minutes")
        val calendarIds = stringSetPreferencesKey("calendar_ids")
        val pushEnabled = booleanPreferencesKey("push_enabled")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val darkTheme = stringPreferencesKey("dark_theme")
        val fcmToken = stringPreferencesKey("fcm_token")
        val apiKey = stringPreferencesKey("api_football_key")
        val backendUrl = stringPreferencesKey("backend_url")
        val demoMode = booleanPreferencesKey("demo_mode")
        val cutoutEnabled = booleanPreferencesKey("island_cutout_enabled")
        val cutoutX = floatPreferencesKey("island_cutout_center_x")
        val cutoutY = intPreferencesKey("island_cutout_center_y_dp")
        val cutoutDiameter = intPreferencesKey("island_cutout_diameter_dp")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingComplete = p[Keys.onboardingComplete] ?: false,
            liveCardStyle = p[Keys.liveCardStyle]
                ?.let { runCatching { LiveCardStyle.valueOf(it) }.getOrNull() }
                ?: LiveCardStyle.AUTO,
            preMatchLeadMinutes = p[Keys.preMatchLead] ?: 60,
            notifyGoals = p[Keys.notifyGoals] ?: true,
            notifyCards = p[Keys.notifyCards] ?: true,
            notifySubstitutions = p[Keys.notifySubs] ?: false,
            notifyKickoffAndFullTime = p[Keys.notifyKickoff] ?: true,
            notifyLineups = p[Keys.notifyLineups] ?: true,
            calendarSyncEnabled = p[Keys.calendarSync] ?: false,
            calendarLeadMinutes = p[Keys.calendarLead] ?: 30,
            enabledCalendarIds = p[Keys.calendarIds].orEmpty().mapNotNull(String::toLongOrNull).toSet(),
            pushEnabled = p[Keys.pushEnabled] ?: true,
            useDynamicColor = p[Keys.dynamicColor] ?: false,
            darkTheme = p[Keys.darkTheme]
                ?.let { runCatching { AppSettings.DarkThemePreference.valueOf(it) }.getOrNull() }
                ?: AppSettings.DarkThemePreference.SYSTEM,
        )
    }

    /** Empty unless the user pasted their own API-Football key in Settings. */
    val apiFootballKey: Flow<String> = context.dataStore.data.map { it[Keys.apiKey].orEmpty() }

    /** Empty unless a Kickoff backend has been pointed at. */
    val backendUrl: Flow<String> = context.dataStore.data.map { it[Keys.backendUrl].orEmpty() }

    /**
     * Demo mode replaces the football source with a generated one. It outranks a backend
     * and a key rather than falling back to them, so turning it on is always a complete
     * answer to "why is the app showing me this".
     */
    val demoMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.demoMode] ?: false }

    val fcmToken: Flow<String> = context.dataStore.data.map { it[Keys.fcmToken].orEmpty() }

    /**
     * The calibrated camera hole. Read by both islands - the in-app one and the overlay -
     * so a single calibration moves them together.
     */
    val islandCutout: Flow<IslandCutout> = context.dataStore.data.map { p ->
        IslandCutout(
            enabled = p[Keys.cutoutEnabled] ?: false,
            centerXFraction = p[Keys.cutoutX] ?: IslandCutout.Unset.centerXFraction,
            centerYDp = p[Keys.cutoutY] ?: IslandCutout.Unset.centerYDp,
            diameterDp = p[Keys.cutoutDiameter] ?: IslandCutout.Unset.diameterDp,
        ).sanitised()
    }

    suspend fun setOnboardingComplete(value: Boolean) = edit { it[Keys.onboardingComplete] = value }
    suspend fun setLiveCardStyle(style: LiveCardStyle) = edit { it[Keys.liveCardStyle] = style.name }
    suspend fun setPreMatchLeadMinutes(minutes: Int) = edit { it[Keys.preMatchLead] = minutes }
    suspend fun setNotifyGoals(value: Boolean) = edit { it[Keys.notifyGoals] = value }
    suspend fun setNotifyCards(value: Boolean) = edit { it[Keys.notifyCards] = value }
    suspend fun setNotifySubstitutions(value: Boolean) = edit { it[Keys.notifySubs] = value }
    suspend fun setNotifyKickoffAndFullTime(value: Boolean) = edit { it[Keys.notifyKickoff] = value }
    suspend fun setNotifyLineups(value: Boolean) = edit { it[Keys.notifyLineups] = value }
    suspend fun setCalendarSyncEnabled(value: Boolean) = edit { it[Keys.calendarSync] = value }
    suspend fun setCalendarLeadMinutes(minutes: Int) = edit { it[Keys.calendarLead] = minutes }
    suspend fun setPushEnabled(value: Boolean) = edit { it[Keys.pushEnabled] = value }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.dynamicColor] = value }
    suspend fun setApiFootballKey(key: String) = edit { it[Keys.apiKey] = key.trim() }
    suspend fun setBackendUrl(url: String) = edit { it[Keys.backendUrl] = url.trim() }
    suspend fun setDemoMode(value: Boolean) = edit { it[Keys.demoMode] = value }
    suspend fun setFcmToken(token: String) = edit { it[Keys.fcmToken] = token }

    suspend fun setIslandCutout(cutout: IslandCutout) = edit { p ->
        val safe = cutout.sanitised()
        p[Keys.cutoutEnabled] = safe.enabled
        p[Keys.cutoutX] = safe.centerXFraction
        p[Keys.cutoutY] = safe.centerYDp
        p[Keys.cutoutDiameter] = safe.diameterDp
    }

    suspend fun setDarkTheme(pref: AppSettings.DarkThemePreference) =
        edit { it[Keys.darkTheme] = pref.name }

    suspend fun setEnabledCalendars(ids: Set<Long>) =
        edit { it[Keys.calendarIds] = ids.map(Long::toString).toSet() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
