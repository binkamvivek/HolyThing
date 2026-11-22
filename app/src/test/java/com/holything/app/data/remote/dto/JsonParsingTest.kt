package com.holything.app.data.remote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun testParseCardsPayload() {
        val jsonPayload = """
            {
              "version": "fc045ea5af6d868d",
              "updatedAt": "2026-11-22T18:01:08.016Z",
              "count": 2,
              "cards": [
                {
                  "id": "c0001",
                  "format": "verse",
                  "hook": "A 3,000-year-old bedtime prayer.",
                  "verse_ref": "Psalm 4:8",
                  "verse_text": "In peace I will both lay myself down and sleep.",
                  "translation": "WEB",
                  "insight": "Real peace means not carrying the night shift alone.",
                  "topics": ["anxiety", "prayer"],
                  "moods": ["anxious", "tired"],
                  "tone": "comforting",
                  "book": "Psalms",
                  "length": "short",
                  "series_id": null,
                  "series_order": null,
                  "reflection_prompt": "What worry can you set down?",
                  "active": true
                },
                {
                  "id": "c0003",
                  "format": "didyouknow",
                  "hook": "Why shepherds carried oil in the desert.",
                  "verse_ref": "Psalm 23:5",
                  "verse_text": "You anoint my head with oil.",
                  "translation": "WEB",
                  "insight": "Oil protected sheep from stinging flies.",
                  "topics": ["peace", "comfort"],
                  "moods": ["tired"],
                  "tone": "historical",
                  "book": "Psalms",
                  "length": "medium",
                  "series_id": "series_shepherd",
                  "series_order": 1,
                  "reflection_prompt": "What irritated you today?",
                  "active": true
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<SyncResponseDto>(jsonPayload)

        assertEquals("fc045ea5af6d868d", response.version)
        assertEquals(2, response.count)
        assertNotNull(response.cards)
        assertEquals(2, response.cards!!.size)

        val card1 = response.cards!![0]
        assertEquals("c0001", card1.id)
        assertEquals("verse", card1.format)
        assertEquals("Psalm 4:8", card1.verseRef)
        assertEquals(listOf("anxiety", "prayer"), card1.topics)
        assertEquals(listOf("anxious", "tired"), card1.moods)
        assertTrue(card1.active)

        // Convert to entity test
        val entity = card1.toEntity(syncedAt = 1000L)
        assertEquals("c0001", entity.id)
        assertEquals(1000L, entity.syncedAt)
        assertEquals(card1.verseText, entity.verseText)
    }

    @Test
    fun testParseUpToDateResponse() {
        val upToDateJson = """
            {
              "upToDate": true,
              "version": "fc045ea5af6d868d"
            }
        """.trimIndent()

        val response = json.decodeFromString<SyncResponseDto>(upToDateJson)

        assertEquals(true, response.upToDate)
        assertEquals("fc045ea5af6d868d", response.version)
        assertEquals(null, response.cards)
    }
}

