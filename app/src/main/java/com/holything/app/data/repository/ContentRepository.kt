package com.holything.app.data.repository

import android.content.Context
import com.holything.app.core.Constants
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.local.room.dao.CardDao
import com.holything.app.data.remote.HolyThingApiService
import com.holything.app.data.remote.dto.SyncResponseDto
import com.holything.app.domain.model.Card
import com.holything.app.domain.model.SyncResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: HolyThingApiService,
    private val cardDao: CardDao,
    private val dataStore: UserPreferencesDataStore,
    private val json: Json
) {
    val cards: Flow<List<Card>> = cardDao.getAllActiveCards().map { entities ->
        entities.map { it.toDomain() }
    }

    val cardsCount: Flow<Int> = cardDao.getActiveCardsCount()

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

    suspend fun clearAllData() {
        cardDao.clearAll()
        dataStore.clearAll()
    }
}

