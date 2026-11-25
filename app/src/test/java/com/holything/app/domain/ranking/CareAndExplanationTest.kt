package com.holything.app.domain.ranking

import com.holything.app.data.local.room.entity.SignalEventEntity
import com.holything.app.domain.model.Card
import com.holything.app.domain.model.OnboardingPreferences
import com.holything.app.domain.state.UserStateConfig
import com.holything.app.domain.state.UserStateEstimate
import com.holything.app.domain.state.UserStateEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareAndExplanationTest {

    private fun makeCard(
        id: String,
        format: String = "verse",
        topic: String = "hope",
        mood: String = "hopeful",
        tone: String = "comforting",
        book: String = "Psalms",
        seriesId: String? = null,
        seriesOrder: Int? = null
    ) = Card(
        id = id,
        format = format,
        hook = "Hook $id",
        verseRef = "$book 1:1",
        verseText = "The Lord is near.",
        translation = "WEB",
        insight = "Insight $id",
        topics = listOf(topic),
        moods = listOf(mood),
        tone = tone,
        book = book,
        length = "short",
        seriesId = seriesId,
        seriesOrder = seriesOrder,
        reflectionPrompt = "Reflect",
        active = true
    )

    @Test
    fun `card explanations reflect real reason and never claim to know user emotion`() {
        val wildcardCard = makeCard("w1", topic = "courage", mood = "sad", book = "Joshua")
        val wildcardReason = AdaptiveFeedRanker.buildReason(
            card = wildcardCard,
            userState = UserStateEstimate(moodBelief = mapOf("sad" to 0.8f)),
            topTopic = "hope",
            seriesState = SessionSeriesState(),
            isWildcard = true
        )
        assertEquals("Something new for you", wildcardReason)

        val seriesCard = makeCard("s2", seriesId = "david", seriesOrder = 2)
        val seriesReason = AdaptiveFeedRanker.buildReason(
            card = seriesCard,
            userState = UserStateEstimate(),
            topTopic = "hope",
            seriesState = SessionSeriesState(pendingNextParts = mapOf("david" to 2)),
            isWildcard = false
        )
        assertEquals("Continuing the story", seriesReason)

        val chipCard = makeCard("c1", mood = "lost")
        val chipReason = AdaptiveFeedRanker.buildReason(
            card = chipCard,
            userState = UserStateEstimate(),
            topTopic = "hope",
            seriesState = SessionSeriesState(),
            isWildcard = false,
            initialMoodHint = "lost"
        )
        assertEquals("Because you chose feeling lost", chipReason)

        val lingeringCard = makeCard("l1", topic = "hope", mood = "sad")
        val lingeringReason = AdaptiveFeedRanker.buildReason(
            card = lingeringCard,
            userState = UserStateEstimate(moodBelief = mapOf("sad" to 0.9f)),
            topTopic = "hope",
            seriesState = SessionSeriesState(),
            isWildcard = false
        )
        assertEquals("You've been lingering on hope", lingeringReason)
        assertFalse(lingeringReason.contains("you seem", ignoreCase = true))
        assertFalse(lingeringReason.contains("you feel", ignoreCase = true))
    }

    @Test
    fun `care rule activates on sad home chip or sustained heavy belief and enforces 60 percent comforting without challenging`() {
        // 1. Sad home chip activates care mode immediately
        val estimator = UserStateEstimator()
        val now = 1_700_000_000_000L
        estimator.startSession(initialMoodHint = "sad", hintTimestampMs = now, nowMs = now)
        assertTrue(estimator.state.value.isCareModeActive)

        // 2. Sustained sad/anxious/lost belief across 15 cards activates care mode
        val estimator2 = UserStateEstimator()
        estimator2.startSession(nowMs = now)
        assertFalse(estimator2.state.value.isCareModeActive)
        for (i in 0 until 25) {
            estimator2.onSignalEvent(
                event = SignalEventEntity(
                    cardId = "sad-$i",
                    positionInSession = i,
                    visibleTimeMs = 10_000L,
                    timeToFirstInteractionMs = null,
                    isFastSkip = false,
                    isScrollBack = false,
                    isReading = true,
                    isHesitation = false,
                    scrollSpeedBucket = "slow",
                    actionsTaken = emptyList(),
                    sessionId = "s",
                    hourOfDay = 14,
                    dayOfWeek = 1,
                    cardsSeenSoFar = i + 1,
                    dwellRatio = 1.5f,
                    engagementScore = 0.7f,
                    timestamp = now + (i * 15_000L)
                ),
                cardMoods = listOf("sad")
            )
        }
        assertTrue(estimator2.state.value.isCareModeActive)

        // 3. Ranker holds back challenging cards and ensures >= 60% comforting
        val books = listOf("Psalms", "John", "Isaiah", "Romans", "Proverbs")
        val formats = listOf("verse", "story", "didyouknow", "question")
        val topics = listOf("hope", "peace", "comfort", "grace", "love")
        val pool = (1..30).map { i ->
            val tone = when {
                i % 3 == 0 -> "challenging"
                i % 4 == 0 -> "reflective"
                else -> "comforting"
            }
            makeCard(
                id = "pool-$i",
                format = formats[i % formats.size],
                topic = topics[i % topics.size],
                mood = UserStateConfig.CARD_MOODS[i % UserStateConfig.CARD_MOODS.size],
                tone = tone,
                book = books[i % books.size]
            )
        }

        val seasonedProfile = LongTermBetaProfile(
            mapOf(
                (BetaDimension.TOPIC to "hope") to BetaParams(alpha = 15f, beta = 2f),
                (BetaDimension.FORMAT to "verse") to BetaParams(alpha = 15f, beta = 2f),
                (BetaDimension.FORMAT to "story") to BetaParams(alpha = 10f, beta = 3f)
            )
        )

        val ranked = AdaptiveFeedRanker.rankFeed(
            allCards = pool,
            userState = UserStateEstimate(
                moodBelief = mapOf("sad" to 0.7f).withDefault { 0.05f },
                confidence = 0.6f,
                isCareModeActive = true
            ),
            betaProfile = seasonedProfile,
            onboardingPrefs = OnboardingPreferences(),
            seenCardIds = emptySet(),
            amenedCardIds = emptySet(),
            savedCardIds = emptySet(),
            swipeCount = 25,
            seed = 123L
        ).filterNot { it.isPauseCard }

        assertTrue(ranked.none { it.card.tone == "challenging" })
        val comfortingRatio = ranked.count { it.card.tone == "comforting" }.toFloat() / ranked.size.toFloat()
        assertTrue("Expected >= 60% comforting cards, got $comfortingRatio", comfortingRatio >= 0.60f)
    }
}
