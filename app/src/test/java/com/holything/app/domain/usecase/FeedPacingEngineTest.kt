package com.holything.app.domain.usecase

import com.holything.app.domain.model.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPacingEngineTest {

    private fun createCard(id: String, format: String, topic: String, seriesId: String? = null, seriesOrder: Int? = null) = Card(
        id = id,
        format = format,
        hook = "Hook $id",
        verseRef = "John 1:1",
        verseText = "Text",
        translation = "WEB",
        insight = "Insight",
        topics = listOf(topic),
        moods = listOf("peaceful"),
        tone = "comforting",
        book = "John",
        length = "short",
        seriesId = seriesId,
        seriesOrder = seriesOrder,
        reflectionPrompt = "Reflect",
        active = true
    )

    @Test
    fun `excludes cards seen in last 30 days`() {
        val cards = listOf(
            createCard("c1", "verse", "peace"),
            createCard("c2", "verse", "hope"),
            createCard("c3", "verse", "love")
        )
        val seenIds = setOf("c1")

        val result = FeedPacingEngine.paceFeed(
            cards = cards,
            seenCardIds = seenIds,
            amenedCardIds = emptySet(),
            savedCardIds = emptySet()
        )

        assertEquals(2, result.size)
        assertFalse(result.any { it.card.id == "c1" })
        assertTrue(result.any { it.card.id == "c2" })
        assertTrue(result.any { it.card.id == "c3" })
    }

    @Test
    fun `resets pool if all cards were seen`() {
        val cards = listOf(
            createCard("c1", "verse", "peace"),
            createCard("c2", "verse", "hope")
        )
        val seenIds = setOf("c1", "c2")

        val result = FeedPacingEngine.paceFeed(
            cards = cards,
            seenCardIds = seenIds,
            amenedCardIds = emptySet(),
            savedCardIds = emptySet()
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `calculates series total count for story cards`() {
        val cards = listOf(
            createCard("c1", "story", "faith", seriesId = "david", seriesOrder = 1),
            createCard("c2", "story", "faith", seriesId = "david", seriesOrder = 2),
            createCard("c3", "story", "faith", seriesId = "david", seriesOrder = 3)
        )

        val result = FeedPacingEngine.paceFeed(
            cards = cards,
            seenCardIds = emptySet(),
            amenedCardIds = emptySet(),
            savedCardIds = emptySet()
        )

        assertEquals(3, result.size)
        result.forEach {
            assertEquals(3, it.seriesTotalCount)
        }
    }

    @Test
    fun `respects anti-repetition pacing constraint`() {
        // Create 4 verse cards and 4 question cards
        val cards = mutableListOf<Card>()
        for (i in 1..4) cards.add(createCard("v$i", "verse", "topic$i"))
        for (i in 1..4) cards.add(createCard("q$i", "question", "topic$i"))

        val result = FeedPacingEngine.paceFeed(
            cards = cards,
            seenCardIds = emptySet(),
            amenedCardIds = emptySet(),
            savedCardIds = emptySet()
        )

        // Verify no format appears 4 times in a row
        for (i in 0..(result.size - 4)) {
            val slice = result.subList(i, i + 4).map { it.card.format }
            assertFalse(
                "Format appeared 4 times in a row: $slice",
                slice.all { it == slice[0] }
            )
        }
    }
}
