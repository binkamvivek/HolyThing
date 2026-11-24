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
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.holything.app.core.Constants
import com.holything.app.domain.model.OnboardingPreferences
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
        val KEY_DARK_MODE = stringPreferencesKey("pref_dark_mode")
        val KEY_LAST_SYNCED_VERSION = stringPreferencesKey("pref_last_synced_version")
        val KEY_LAST_SYNCED_AT = longPreferencesKey("pref_last_synced_at")
        val KEY_HAS_SEEN_WELCOME = booleanPreferencesKey("pref_has_seen_welcome")

        // Phase 4: Identity, Onboarding & Learning Engine
        val KEY_ANONYMOUS_USER_ID = stringPreferencesKey("pref_anon_user_id")
        val KEY_USER_MOOD = stringPreferencesKey("pref_user_mood")
        val KEY_INITIAL_MOOD_HINT = stringPreferencesKey("pref_initial_mood_hint")
        val KEY_MOOD_HINT_TIMESTAMP = longPreferencesKey("pref_mood_hint_timestamp")
        val KEY_USER_STYLES = stringSetPreferencesKey("pref_user_styles")
        val KEY_USER_GOAL = stringPreferencesKey("pref_user_goal")
        val KEY_HAS_SHOWN_SUPPORT_MESSAGE = booleanPreferencesKey("pref_has_shown_support_msg")
        val KEY_SWIPE_COUNT = intPreferencesKey("pref_swipe_count")
        val KEY_TASTE_PROFILE = stringPreferencesKey("pref_taste_profile_json")

        // Phase 5: Translation, Reminders, Care & Streaks
        val KEY_TRANSLATION = stringPreferencesKey("pref_bible_translation")
        val KEY_REMINDER_ENABLED = booleanPreferencesKey("pref_reminder_enabled")
        val KEY_REMINDER_TIME = stringPreferencesKey("pref_reminder_time")
        val KEY_RANDOM_REMINDER_ENABLED = booleanPreferencesKey("pref_random_reminder_enabled")
        val KEY_REMINDER_START_TIME = stringPreferencesKey("pref_reminder_start_time")
        val KEY_REMINDER_END_TIME = stringPreferencesKey("pref_reminder_end_time")
        val KEY_REMINDER_FREQUENCY = intPreferencesKey("pref_reminder_frequency")
        val KEY_REMINDER_IGNORED_COUNT = intPreferencesKey("pref_reminder_ignored_count")
        val KEY_HAS_PROMPTED_NOTIFICATION = booleanPreferencesKey("pref_has_prompted_notif")
    }

    val translation: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TRANSLATION] ?: "WEB"
    }

    val reminderEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_REMINDER_ENABLED] ?: false
    }

    val reminderTime: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_REMINDER_TIME] ?: "08:00"
    }

    val randomReminderEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_RANDOM_REMINDER_ENABLED] ?: false
    }

    val reminderStartTime: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_REMINDER_START_TIME] ?: "09:00"
    }

    val reminderEndTime: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_REMINDER_END_TIME] ?: "21:00"
    }

    val reminderFrequency: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_REMINDER_FREQUENCY] ?: 2
    }

    val reminderIgnoredCount: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_REMINDER_IGNORED_COUNT] ?: 0
    }

    val hasPromptedNotification: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HAS_PROMPTED_NOTIFICATION] ?: false
    }

    val anonymousUserId: Flow<String?> = dataStore.data.map { it[KEY_ANONYMOUS_USER_ID] }

    val initialMoodHint: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_INITIAL_MOOD_HINT]?.takeIf { it.isNotBlank() }
    }

    val moodHintTimestamp: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_MOOD_HINT_TIMESTAMP] ?: 0L
    }

    val onboardingPreferences: Flow<OnboardingPreferences> = dataStore.data.map { prefs ->
        OnboardingPreferences(
            mood = prefs[KEY_USER_MOOD] ?: "hopeful",
            styles = prefs[KEY_USER_STYLES] ?: setOf("verse"),
            goal = prefs[KEY_USER_GOAL] ?: "peace"
        )
    }

    val hasShownSupportMessage: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HAS_SHOWN_SUPPORT_MESSAGE] ?: false
    }

    val swipeCount: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SWIPE_COUNT] ?: 0
    }

    val tasteProfileJson: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_TASTE_PROFILE]
    }

    suspend fun getOrCreateAnonymousId(idGenerator: () -> String): String {
        var id: String? = null
        dataStore.edit { prefs ->
            val existing = prefs[KEY_ANONYMOUS_USER_ID]
            if (existing != null) {
                id = existing
            } else {
                val newId = idGenerator()
                prefs[KEY_ANONYMOUS_USER_ID] = newId
                id = newId
            }
        }
        return id ?: idGenerator()
    }

    suspend fun saveOnboardingPreferences(prefs: OnboardingPreferences) {
        dataStore.edit { p ->
            p[KEY_USER_MOOD] = prefs.mood
            p[KEY_USER_STYLES] = prefs.styles
            p[KEY_USER_GOAL] = prefs.goal
            p[KEY_HAS_SEEN_WELCOME] = true
        }
    }

    suspend fun markSupportMessageShown() {
        dataStore.edit { prefs ->
            prefs[KEY_HAS_SHOWN_SUPPORT_MESSAGE] = true
        }
    }

    suspend fun incrementSwipeCount(): Int {
        var count = 0
        dataStore.edit { prefs ->
            val current = prefs[KEY_SWIPE_COUNT] ?: 0
            count = current + 1
            prefs[KEY_SWIPE_COUNT] = count
        }
        return count
    }

    suspend fun resetSwipeCount() {
        dataStore.edit { prefs ->
            prefs[KEY_SWIPE_COUNT] = 0
        }
    }

    suspend fun saveTasteProfileJson(json: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TASTE_PROFILE] = json
        }
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

    suspend fun setTranslation(trans: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TRANSLATION] = trans
        }
    }

    suspend fun setReminderEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_REMINDER_ENABLED] = enabled
        }
    }

    suspend fun setReminderTime(time: String) {
        dataStore.edit { prefs ->
            prefs[KEY_REMINDER_TIME] = time
        }
    }

    suspend fun setRandomReminderEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_RANDOM_REMINDER_ENABLED] = enabled
        }
    }

    suspend fun setReminderWindow(startTime: String, endTime: String) {
        dataStore.edit { prefs ->
            prefs[KEY_REMINDER_START_TIME] = startTime
            prefs[KEY_REMINDER_END_TIME] = endTime
        }
    }

    suspend fun setReminderFrequency(frequency: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_REMINDER_FREQUENCY] = frequency
        }
    }

    suspend fun incrementReminderIgnored() {
        dataStore.edit { prefs ->
            val count = prefs[KEY_REMINDER_IGNORED_COUNT] ?: 0
            prefs[KEY_REMINDER_IGNORED_COUNT] = count + 1
        }
    }

    suspend fun resetReminderIgnored() {
        dataStore.edit { prefs ->
            prefs[KEY_REMINDER_IGNORED_COUNT] = 0
        }
    }

    suspend fun markNotificationPrompted() {
        dataStore.edit { prefs ->
            prefs[KEY_HAS_PROMPTED_NOTIFICATION] = true
        }
    }

    suspend fun setInitialMoodHint(mood: String?) {
        dataStore.edit { prefs ->
            if (mood.isNullOrBlank()) {
                prefs.remove(KEY_INITIAL_MOOD_HINT)
                prefs.remove(KEY_MOOD_HINT_TIMESTAMP)
            } else {
                prefs[KEY_INITIAL_MOOD_HINT] = mood.lowercase().trim()
                prefs[KEY_MOOD_HINT_TIMESTAMP] = System.currentTimeMillis()
            }
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

