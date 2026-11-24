package com.holything.app.data.repository

import android.content.Context
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.local.room.dao.CardDao
import com.holything.app.data.local.room.dao.InteractionDao
import com.holything.app.data.local.room.dao.SavedCardDao
import com.holything.app.data.remote.HolyThingApiService
import com.holything.app.data.remote.dto.CardDto
import com.holything.app.data.remote.dto.SyncResponseDto
import com.holything.app.domain.model.SyncResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {

    private val context: Context = mockk(relaxed = true)
    private val apiService: HolyThingApiService = mockk()
    private val cardDao: CardDao = mockk(relaxed = true)
    private val interactionDao: InteractionDao = mockk(relaxed = true)
    private val savedCardDao: SavedCardDao = mockk(relaxed = true)
    private val journalNoteDao: com.holything.app.data.local.room.dao.JournalNoteDao = mockk(relaxed = true)
    private val readingDayDao: com.holything.app.data.local.room.dao.ReadingDayDao = mockk(relaxed = true)
    private val betaProfileDao: com.holything.app.data.local.room.dao.BetaProfileDao = mockk(relaxed = true)
    private val userStateEstimator = com.holything.app.domain.state.UserStateEstimator()
    private val dataStore: UserPreferencesDataStore = mockk(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var repository: ContentRepository

    @Before
    fun setup() {
        repository = ContentRepository(
            context = context,
            apiService = apiService,
            cardDao = cardDao,
            interactionDao = interactionDao,
            savedCardDao = savedCardDao,
            journalNoteDao = journalNoteDao,
            readingDayDao = readingDayDao,
            betaProfileDao = betaProfileDao,
            userStateEstimator = userStateEstimator,
            dataStore = dataStore,
            json = json
        )
    }

    @Test
    fun `syncContent returns UpToDate when server indicates no changes`() = runTest {
        coEvery { dataStore.lastSyncedVersion } returns flowOf("v12345")
        coEvery { cardDao.getActiveCount() } returns 850
        coEvery { apiService.getCards("v12345") } returns SyncResponseDto(
            upToDate = true,
            version = "v12345"
        )

        val result = repository.syncContent()

        assertTrue(result.isSuccess)
        val syncResult = result.getOrNull()
        assertTrue(syncResult is SyncResult.UpToDate)
        assertEquals("v12345", (syncResult as SyncResult.UpToDate).version)
        coVerify(exactly = 0) { cardDao.upsertCards(any()) }
    }

    @Test
    fun `syncContent upserts cards and updates version when fresh cards received`() = runTest {
        coEvery { dataStore.lastSyncedVersion } returns flowOf(null)
        coEvery { cardDao.getActiveCount() } returns 0

        val newCards = listOf(
            CardDto(
                id = "c001",
                format = "verse",
                hook = "Hook",
                verseRef = "John 3:16",
                verseText = "For God so loved...",
                active = true
            )
        )
        coEvery { apiService.getCards(null) } returns SyncResponseDto(
            version = "new_v1",
            count = 1,
            cards = newCards
        )

        val result = repository.syncContent()

        assertTrue(result.isSuccess)
        val syncResult = result.getOrNull()
        assertTrue(syncResult is SyncResult.Synced)
        assertEquals(1, (syncResult as SyncResult.Synced).updatedCount)
        assertEquals("new_v1", syncResult.version)

        coVerify(exactly = 1) { cardDao.upsertCards(any()) }
        coVerify(exactly = 1) { dataStore.setLastSynced("new_v1", any()) }
    }

    @Test
    fun `syncContent returns Offline when network call fails and local cards exist`() = runTest {
        coEvery { dataStore.lastSyncedVersion } returns flowOf("v1")
        coEvery { cardDao.getActiveCount() } returns 850
        coEvery { apiService.getCards("v1") } throws IOException("No internet")

        val result = repository.syncContent()

        assertTrue(result.isSuccess)
        val syncResult = result.getOrNull()
        assertTrue(syncResult is SyncResult.Offline)
        assertEquals(850, (syncResult as SyncResult.Offline).localCount)
    }
}

