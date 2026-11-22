package com.holything.app.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.repository.ContentRepository
import com.holything.app.domain.model.Card
import com.holything.app.domain.model.SyncResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedUiState(
    val cards: List<Card> = emptyList(),
    val totalCount: Int = 0,
    val lastSyncedVersion: String? = null,
    val isSyncing: Boolean = false,
    val lastSyncMessage: String = "Ready"
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: ContentRepository,
    private val dataStore: UserPreferencesDataStore
) : ViewModel() {

    private val isSyncingFlow = MutableStateFlow(false)
    private val syncMessageFlow = MutableStateFlow("Initializing...")

    val uiState: StateFlow<FeedUiState> = combine(
        repository.cards,
        repository.cardsCount,
        dataStore.lastSyncedVersion,
        isSyncingFlow,
        syncMessageFlow
    ) { cards, count, version, syncing, msg ->
        FeedUiState(
            cards = cards,
            totalCount = count,
            lastSyncedVersion = version,
            isSyncing = syncing,
            lastSyncMessage = msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FeedUiState()
    )

    init {
        syncContent()
    }

    fun syncContent() {
        viewModelScope.launch {
            isSyncingFlow.value = true
            syncMessageFlow.value = "Syncing with cloud..."
            val result = repository.syncContent()
            isSyncingFlow.value = false
            
            result.onSuccess { syncRes ->
                syncMessageFlow.value = when (syncRes) {
                    is SyncResult.Synced -> "Synced ${syncRes.updatedCount} cards (v${syncRes.version.take(8)})"
                    is SyncResult.UpToDate -> "Up to date (v${syncRes.version.take(8)})"
                    is SyncResult.FallbackLoaded -> "Loaded ${syncRes.count} bundled cards (offline seed)"
                    is SyncResult.Offline -> "Offline mode (${syncRes.localCount} cards available)"
                }
            }.onFailure { err ->
                syncMessageFlow.value = "Sync error: ${err.message}"
            }
        }
    }
}

