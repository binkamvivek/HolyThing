package com.holything.app.ui.screens.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.holything.app.data.repository.ContentRepository
import com.holything.app.domain.model.Card
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SavedUiState(
    val items: List<Card> = emptyList(),
    val searchQuery: String = "",
    val isGridView: Boolean = false,
    val isLoading: Boolean = false
)

@HiltViewModel
class SavedViewModel @Inject constructor(
    private val repository: ContentRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _isGridView = MutableStateFlow(false)

    private val _activeJournalNote = MutableStateFlow("")
    val activeJournalNote: StateFlow<String> = _activeJournalNote.asStateFlow()

    val uiState: StateFlow<SavedUiState> = combine(
        repository.getSavedCards(),
        _searchQuery,
        _isGridView
    ) { cards, query, isGrid ->
        val filtered = if (query.isBlank()) {
            cards
        } else {
            val q = query.trim().lowercase()
            cards.filter { card ->
                card.hook.lowercase().contains(q) ||
                card.verseText.lowercase().contains(q) ||
                card.verseRef.lowercase().contains(q) ||
                card.insight.lowercase().contains(q) ||
                card.book.lowercase().contains(q) ||
                card.topics.any { it.lowercase().contains(q) }
            }
        }
        SavedUiState(
            items = filtered,
            searchQuery = query,
            isGridView = isGrid,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SavedUiState(isLoading = true)
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun toggleViewMode() {
        _isGridView.value = !_isGridView.value
    }

    fun unsaveCard(cardId: String) {
        viewModelScope.launch {
            repository.toggleSave(cardId)
        }
    }

    fun loadJournalNote(cardId: String) {
        viewModelScope.launch {
            val note = repository.getLatestJournalNote(cardId)
            _activeJournalNote.value = note?.noteText ?: ""
        }
    }

    fun onPrayed(cardId: String, noteText: String) {
        viewModelScope.launch {
            if (noteText.isNotBlank()) {
                repository.saveJournalNote(cardId, noteText.trim())
                _activeJournalNote.value = noteText.trim()
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
}
