package com.tzvi.kickoff.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore("kickoff_auth")

/**
 * The two things the account layer has to remember across launches.
 *
 * A store of its own rather than rows in SettingsRepository: neither value is a user
 * preference. One is a cached answer from the server, the other is how far the launch
 * flow got - and both are meaningless outside this package.
 */
@Singleton
class AuthPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val publishableKey = stringPreferencesKey("clerk_publishable_key")
        val gateCleared = booleanPreferencesKey("auth_gate_cleared")
    }

    /**
     * The key the backend last handed out.
     *
     * Cached so that the second launch of a build with no compiled-in key does not have
     * to reach the network before it can decide whether accounts exist at all.
     */
    val publishableKey: Flow<String> =
        context.authDataStore.data.map { it[Keys.publishableKey].orEmpty() }

    /**
     * Whether the user has been past the auth screen - by signing in, or by declining to.
     *
     * Signing out clears it, which is what sends the app back to the auth screen rather
     * than dropping a signed-out user into a shell of account features.
     */
    val gateCleared: Flow<Boolean> =
        context.authDataStore.data.map { it[Keys.gateCleared] ?: false }

    suspend fun setPublishableKey(key: String) =
        edit { it[Keys.publishableKey] = key.trim() }

    suspend fun setGateCleared(value: Boolean) = edit { it[Keys.gateCleared] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.authDataStore.edit(block)
    }
}
