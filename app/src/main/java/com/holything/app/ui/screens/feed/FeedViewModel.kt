package com.holything.app.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.holything.app.data.repository.ContentRepository
import com.holything.app.domain.model.Card
import com.holything.app.domain.model.FeedCardItem
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.domain.model.OnboardingPreferences
import com.holything.app.domain.model.SyncResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedUiState(
    val items: List<FeedCardItem> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val syncStatusMessage: String = "Ready",
    val showSupportBanner: Boolean = false,
    val showMoodRecheck: Boolean = false,
    val currentMood: String = "hopeful",
    val availableMoods: List<String> = OnboardingPreferences.DEFAULT_MOODS
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: ContentRepository,
    private val dataStore: UserPreferencesDataStore
) : ViewModel() {

    private val isLoadingFlow = MutableStateFlow(true)
    private val isSyncingFlow = MutableStateFlow(false)
    private val isOfflineFlow = MutableStateFlow(false)
    private val statusMessageFlow = MutableStateFlow("Loading scripture...")

    private val _userFeedbackEvent = MutableSharedFlow<String>()
    val userFeedbackEvent: SharedFlow<String> = _userFeedbackEvent.asSharedFlow()

    private var currentPageIndex = 0
    private var pageStartTime = System.currentTimeMillis()

    private val _feedItemsFlow = MutableStateFlow<List<FeedCardItem>>(emptyList())

    private data class SyncStatus(
        val loading: Boolean,
        val syncing: Boolean,
        val offline: Boolean,
        val message: String
    )

    private val syncStatusFlow = combine(
        isLoadingFlow,
        isSyncingFlow,
        isOfflineFlow,
        statusMessageFlow
    ) { loading, syncing, offline, msg ->
        SyncStatus(loading, syncing, offline, msg)
    }

    private data class UserPreferencesState(
        val onboarding: OnboardingPreferences,
        val supportShown: Boolean,
        val swipes: Int,
        val moods: List<String>
    )

    private val userPrefsFlow = combine(
        dataStore.onboardingPreferences,
        dataStore.hasShownSupportMessage,
        dataStore.swipeCount,
        repository.distinctMoods
    ) { onboarding, supportShown, swipes, moods ->
        UserPreferencesState(onboarding, supportShown, swipes, moods)
    }

    val uiState: StateFlow<FeedUiState> = combine(
        _feedItemsFlow,
        repository.cardsCount,
        syncStatusFlow,
        userPrefsFlow
    ) { items, count, status, userPrefs ->
        val shouldShowSupport = userPrefs.onboarding.isHeavyMood && !userPrefs.supportShown
        val shouldShowMoodRecheck = userPrefs.swipes >= 25
        FeedUiState(
            items = items,
            totalCount = count,
            isLoading = status.loading && items.isEmpty(),
            isSyncing = status.syncing,
            isOffline = status.offline,
            syncStatusMessage = status.message,
            showSupportBanner = shouldShowSupport,
            showMoodRecheck = shouldShowMoodRecheck,
            currentMood = userPrefs.onboarding.mood,
            availableMoods = userPrefs.moods
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FeedUiState()
    )

    init {
        viewModelScope.launch {
            repository.getFeedCards().collect { newItems ->
                if (_feedItemsFlow.value.isEmpty() || newItems.size != _feedItemsFlow.value.size) {
                    _feedItemsFlow.value = newItems
                }
            }
        }
        syncContent()
    }

    fun syncContent() {
        viewModelScope.launch {
            isSyncingFlow.value = true
            val result = repository.syncContent()
            isSyncingFlow.value = false
            isLoadingFlow.value = false

            result.onSuccess { syncResult ->
                when (syncResult) {
                    is SyncResult.Synced -> {
                        isOfflineFlow.value = false
                        statusMessageFlow.value = "Synced ${syncResult.updatedCount} cards"
                    }
                    is SyncResult.UpToDate -> {
                        isOfflineFlow.value = false
                        statusMessageFlow.value = "Feed up to date"
                    }
                    is SyncResult.FallbackLoaded -> {
                        isOfflineFlow.value = true
                        statusMessageFlow.value = "Loaded offline seed cards"
                    }
                    is SyncResult.Offline -> {
                        isOfflineFlow.value = true
                        statusMessageFlow.value = "Offline mode"
                    }
                }
            }.onFailure {
                isOfflineFlow.value = true
                statusMessageFlow.value = "Using offline library"
            }
        }
    }

    /**
     * Dwell tracking: records duration the user stayed on the card.
     * Flags fast-skips (< 1.5s) to avoid repeating unwanted cards.
     */
    fun onPageChanged(newIndex: Int) {
        val items = uiState.value.items
        if (currentPageIndex in items.indices) {
            val previousCard = items[currentPageIndex].card
            val dwellMs = System.currentTimeMillis() - pageStartTime
            val interactionType = if (dwellMs < 1500) "fast_skip" else "dwell"

            viewModelScope.launch {
                repository.recordInteraction(
                    cardId = previousCard.id,
                    type = interactionType,
                    dwellMs = dwellMs
                )
            }
        }
        currentPageIndex = newIndex
        pageStartTime = System.currentTimeMillis()
    }

    fun onAmenToggle(cardId: String) {
        viewModelScope.launch {
            val added = repository.toggleAmen(cardId)
            _feedItemsFlow.update { list ->
                list.map { if (it.card.id == cardId) it.copy(isAmened = added) else it }
            }
            _userFeedbackEvent.emit(if (added) "Amen" else "Amen removed")
        }
    }

    fun onSaveToggle(cardId: String) {
        viewModelScope.launch {
            val added = repository.toggleSave(cardId)
            _feedItemsFlow.update { list ->
                list.map { if (it.card.id == cardId) it.copy(isSaved = added) else it }
            }
            _userFeedbackEvent.emit(if (added) "Saved to your library" else "Removed from Saved")
        }
    }

    fun onShare(card: Card) {
        viewModelScope.launch {
            repository.recordInteraction(cardId = card.id, type = "share", dwellMs = 0)
        }
    }

    fun onMoreLikeThis(cardId: String) {
        viewModelScope.launch {
            repository.recordInteraction(cardId = cardId, type = "more_like_this", dwellMs = 0)
            _userFeedbackEvent.emit("We'll show you more passages like this")
        }
    }

    fun onLessLikeThis(cardId: String) {
        viewModelScope.launch {
            repository.recordInteraction(cardId = cardId, type = "less_like_this", dwellMs = 0)
            _userFeedbackEvent.emit("We'll show you fewer cards like this")
        }
    }

    fun onReflect(cardId: String) {
        viewModelScope.launch {
            repository.recordInteraction(cardId = cardId, type = "reflect", dwellMs = 0)
        }
    }

    fun onPrayed(cardId: String, noteText: String) {
        viewModelScope.launch {
            repository.saveJournalNote(cardId, noteText)
            _userFeedbackEvent.emit("Saved to your private journal")
        }
    }

    fun dismissSupportBanner() {
        viewModelScope.launch {
            dataStore.markSupportMessageShown()
        }
    }

    fun onMoodRechecked(newMood: String) {
        viewModelScope.launch {
            repository.updateMood(newMood)
            dataStore.resetSwipeCount()
            _userFeedbackEvent.emit("Tuning cards for feeling $newMood")
            _feedItemsFlow.value = repository.getFeedCards().first()
        }
    }

    fun dismissMoodRecheck() {
        viewModelScope.launch {
            dataStore.resetSwipeCount()
        }
    }
}
