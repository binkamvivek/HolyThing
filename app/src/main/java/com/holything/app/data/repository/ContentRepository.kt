package com.holything.app.data.repository

import android.content.Context
import com.holything.app.core.Constants
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.local.room.dao.CardDao
import com.holything.app.data.remote.HolyThingApiService
import com.holything.app.data.remote.dto.SyncResponseDto
import com.holything.app.domain.model.Card
import com.holything.app.data.local.room.dao.InteractionDao
import com.holything.app.data.local.room.dao.SavedCardDao
import com.holything.app.data.local.room.entity.InteractionEntity
import com.holything.app.data.local.room.entity.SavedCardEntity
import com.holything.app.domain.model.FeedCardItem
import com.holything.app.domain.model.SyncResult
import com.holything.app.domain.engine.FeedRankingEngine
import com.holything.app.domain.engine.TasteProfileEngine
import com.holything.app.domain.model.OnboardingPreferences
import com.holything.app.domain.model.TasteProfile
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.combine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStreamReader
import com.holything.app.data.local.room.dao.JournalNoteDao
import com.holything.app.data.local.room.dao.ReadingDayDao
import com.holything.app.data.local.room.entity.JournalNoteEntity
import com.holything.app.data.local.room.entity.ReadingDayEntity
import com.holything.app.domain.engine.StreakCalculator
import com.holything.app.domain.model.StreakInfo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: HolyThingApiService,
    private val cardDao: CardDao,
    private val interactionDao: InteractionDao,
    private val savedCardDao: SavedCardDao,
    private val journalNoteDao: JournalNoteDao,
    private val readingDayDao: ReadingDayDao,
    private val dataStore: UserPreferencesDataStore,
    private val json: Json
) {
    val cards: Flow<List<Card>> = cardDao.getAllActiveCards().map { entities ->
        entities.map { it.toDomain() }
    }

    val cardsCount: Flow<Int> = cardDao.getActiveCardsCount()

    val amenedCardIds: Flow<List<String>> = interactionDao.getAmenedCardIds()
    val savedCardIds: Flow<List<String>> = savedCardDao.getAllSavedCardIds()

    private val sessionStartTime: Long = System.currentTimeMillis()
    private var lastInteractedCard: Card? = null

    val distinctMoods: Flow<List<String>> = cards.map { cardList ->
        val moods = cardList.flatMap { it.moods }.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().sorted()
        if (moods.isNotEmpty()) moods else OnboardingPreferences.DEFAULT_MOODS
    }

    val streakInfo: Flow<StreakInfo> = combine(
        readingDayDao.getAllReadingDays(),
        dataStore.tasteProfileJson
    ) { readingDays, tasteJson ->
        val tasteProfile = tasteJson?.let {
            try { json.decodeFromString<TasteProfile>(it) } catch (e: Exception) { null }
        }
        val topTopics = tasteProfile?.topicScores?.entries
            ?.sortedByDescending { it.value }
            ?.take(3)
            ?.map { it.key.replaceFirstChar { c -> c.uppercase() } } ?: emptyList()
        StreakCalculator.calculate(
            readingDays = readingDays,
            favoriteTopics = topTopics
        )
    }

    fun getSavedCards(): Flow<List<Card>> {
        return combine(cards, savedCardIds) { allCards, ids ->
            val idSet = ids.toSet()
            allCards.filter { it.id in idSet }
        }
    }

    /**
     * Emits ranked feed with 70/20/10 distribution, style testing, story continuation,
     * and anti-repetition rules applied.
     */
    fun getFeedCards(): Flow<List<FeedCardItem>> {
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        return combine(
            cards,
            dataStore.onboardingPreferences,
            dataStore.initialMoodHint
        ) { rawCards, onboardingPrefs, moodHint ->
            if (rawCards.isEmpty()) return@combine emptyList<FeedCardItem>()
            // Exclude cards seen prior to this session, but keep cards seen during this session
            val seenCardIds = interactionDao.getRecentlySeenCardIdsBefore(thirtyDaysAgo, sessionStartTime).toSet()
            val amenedList = interactionDao.getAmenedCardIds().first()
            val savedList = savedCardDao.getAllSavedCardIds().first()
            val tasteJson = dataStore.tasteProfileJson.first()
            val tasteProfile = tasteJson?.let {
                try { json.decodeFromString<TasteProfile>(it) } catch (e: Exception) { null }
            } ?: TasteProfileEngine.createFromOnboarding(onboardingPrefs)

            val currentSwipeCount = dataStore.swipeCount.first()

            FeedRankingEngine.rankFeed(
                allCards = rawCards,
                tasteProfile = tasteProfile,
                onboardingPrefs = onboardingPrefs,
                seenCardIds = seenCardIds,
                amenedCardIds = amenedList.toSet(),
                savedCardIds = savedList.toSet(),
                lastInteractedCard = lastInteractedCard,
                swipeCount = currentSwipeCount,
                initialMoodHint = moodHint
            )
        }
    }

    suspend fun recordInteraction(cardId: String, type: String, dwellMs: Long = 0) {
        withContext(Dispatchers.IO) {
            interactionDao.insertInteraction(
                InteractionEntity(
                    cardId = cardId,
                    type = type,
                    dwellMs = dwellMs
                )
            )

            // Update on-device taste profile
            val cardEntity = cardDao.getCardById(cardId)
            if (cardEntity != null) {
                val card = cardEntity.toDomain()
                lastInteractedCard = card

                val onboardingPrefs = dataStore.onboardingPreferences.first()
                val tasteJson = dataStore.tasteProfileJson.first()
                val currentProfile = tasteJson?.let {
                    try { json.decodeFromString<TasteProfile>(it) } catch (e: Exception) { null }
                } ?: TasteProfileEngine.createFromOnboarding(onboardingPrefs)

                val updatedProfile = TasteProfileEngine.updateWithInteraction(
                    current = currentProfile,
                    card = card,
                    interactionType = type,
                    dwellMs = dwellMs
                )
                dataStore.saveTasteProfileJson(json.encodeToString(updatedProfile))
            }

            if (type in listOf("dwell", "fast_skip")) {
                dataStore.incrementSwipeCount()
            }

            if (type == "dwell" || type in listOf("amen", "save", "prayed")) {
                trackCardRead()
            }
        }
    }

    private suspend fun trackCardRead() {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val existing = readingDayDao.getReadingDay(todayStr)
        val newCount = (existing?.cardsReadCount ?: 0) + 1
        readingDayDao.upsertReadingDay(
            ReadingDayEntity(
                date = todayStr,
                cardsReadCount = newCount,
                streakCount = 0
            )
        )
    }

    suspend fun saveJournalNote(cardId: String, text: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = journalNoteDao.getLatestNoteForCard(cardId)
        if (existing != null) {
            journalNoteDao.upsertNote(
                existing.copy(
                    noteText = text,
                    updatedAt = now
                )
            )
        } else {
            journalNoteDao.upsertNote(
                JournalNoteEntity(
                    cardId = cardId,
                    noteText = text,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    suspend fun getLatestJournalNote(cardId: String): JournalNoteEntity? = withContext(Dispatchers.IO) {
        journalNoteDao.getLatestNoteForCard(cardId)
    }

    fun getJournalNote(cardId: String): Flow<JournalNoteEntity?> =
        journalNoteDao.getNoteForCard(cardId)

    suspend fun toggleAmen(cardId: String): Boolean = withContext(Dispatchers.IO) {
        val alreadyAmened = interactionDao.isCardAmened(cardId)
        if (alreadyAmened) {
            interactionDao.removeAmen(cardId)
            false
        } else {
            interactionDao.insertInteraction(
                InteractionEntity(
                    cardId = cardId,
                    type = "amen",
                    dwellMs = 0
                )
            )
            true
        }
    }

    suspend fun toggleSave(cardId: String): Boolean = withContext(Dispatchers.IO) {
        val alreadySaved = savedCardDao.isCardSavedDirect(cardId)
        if (alreadySaved) {
            savedCardDao.removeSavedCard(cardId)
            false
        } else {
            savedCardDao.saveCard(SavedCardEntity(cardId = cardId))
            true
        }
    }

    suspend fun syncContent(): Result<SyncResult> = withContext(Dispatchers.IO) {
        val localVersion = dataStore.lastSyncedVersion.first()
        val localCount = cardDao.getActiveCount()

        try {
            val response = apiService.getCards(sinceVersion = localVersion)

            // 1. If backend indicates nothing changed
            if (response.upToDate == true) {
                return@withContext Result.success(
                    SyncResult.UpToDate(version = response.version ?: localVersion ?: "current")
                )
            }

            // 2. If new cards were received
            val incomingCards = response.cards
            if (!incomingCards.isNullOrEmpty()) {
                val now = System.currentTimeMillis()
                val entities = incomingCards.map { it.toEntity(syncedAt = now) }
                
                // Upsert all received cards
                cardDao.upsertCards(entities)

                // Deactivate / remove any cards not present in the active feed
                val activeIds = entities.filter { it.active }.map { it.id }
                if (activeIds.isNotEmpty()) {
                    cardDao.deleteCardsNotIn(activeIds)
                }

                // Update version & sync timestamp
                val newVersion = response.version ?: System.currentTimeMillis().toString()
                dataStore.setLastSynced(newVersion, now)

                return@withContext Result.success(
                    SyncResult.Synced(updatedCount = entities.size, version = newVersion)
                )
            }

            // If empty response returned without error
            return@withContext Result.success(
                SyncResult.Offline(localCount = localCount, version = localVersion)
            )

        } catch (e: Exception) {
            // Network failed or offline: fallback to local database or seed assets
            if (localCount == 0) {
                // If local database is completely empty on first launch, load fallback asset
                val fallbackLoaded = loadFallbackFromAssets()
                if (fallbackLoaded != null) {
                    return@withContext Result.success(fallbackLoaded)
                }
            }

            // Return offline success with existing local data
            return@withContext Result.success(
                SyncResult.Offline(localCount = localCount, version = localVersion)
            )
        }
    }

    private suspend fun loadFallbackFromAssets(): SyncResult.FallbackLoaded? {
        return try {
            val inputStream = context.assets.open(Constants.FALLBACK_ASSET_NAME)
            val jsonString = InputStreamReader(inputStream).use { it.readText() }
            val fallbackResponse = json.decodeFromString<SyncResponseDto>(jsonString)
            
            val cardsList = fallbackResponse.cards
            if (!cardsList.isNullOrEmpty()) {
                val now = System.currentTimeMillis()
                val entities = cardsList.map { it.toEntity(syncedAt = now) }
                cardDao.upsertCards(entities)

                val version = fallbackResponse.version ?: "seed-1.0"
                dataStore.setLastSynced(version, now)

                SyncResult.FallbackLoaded(count = entities.size, version = version)
            } else {
                null
            }
        } catch (ex: Exception) {
            null
        }
    }

    suspend fun getActiveCardsList(): List<Card> = withContext(Dispatchers.IO) {
        cardDao.getActiveCardsList().map { it.toDomain() }
    }

    suspend fun getCardById(cardId: String): Card? = withContext(Dispatchers.IO) {
        cardDao.getCardById(cardId)?.toDomain()
    }

    val initialMoodHint: Flow<String?> = dataStore.initialMoodHint

    suspend fun setInitialMoodHint(mood: String?) = withContext(Dispatchers.IO) {
        dataStore.setInitialMoodHint(mood)
    }

    suspend fun updateMood(newMood: String) = withContext(Dispatchers.IO) {
        val current = dataStore.onboardingPreferences.first()
        val updated = current.copy(mood = newMood, isHeavyMood = newMood.lowercase() in OnboardingPreferences.HEAVY_MOODS)
        dataStore.saveOnboardingPreferences(updated)
        val newProfile = TasteProfileEngine.createFromOnboarding(updated)
        dataStore.saveTasteProfileJson(json.encodeToString(newProfile))
    }

    suspend fun clearAllData() {
        cardDao.clearAll()
        interactionDao.clearAll()
        savedCardDao.clearAll()
        journalNoteDao.clearAll()
        readingDayDao.clearAll()
        dataStore.clearAll()
    }
}

