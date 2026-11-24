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
import kotlinx.coroutines.flow.asStateFlow
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
    val isRefreshing: Boolean = false,
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
    private val isRefreshingFlow = MutableStateFlow(false)
    private val isOfflineFlow = MutableStateFlow(false)
    private val statusMessageFlow = MutableStateFlow("Loading scripture...")

    private val _userFeedbackEvent = MutableSharedFlow<String>()
    val userFeedbackEvent: SharedFlow<String> = _userFeedbackEvent.asSharedFlow()

    private val _targetScrollCardId = MutableStateFlow<String?>(null)
    val targetScrollCardId: StateFlow<String?> = _targetScrollCardId

    private val _activeJournalNote = MutableStateFlow<String>("")
    val activeJournalNote: StateFlow<String> = _activeJournalNote.asStateFlow()

    private var currentPageIndex = 0
    private var pageStartTime = System.currentTimeMillis()

    private val _feedItemsFlow = MutableStateFlow<List<FeedCardItem>>(emptyList())

    private data class SyncStatus(
        val loading: Boolean,
        val syncing: Boolean,
        val refreshing: Boolean,
        val offline: Boolean,
        val message: String
    )

    private val syncStatusFlow = combine(
        isLoadingFlow,
        isSyncingFlow,
        isRefreshingFlow,
        isOfflineFlow,
        statusMessageFlow
    ) { loading, syncing, refreshing, offline, msg ->
        SyncStatus(loading, syncing, refreshing, offline, msg)
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
            isRefreshing = status.refreshing,
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
                if (_feedItemsFlow.value.isEmpty()) {
                    _feedItemsFlow.value = newItems
                } else {
                    // Preserve the active session feed stability!
                    // Cards that user is reading or has scrolled past must never shift or disappear.
                    val existingIds = _feedItemsFlow.value.map { it.card.id }.toSet()
                    val toAppend = newItems.filter { it.card.id !in existingIds }
                    if (toAppend.isNotEmpty()) {
                        _feedItemsFlow.value = _feedItemsFlow.value + toAppend
                    }
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

    fun loadJournalNote(cardId: String) {
        viewModelScope.launch {
            val note = repository.getLatestJournalNote(cardId)
            _activeJournalNote.value = note?.noteText ?: ""
        }
    }

    fun onReflect(cardId: String) {
        viewModelScope.launch {
            repository.recordInteraction(cardId = cardId, type = "reflect", dwellMs = 0)
        }
        loadJournalNote(cardId)
    }

    fun onPrayed(cardId: String, noteText: String) {
        viewModelScope.launch {
            if (noteText.isNotBlank()) {
                repository.saveJournalNote(cardId, noteText.trim())
                _activeJournalNote.value = noteText.trim()
                _userFeedbackEvent.emit("Saved to your private journal")
            }
            repository.recordInteraction(cardId = cardId, type = "prayed", dwellMs = 0)
        }
    }

    fun saveJournalDraft(cardId: String, noteText: String) {
        if (noteText.isBlank()) return
        viewModelScope.launch {
            repository.saveJournalNote(cardId, noteText.trim())
            _activeJournalNote.value = noteText.trim()
        }
    }

    fun clearActiveJournalNote() {
        _activeJournalNote.value = ""
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
            val freshFeed = repository.getFeedCards().first()
            _feedItemsFlow.value = freshFeed
            currentPageIndex = 0
            _targetScrollCardId.value = freshFeed.firstOrNull()?.card?.id
        }
    }

    fun dismissMoodRecheck() {
        viewModelScope.launch {
            dataStore.resetSwipeCount()
        }
    }

    fun openCardInFeed(cardId: String) {
        viewModelScope.launch {
            val currentItems = _feedItemsFlow.value
            val existingIndex = currentItems.indexOfFirst { it.card.id == cardId }
            if (existingIndex >= 0) {
                _targetScrollCardId.value = cardId
            } else {
                val card = repository.getCardById(cardId)
                if (card != null) {
                    val newItem = FeedCardItem(
                        card = card,
                        recommendationReason = "From daily invitation"
                    )
                    _feedItemsFlow.value = listOf(newItem) + currentItems
                }
                _targetScrollCardId.value = cardId
            }
        }
    }

    fun onScrolledToTargetCard() {
        _targetScrollCardId.value = null
    }

    /**
     * Pull-to-refresh triggered from Home screen:
     * Syncs fresh content, picks a new distinct featured post, and prepares the feed.
     */
    fun onPullToRefresh() {
        viewModelScope.launch {
            isRefreshingFlow.value = true
            try {
                repository.syncContent()
            } catch (e: Exception) {
                // Ignore network errors in offline mode
            }

            val freshFeed = repository.getFeedCards().first()
            if (freshFeed.isNotEmpty()) {
                val currentTopId = _feedItemsFlow.value.firstOrNull()?.card?.id
                // Pick a different card so the home screen post visually changes!
                val candidates = freshFeed.filter { it.card.id != currentTopId }
                val newTop = if (candidates.isNotEmpty()) {
                    candidates.random()
                } else {
                    freshFeed.first()
                }
                val remaining = freshFeed.filter { it.card.id != newTop.card.id }
                _feedItemsFlow.value = listOf(newTop) + remaining
                currentPageIndex = 0
                _targetScrollCardId.value = newTop.card.id
            }
            kotlinx.coroutines.delay(400) // Natural settle time for pull-to-refresh spinner
            isRefreshingFlow.value = false
            _userFeedbackEvent.emit("New passage for you")
        }
    }
}
