package com.holything.app.ui.screens.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.repository.ContentRepository
import com.holything.app.domain.engine.TasteProfileEngine
import com.holything.app.domain.model.OnboardingPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class OnboardingUiState(
    val step: Int = 0, // 0: Mood, 1: Style, 2: Goal
    val selectedMood: String? = null,
    val selectedStyles: Set<String> = setOf("verse"),
    val selectedGoal: String? = null
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: ContentRepository,
    private val dataStore: UserPreferencesDataStore,
    private val json: Json
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    val availableMoods: StateFlow<List<String>> = repository.distinctMoods.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = OnboardingPreferences.DEFAULT_MOODS
    )

    fun onMoodSelected(mood: String) {
        _uiState.value = _uiState.value.copy(
            selectedMood = mood,
            step = 1
        )
    }

    fun onStyleToggled(styleKey: String) {
        val current = _uiState.value.selectedStyles.toMutableSet()
        if (current.contains(styleKey)) {
            if (current.size > 1) current.remove(styleKey) // Keep at least one
        } else {
            current.add(styleKey)
        }
        _uiState.value = _uiState.value.copy(selectedStyles = current)
    }

    fun onStylesConfirmed() {
        _uiState.value = _uiState.value.copy(step = 2)
    }

    fun onGoalSelected(goal: String, onFinished: () -> Unit) {
        _uiState.value = _uiState.value.copy(selectedGoal = goal)
        val finalMood = _uiState.value.selectedMood ?: "hopeful"
        val finalStyles = _uiState.value.selectedStyles.ifEmpty { setOf("verse") }

        val prefs = OnboardingPreferences(
            mood = finalMood,
            styles = finalStyles,
            goal = goal,
            isHeavyMood = finalMood.lowercase() in OnboardingPreferences.HEAVY_MOODS
        )

        viewModelScope.launch {
            dataStore.saveOnboardingPreferences(prefs)
            // Initialize on-device taste profile
            val initialProfile = TasteProfileEngine.createFromOnboarding(prefs)
            dataStore.saveTasteProfileJson(json.encodeToString(initialProfile))
            onFinished()
        }
    }

    fun onPreviousStep() {
        if (_uiState.value.step > 0) {
            _uiState.value = _uiState.value.copy(step = _uiState.value.step - 1)
        }
    }
}
