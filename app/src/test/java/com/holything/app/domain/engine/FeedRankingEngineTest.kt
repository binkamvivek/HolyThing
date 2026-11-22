package com.holything.app.domain.engine

import com.holything.app.domain.model.Card
import com.holything.app.domain.model.OnboardingPreferences
import com.holything.app.domain.model.TasteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRankingEngineTest {

    private fun makeCard(
        id: String,
        format: String,
        topic: String,
        mood: String = "hopeful",
        tone: String = "inspiring",
        seriesId: String? = null,
        seriesOrder: Int? = null
    ) = Card(
        id = id,
        format = format,
        hook = "Hook $id",
        verseRef = "Psalm 1:1",
        verseText = "Scripture text $id",
        translation = "WEB",
        insight = "Insight $id",
        topics = listOf(topic),
        moods = listOf(mood),
        tone = tone,
        book = "Psalms",
        length = "short",
        seriesId = seriesId,
        seriesOrder = seriesOrder,
        reflectionPrompt = "Prompt",
        active = true
    )

    @Test
    fun `repeat limits - never more than 3 of the same format or topic in a row`() {
        val cards = mutableListOf<Card>()
        // Generate a large pool with repeating formats
        for (i in 1..25) cards.add(makeCard("v$i", "verse", "peace"))
        for (i in 1..25) cards.add(makeCard("s$i", "story", "peace"))
        for (i in 1..25) cards.add(makeCard("d$i", "didyouknow", "hope"))

        val taste = TasteProfile(topicScores = mapOf("peace" to 5f))
        val onboarding = OnboardingPreferences(mood = "peaceful", goal = "peace")

        val result = FeedRankingEngine.rankFeed(
            allCards = cards,
            tasteProfile = taste,
            onboardingPrefs = onboarding,
            seenCardIds = emptySet(),
            amenedCardIds = emptySet(),
            savedCardIds = emptySet(),
            swipeCount = 20
        )

        // Check format anti-repetition: no format appears 4 times in a row
        for (i in 0..(result.size - 4)) {
            val formatSlice = result.subList(i, i + 4).map { it.card.format }
            assertFalse(
                "Format appeared 4 times in a row at index $i: $formatSlice",
                formatSlice.all { it == formatSlice[0] }
            )
        }
    }

    @Test
    fun `story continuation - serves next part within the next few swipes`() {
        val part1 = makeCard("s1", "story", "faith", seriesId = "david", seriesOrder = 1)
        val part2 = makeCard("s2", "story", "faith", seriesId = "david", seriesOrder = 2)
        val otherCards = (3..20).map { makeCard("c$it", "verse", "hope") }
        val pool = listOf(part2) + otherCards

        val taste = TasteProfile(topicScores = mapOf("faith" to 4f))
        val onboarding = OnboardingPreferences()

        val result = FeedRankingEngine.rankFeed(
            allCards = pool,
            tasteProfile = taste,
            onboardingPrefs = onboarding,
            seenCardIds = setOf("s1"),
            amenedCardIds = emptySet(),
            savedCardIds = emptySet(),
            lastInteractedCard = part1,
            swipeCount = 20
        )

        val part2Index = result.indexOfFirst { it.card.id == "s2" }
        assertTrue("Part 2 was not found in feed", part2Index != -1)
        assertTrue("Part 2 was placed at index $part2Index, expected within first 4 cards", part2Index in 0..4)
    }

    @Test
    fun `cold start - immediately prioritizes chosen mood and comforting tone for heavy mood`() {
        val anxiousCard = makeCard("c_anx", "verse", "peace", mood = "anxious", tone = "comforting")
        val cheerfulCard = makeCard("c_joy", "testimony", "work", mood = "joyful", tone = "inspiring")
        val pool = listOf(cheerfulCard, anxiousCard)

        val onboarding = OnboardingPreferences(mood = "anxious", isHeavyMood = true)
        val taste = TasteProfileEngine.createFromOnboarding(onboarding)

        val result = FeedRankingEngine.rankFeed(
            allCards = pool,
            tasteProfile = taste,
            onboardingPrefs = onboarding,
            seenCardIds = emptySet(),
            amenedCardIds = emptySet(),
            savedCardIds = emptySet(),
            swipeCount = 0 // Cold start
        )

        assertNotNull(result)
        assertEquals(2, result.size)
        // First card must be the comforting/anxious card
        assertEquals("c_anx", result.first().card.id)
    }

    @Test
    fun `70-20-10 distribution over many runs`() {
        // Pool with:
        // - Close Match: topic "peace"
        // - Adjacent: topic "anxiety" (adjacent to peace)
        // - Wildcard: topic "work"
        val closeCards = (1..70).map { makeCard("close_$it", "verse", "peace") }
        val adjacentCards = (1..30).map { makeCard("adj_$it", "didyouknow", "anxiety") }
        val wildcardCards = (1..20).map { makeCard("wild_$it", "story", "work") }
        val pool = closeCards + adjacentCards + wildcardCards

        val taste = TasteProfile(
            topicScores = mapOf("peace" to 8f),
            formatScores = mapOf("verse" to 5f)
        )
        val onboarding = OnboardingPreferences(goal = "peace")

        val result = FeedRankingEngine.rankFeed(
            allCards = pool,
            tasteProfile = taste,
            onboardingPrefs = onboarding,
            seenCardIds = emptySet(),
            amenedCardIds = emptySet(),
            savedCardIds = emptySet(),
            swipeCount = 20
        )

        // Over the first 30 cards, check the proportion
        val sampleSize = 30
        val sample = result.take(sampleSize)

        val closeCount = sample.count { it.card.topics.contains("peace") }
        val adjCount = sample.count { it.card.topics.contains("anxiety") }
        val wildCount = sample.count { it.card.topics.contains("work") }

        // Expect ~70% close (18-24), ~20% adjacent (4-8), ~10% wildcard (1-5)
        assertTrue("Close match count was $closeCount, expected >= 16 out of $sampleSize", closeCount >= 16)
        assertTrue("Adjacent count was $adjCount, expected >= 2 out of $sampleSize", adjCount >= 2)
        assertTrue("Wildcard count was $wildCount, expected >= 1 out of $sampleSize", wildCount >= 1)
    }
}
