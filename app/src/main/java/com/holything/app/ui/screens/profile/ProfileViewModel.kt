package com.holything.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.holything.app.core.reminder.ReminderScheduler
import com.holything.app.core.signal.SignalCaptureManager
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.local.room.entity.SignalEventEntity
import com.holything.app.data.repository.ContentRepository
import com.holything.app.domain.model.StreakInfo
import com.holything.app.domain.state.UserStateEstimate
import com.holything.app.domain.state.UserStateEstimator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val randomReminderEnabled: Boolean = false,
    val reminderStartTime: String = "09:00",
    val reminderEndTime: String = "21:00",
    val reminderFrequency: Int = 2,
    val streakInfo: StreakInfo = StreakInfo()
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val dataStore: UserPreferencesDataStore,
    private val repository: ContentRepository,
    private val reminderScheduler: ReminderScheduler,
    private val signalCaptureManager: SignalCaptureManager,
    private val userStateEstimator: UserStateEstimator
) : ViewModel() {

    private val _messageEvent = MutableSharedFlow<String>()
    val messageEvent: SharedFlow<String> = _messageEvent.asSharedFlow()

    val userState: StateFlow<UserStateEstimate> = userStateEstimator.state
    val recentServedCards: StateFlow<List<SignalCaptureManager.ServedCardDebugInfo>> =
        signalCaptureManager.recentServedCards

    private val _recentSignalEvents = MutableStateFlow<List<SignalEventEntity>>(emptyList())
    val recentSignalEvents: StateFlow<List<SignalEventEntity>> = _recentSignalEvents.asStateFlow()

    private data class ReminderPrefs(
        val enabled: Boolean,
        val reminderTime: String,
        val randomEnabled: Boolean,
        val startTime: String,
        val endTime: String,
        val frequency: Int
    )

    private val randomPrefsFlow = combine(
        dataStore.randomReminderEnabled,
        dataStore.reminderStartTime,
        dataStore.reminderEndTime,
        dataStore.reminderFrequency
    ) { randomEnabled, startTime, endTime, frequency ->
        listOf(randomEnabled, startTime, endTime, frequency)
    }

    private val reminderPrefsFlow = combine(
        dataStore.reminderEnabled,
        dataStore.reminderTime,
        randomPrefsFlow
    ) { enabled, time, randomList ->
        ReminderPrefs(
            enabled = enabled,
            reminderTime = time,
            randomEnabled = randomList[0] as Boolean,
            startTime = randomList[1] as String,
            endTime = randomList[2] as String,
            frequency = randomList[3] as Int
        )
    }

    private val generalPrefsFlow = combine(
        dataStore.textScale,
        dataStore.darkModeConfig,
        dataStore.translation
    ) { scale, mode, translation ->
        Triple(scale, mode, translation)
    }

    val uiState: StateFlow<ProfileUiState> = combine(
        generalPrefsFlow,
        reminderPrefsFlow,
        repository.streakInfo
    ) { general, rem, streak ->
        ProfileUiState(
            textScale = general.first,
            darkModeConfig = general.second,
            translation = general.third,
            reminderEnabled = rem.enabled,
            reminderTime = rem.reminderTime,
            randomReminderEnabled = rem.randomEnabled,
            reminderStartTime = rem.startTime,
            reminderEndTime = rem.endTime,
            reminderFrequency = rem.frequency,
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
                reminderScheduler.cancelDailyReminder()
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

    fun setRandomReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setRandomReminderEnabled(enabled)
            if (enabled) {
                reminderScheduler.scheduleRandomReminders(
                    startTimeStr = uiState.value.reminderStartTime,
                    endTimeStr = uiState.value.reminderEndTime,
                    frequency = uiState.value.reminderFrequency
                )
            } else {
                reminderScheduler.cancelRandomReminders()
            }
        }
    }

    fun setReminderStartTime(time: String) {
        viewModelScope.launch {
            dataStore.setReminderWindow(time, uiState.value.reminderEndTime)
            if (uiState.value.randomReminderEnabled) {
                reminderScheduler.scheduleRandomReminders(
                    startTimeStr = time,
                    endTimeStr = uiState.value.reminderEndTime,
                    frequency = uiState.value.reminderFrequency
                )
            }
        }
    }

    fun setReminderEndTime(time: String) {
        viewModelScope.launch {
            dataStore.setReminderWindow(uiState.value.reminderStartTime, time)
            if (uiState.value.randomReminderEnabled) {
                reminderScheduler.scheduleRandomReminders(
                    startTimeStr = uiState.value.reminderStartTime,
                    endTimeStr = time,
                    frequency = uiState.value.reminderFrequency
                )
            }
        }
    }

    fun setReminderFrequency(frequency: Int) {
        viewModelScope.launch {
            dataStore.setReminderFrequency(frequency)
            if (uiState.value.randomReminderEnabled) {
                reminderScheduler.scheduleRandomReminders(
                    startTimeStr = uiState.value.reminderStartTime,
                    endTimeStr = uiState.value.reminderEndTime,
                    frequency = frequency
                )
            }
        }
    }

    fun sendTestNotification() {
        viewModelScope.launch {
            val cards = repository.getActiveCardsList()
            val chosen = cards.shuffled().firstOrNull()
            val hook = chosen?.hook ?: "Pause, breathe, and rest in Scripture today."
            val cardId = chosen?.id ?: ""
            reminderScheduler.postNotificationImmediately(cardId, hook)
            _messageEvent.emit("Notification sent! Check your notification tray.")
        }
    }

    fun loadDebugSnapshot() {
        viewModelScope.launch {
            _recentSignalEvents.value = repository.getRecentSignalEvents(20)
        }
    }

    fun resetMyFeed() {
        viewModelScope.launch {
            signalCaptureManager.resetSessionState()
            repository.resetFeedLearning()
            _messageEvent.emit("Feed preferences and history reset.")
        }
    }

    fun deleteMyData() {
        viewModelScope.launch {
            reminderScheduler.cancelAllReminders()
            signalCaptureManager.resetSessionState()
            repository.clearAllData()
        }
    }
}
