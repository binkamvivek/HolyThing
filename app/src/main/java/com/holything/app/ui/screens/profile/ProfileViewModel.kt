package com.holything.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.holything.app.core.reminder.ReminderScheduler
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.repository.ContentRepository
import com.holything.app.domain.model.StreakInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val textScale: Float = 1.0f,
    val darkModeConfig: String = "SYSTEM",
    val translation: String = "WEB",
    val reminderEnabled: Boolean = false,
    val reminderTime: String = "08:00",
    val streakInfo: StreakInfo = StreakInfo()
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val dataStore: UserPreferencesDataStore,
    private val repository: ContentRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    private data class PreferenceState(
        val scale: Float,
        val mode: String,
        val translation: String,
        val reminderEnabled: Boolean,
        val reminderTime: String
    )

    private val prefsFlow = combine(
        dataStore.textScale,
        dataStore.darkModeConfig,
        dataStore.translation,
        dataStore.reminderEnabled,
        dataStore.reminderTime
    ) { scale, mode, trans, remEnabled, remTime ->
        PreferenceState(scale, mode, trans, remEnabled, remTime)
    }

    val uiState: StateFlow<ProfileUiState> = combine(
        prefsFlow,
        repository.streakInfo
    ) { prefs, streak ->
        ProfileUiState(
            textScale = prefs.scale,
            darkModeConfig = prefs.mode,
            translation = prefs.translation,
            reminderEnabled = prefs.reminderEnabled,
            reminderTime = prefs.reminderTime,
            streakInfo = streak
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProfileUiState()
    )

    fun setTextScale(scale: Float) {
        viewModelScope.launch {
            dataStore.setTextScale(scale)
        }
    }

    fun setDarkMode(mode: String) {
        viewModelScope.launch {
            dataStore.setDarkModeConfig(mode)
        }
    }

    fun setTranslation(trans: String) {
        viewModelScope.launch {
            dataStore.setTranslation(trans)
        }
    }

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setReminderEnabled(enabled)
            if (enabled) {
                reminderScheduler.scheduleDailyReminder(uiState.value.reminderTime)
            } else {
                reminderScheduler.cancelReminder()
            }
        }
    }

    fun setReminderTime(time: String) {
        viewModelScope.launch {
            dataStore.setReminderTime(time)
            if (uiState.value.reminderEnabled) {
                reminderScheduler.scheduleDailyReminder(time)
            }
        }
    }

    fun deleteMyData() {
        viewModelScope.launch {
            reminderScheduler.cancelReminder()
            repository.clearAllData()
        }
    }
}
