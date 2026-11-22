package com.holything.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.repository.ContentRepository
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
    val lastSyncedAt: Long? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val dataStore: UserPreferencesDataStore,
    private val repository: ContentRepository
) : ViewModel() {

    val uiState: StateFlow<ProfileUiState> = combine(
        dataStore.textScale,
        dataStore.darkModeConfig,
        dataStore.lastSyncedAt
    ) { scale, mode, lastSynced ->
        ProfileUiState(
            textScale = scale,
            darkModeConfig = mode,
            lastSyncedAt = lastSynced
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

    fun deleteMyData() {
        viewModelScope.launch {
            repository.clearAllData()
        }
    }
}

