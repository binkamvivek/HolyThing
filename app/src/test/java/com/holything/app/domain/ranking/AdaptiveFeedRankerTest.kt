package com.holything.app.domain.ranking

import com.holything.app.domain.model.Card
import com.holything.app.domain.model.OnboardingPreferences
import com.holything.app.domain.state.SessionMode
import com.holything.app.domain.state.UserStateConfig
import com.holything.app.domain.state.UserStateEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveFeedRankerTest {

    private fun makeCard(
        id: String,
        format: String = "verse",
        topic: String = "peace",
        mood: String = "hopeful",
        book: String = "Psalms",
        length: String = "short",
        tone: String = "comforting",
        seriesId: String? = null,
        seriesOrder: Int? = null
    ) = Card(
        id = id,
        format = format,
        hook = "Hook for $id",
        verseRef = "$book 1:1",
        verseText = "Be still and know.",
        translation = "WEB",
        insight = "Insight for $id",
        topics = listOf(topic),
        moods = listOf(mood),
        tone = tone,
        book = book,
        length = length,
        seriesId = seriesId,
        seriesOrder = seriesOrder,
        reflectionPrompt = "Reflect on $id",
        active = true
    )

    private fun seasonedProfile(): LongTermBetaProfile {
        val entries = mutableMapOf<Pair<String, String>, BetaParams>()
        listOf("verse", "story", "didyouknow", "question").forEach { f ->
            entries[BetaDimension.FORMAT to f] = BetaParams(alpha = 10f, beta = 3f)
        }
        entries[BetaDimension.TOPIC to "peace"] = BetaParams(alpha = 18f, beta = 2f)
        return LongTermBetaProfile(entries)
    }

    @Test
    fun `fatigue rules prevent more than 3 same format or topic and 3 same book in a row`() {
        val formats = listOf("verse", "story", "didyouknow", "question")
        val topics = listOf("peace", "anxiety", "hope", "grace", "wisdom")
        val books = listOf("Psalms", "John", "Romans", "Proverbs", "Isaiah")

        val cards = (1..60).map { i ->
            makeCard(
                id = "c-$i",
                format = formats[i % formats.size],
                topic = topics[i % topics.size],
                book = books[i % books.size],
                mood = UserStateConfig.CARD_MOODS[i % UserStateConfig.CARD_MOODS.size]
            )
        }

        val ranked = AdaptiveFeedRanker.rankFeed(
            allCards = cards,
            userState = UserStateEstimate(confidence = 0.7f, sessionMode = SessionMode.READING),
            betaProfile = seasonedProfile(),
            onboardingPrefs = OnboardingPreferences(),
            seenCardIds = emptySet(),
            amenedCardIds = emptySet(),
            savedCardIds = emptySet(),
            swipeCount = 25,
            seed = 42L
        ).filterNot { it.isPauseCard }

        assertEquals(60, ranked.size)

        for (i in 3 until ranked.size) {
            val window4 = ranked.subList(i - 3, i + 1).map { it.card }
            assertFalse(
                "4 consecutive same format at $i",
                window4.all { it.format == window4[0].format }
            )
            assertFalse(
                "4 consecutive same topic at $i",
                window4.all { it.topics.firstOrNull() == window4[0].topics.firstOrNull() }
            )
        }

        for (i in 2 until ranked.size) {
            val window3 = ranked.subList(i - 2, i + 1).map { it.card }
            assertFalse(
                "3 consecutive same book at $i",
                window3.all { it.book == window3[0].book }
            )
        }
    }

    @Test
    fun `70-20-10 mix holds at high confidence and raises exploration when confidence is low`() {
        val (bestHigh, adjHigh, wildHigh) = AdaptiveFeedRanker.computeMixRatios(confidence = 0.9f)
        assertEquals(0.70f, bestHigh, 0.001f)
        assertEquals(0.20f, adjHigh, 0.001f)
        assertEquals(0.10f, wildHigh, 0.001f)

        val (bestLow, adjLow, wildLow) = AdaptiveFeedRanker.computeMixRatios(confidence = 0.0f)
        assertTrue(wildLow >= 0.10f)
        assertTrue(wildLow + adjLow > wildHigh + adjHigh)
        assertEquals(1.0f, bestLow + adjLow + wildLow, 0.001f)
    }

    @Test
    fun `series continuation places part N+1 within next 1 to 3 cards and fast skip drops series`() {
        val regularCards = (1..20).map { i ->
            makeCard(
                id = "reg-$i",
                format = if (i % 2 == 0) "verse" else "didyouknow",
                topic = "peace",
                book = if (i % 2 == 0) "Psalms" else "Proverbs"
            )
        }
        val part2 = makeCard(
            id = "story-p2",
            format = "story",
            topic = "faith",
            book = "Genesis",
            seriesId = "abraham",
            seriesOrder = 2
        )
        val part3 = makeCard(
            id = "story-p3",
            format = "story",
            topic = "faith",
            book = "Genesis",
            seriesId = "abraham",
            seriesOrder = 3
        )
        val allCards = regularCards + part2 + part3

        // When dwellRatio > 0.8 on part 1, part 2 must appear within next 1..3 cards
        val withContinuation = AdaptiveFeedRanker.rankFeed(
            allCards = allCards,
            userState = UserStateEstimate(confidence = 0.6f),
            betaProfile = seasonedProfile(),
            onboardingPrefs = OnboardingPreferences(),
            seenCardIds = emptySet(),
            amenedCardIds = emptySet(),
            savedCardIds = emptySet(),
            seriesState = SessionSeriesState(pendingNextParts = mapOf("abraham" to 2)),
            swipeCount = 20,
            seed = 7L
        ).filterNot { it.isPauseCard }

        val part2Index = withContinuation.indexOfFirst { it.card.id == "story-p2" }
        assertTrue("Expected part 2 within first 3 cards, was at $part2Index", part2Index in 0..2)

        // When series is dropped after a fast skip, no cards from that series appear
        val afterDrop = AdaptiveFeedRanker.rankFeed(
            allCards = allCards,
            userState = UserStateEstimate(confidence = 0.6f),
            betaProfile = seasonedProfile(),
            onboardingPrefs = OnboardingPreferences(),
            seenCardIds = emptySet(),
            amenedCardIds = emptySet(),
            savedCardIds = emptySet(),
            seriesState = SessionSeriesState(droppedSeriesIds = setOf("abraham")),
            swipeCount = 20,
            seed = 7L
        ).filterNot { it.isPauseCard }

        assertTrue(afterDrop.none { it.card.seriesId == "abraham" })
    }

    @Test
    fun `30-day exclusion hides seen cards until pool runs out`() {
        val cards = (1..10).map { i ->
            makeCard(id = "c-$i", book = "Book$i")
        }
        val seenIds = setOf("c-1", "c-2", "c-3", "c-4", "c-5")

        val rankedUnseen = AdaptiveFeedRanker.rankFeed(
            allCards = cards,
            userState = UserStateEstimate(),
            betaProfile = seasonedProfile(),
            onboardingPrefs = OnboardingPreferences(),
            seenCardIds = seenIds,
            amenedCardIds = emptySet(),
            savedCardIds = emptySet(),
            swipeCount = 20,
            seed = 99L
        ).filterNot { it.isPauseCard }

        assertEquals(5, rankedUnseen.size)
        assertTrue(rankedUnseen.none { it.card.id in seenIds })

        // When all cards have been seen, fall back to full pool instead of returning empty
        val allSeen = cards.map { it.id }.toSet()
        val fallbackRanked = AdaptiveFeedRanker.rankFeed(
            allCards = cards,
            userState = UserStateEstimate(),
            betaProfile = seasonedProfile(),
            onboardingPrefs = OnboardingPreferences(),
            seenCardIds = allSeen,
            amenedCardIds = emptySet(),
            savedCardIds = emptySet(),
            swipeCount = 20,
            seed = 99L
        ).filterNot { it.isPauseCard }

        assertEquals(10, fallbackRanked.size)
    }
}
