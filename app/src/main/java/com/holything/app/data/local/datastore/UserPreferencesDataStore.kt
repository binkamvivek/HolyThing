package com.holything.app.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.holything.app.core.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = Constants.PREFERENCES_NAME)

@Singleton
class UserPreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        val KEY_TEXT_SCALE = floatPreferencesKey("pref_text_scale")
        val KEY_DARK_MODE = stringPreferencesKey("pref_dark_mode") // "SYSTEM", "LIGHT", "DARK"
        val KEY_LAST_SYNCED_VERSION = stringPreferencesKey("pref_last_synced_version")
        val KEY_LAST_SYNCED_AT = longPreferencesKey("pref_last_synced_at")
        val KEY_HAS_SEEN_WELCOME = booleanPreferencesKey("pref_has_seen_welcome")
    }

    val textScale: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_TEXT_SCALE] ?: Constants.DEFAULT_TEXT_SCALE
    }

    val darkModeConfig: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DARK_MODE] ?: "SYSTEM"
    }

    val lastSyncedVersion: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNCED_VERSION]
    }

    val lastSyncedAt: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNCED_AT]
    }

    val hasSeenWelcome: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HAS_SEEN_WELCOME] ?: false
    }

    suspend fun setTextScale(scale: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_TEXT_SCALE] = scale
        }
    }

    suspend fun setDarkModeConfig(mode: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DARK_MODE] = mode
        }
    }

    suspend fun setLastSynced(version: String, timestamp: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_SYNCED_VERSION] = version
            prefs[KEY_LAST_SYNCED_AT] = timestamp
        }
    }

    suspend fun setHasSeenWelcome(seen: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_HAS_SEEN_WELCOME] = seen
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

