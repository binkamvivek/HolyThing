package com.holything.app.domain.state

import com.holything.app.data.local.room.entity.SignalEventEntity
import com.holything.app.domain.signal.SignalScoringConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserStateEstimatorTest {

    private fun fakeEvent(
        cardId: String = "c1",
        position: Int = 0,
        visibleTimeMs: Long = 5000L,
        isFastSkip: Boolean = false,
        isScrollBack: Boolean = false,
        isReading: Boolean = false,
        actionsTaken: List<String> = emptyList(),
        hourOfDay: Int = 14,
        dwellRatio: Float = 0.8f,
        engagementScore: Float = 0.2f,
        timestamp: Long = 1_700_000_000_000L
    ) = SignalEventEntity(
        cardId = cardId,
        positionInSession = position,
        visibleTimeMs = visibleTimeMs,
        timeToFirstInteractionMs = null,
        isFastSkip = isFastSkip,
        isScrollBack = isScrollBack,
        isReading = isReading,
        isHesitation = false,
        scrollSpeedBucket = "medium",
        actionsTaken = actionsTaken,
        sessionId = "test-session",
        hourOfDay = hourOfDay,
        dayOfWeek = 1,
        cardsSeenSoFar = position + 1,
        dwellRatio = dwellRatio,
        engagementScore = engagementScore,
        timestamp = timestamp
    )

    @Test
    fun `initial state without hint is uniform with zero confidence`() {
        val estimator = UserStateEstimator()
        val now = 1_700_000_000_000L
        estimator.startSession(nowMs = now)

        val state = estimator.state.value
        assertEquals(7, state.moodBelief.size)
        val expectedUniform = 1f / 7f
        UserStateConfig.CARD_MOODS.forEach { mood ->
            assertEquals(expectedUniform, state.moodBelief[mood] ?: 0f, 0.001f)
        }
        assertEquals(0f, state.confidence, 0.001f)
        assertEquals(SessionMode.READING, state.sessionMode)
    }

    @Test
    fun `home chip chosen today starts mood at roughly 50 percent and fades over 10 cards`() {
        val estimator = UserStateEstimator()
        val startMs = 1_700_000_000_000L
        estimator.startSession(
            initialMoodHint = "anxious",
            hintTimestampMs = startMs,
            nowMs = startMs
        )

        val initialAnxious = estimator.state.value.moodBelief["anxious"] ?: 0f
        assertEquals(0.50f, initialAnxious, 0.02f)

        // Feed 10 neutral-score events on non-anxious/no-mood cards so only hint decay acts
        for (i in 0 until 10) {
            estimator.onSignalEvent(
                event = fakeEvent(
                    cardId = "neutral-$i",
                    position = i,
                    engagementScore = 0f,
                    timestamp = startMs + (i * 10_000L)
                ),
                cardMoods = emptyList()
            )
        }

        val after10CardsAnxious = estimator.state.value.moodBelief["anxious"] ?: 0f
        val uniform = 1f / 7f
        val initialExtra = initialAnxious - uniform
        val remainingExtra = after10CardsAnxious - uniform
        // Extra weight should have roughly halved (plus small drift)
        assertTrue(remainingExtra < initialExtra * 0.65f)
        assertTrue(after10CardsAnxious > uniform)
    }

    @Test
    fun `lonely home chip splits initial 50 percent across sad and lost`() {
        val estimator = UserStateEstimator()
        val startMs = 1_700_000_000_000L
        estimator.startSession(
            initialMoodHint = "Lonely",
            hintTimestampMs = startMs,
            nowMs = startMs
        )

        val belief = estimator.state.value.moodBelief
        assertEquals(0.25f, belief["sad"] ?: 0f, 0.02f)
        assertEquals(0.25f, belief["lost"] ?: 0f, 0.02f)
        assertEquals(0.10f, belief["hopeful"] ?: 0f, 0.02f)
    }

    @Test
    fun `user dwelling on sad cards raises sad belief and confidence`() {
        val estimator = UserStateEstimator()
        val startMs = 1_700_000_000_000L
        estimator.startSession(nowMs = startMs)

        for (i in 0 until 12) {
            estimator.onSignalEvent(
                event = fakeEvent(
                    cardId = "sad-$i",
                    position = i,
                    visibleTimeMs = 12_000L,
                    isReading = true,
                    dwellRatio = 1.6f,
                    engagementScore = 0.7f,
                    timestamp = startMs + (i * 15_000L)
                ),
                cardMoods = listOf("sad")
            )
        }

        val state = estimator.state.value
        val sadProb = state.moodBelief["sad"] ?: 0f
        assertTrue("Expected sad probability to dominate, was $sadProb", sadProb > 0.35f)
        assertTrue("Expected confidence to rise above 0.1, was ${state.confidence}", state.confidence > 0.1f)
        assertEquals(SessionMode.DEEP, state.sessionMode)
        UserStateConfig.CARD_MOODS.forEach { mood ->
            assertTrue((state.moodBelief[mood] ?: 0f) >= UserStateConfig.MIN_MOOD_PROBABILITY * 0.5f)
        }
    }

    @Test
    fun `user skipping fast through everything enters skimming mode without flipping mood on one skip`() {
        val estimator = UserStateEstimator()
        val startMs = 1_700_000_000_000L
        estimator.startSession(
            initialMoodHint = "hopeful",
            hintTimestampMs = startMs,
            nowMs = startMs
        )
        val beforeSkipHopeful = estimator.state.value.moodBelief["hopeful"] ?: 0f

        // Single accidental fast skip on hopeful card should be capped
        estimator.onSignalEvent(
            event = fakeEvent(
                cardId = "skip-0",
                position = 0,
                visibleTimeMs = 400L,
                isFastSkip = true,
                dwellRatio = 0.05f,
                engagementScore = -0.85f,
                timestamp = startMs + 500L
            ),
            cardMoods = listOf("hopeful")
        )
        val afterOneSkipHopeful = estimator.state.value.moodBelief["hopeful"] ?: 0f
        assertTrue(
            "Single skip shifted hopeful too much: $beforeSkipHopeful -> $afterOneSkipHopeful",
            beforeSkipHopeful - afterOneSkipHopeful < 0.15f
        )

        // Continue fast skipping across 7 more cards
        for (i in 1 until 8) {
            estimator.onSignalEvent(
                event = fakeEvent(
                    cardId = "skip-$i",
                    position = i,
                    visibleTimeMs = 500L,
                    isFastSkip = true,
                    dwellRatio = 0.08f,
                    engagementScore = -0.85f,
                    timestamp = startMs + (i * 1000L)
                ),
                cardMoods = listOf(UserStateConfig.CARD_MOODS[i % UserStateConfig.CARD_MOODS.size])
            )
        }

        val state = estimator.state.value
        assertEquals(SessionMode.SKIMMING, state.sessionMode)
        assertEquals(1f, state.moodBelief.values.sum(), 0.001f)
        UserStateConfig.CARD_MOODS.forEach { mood ->
            assertTrue((state.moodBelief[mood] ?: 0f) > 0f)
        }
    }

    @Test
    fun `reflect or save actions classify session as deep and late night flag triggers after 11pm`() {
        val estimator = UserStateEstimator()
        estimator.startSession(nowMs = 1_700_000_000_000L)

        estimator.onSignalEvent(
            event = fakeEvent(
                cardId = "c-reflect",
                position = 0,
                visibleTimeMs = 6000L,
                actionsTaken = listOf(SignalScoringConfig.ACTION_REFLECT),
                hourOfDay = 23,
                dwellRatio = 0.8f,
                engagementScore = 0.7f
            ),
            cardMoods = listOf("grateful")
        )

        val lateState = estimator.state.value
        assertEquals(SessionMode.DEEP, lateState.sessionMode)
        assertTrue(lateState.isLateNight)

        estimator.onSignalEvent(
            event = fakeEvent(
                cardId = "c-day",
                position = 1,
                visibleTimeMs = 5000L,
                hourOfDay = 10,
                dwellRatio = 0.75f,
                engagementScore = 0.2f
            ),
            cardMoods = listOf("hopeful")
        )
        assertFalse(estimator.state.value.isLateNight)
    }

    @Test
    fun `belief drifts slowly toward baseline over neutral cards`() {
        val estimator = UserStateEstimator()
        val baseline = UserStateConfig.CARD_MOODS.associateWith { mood ->
            if (mood == "grateful") 0.40f else 0.10f
        }
        val startMs = 1_700_000_000_000L
        estimator.startSession(
            longTermBaseline = baseline,
            initialMoodHint = "anxious",
            hintTimestampMs = startMs,
            nowMs = startMs
        )

        val initialGrateful = estimator.state.value.moodBelief["grateful"] ?: 0f
        for (i in 0 until 25) {
            estimator.onSignalEvent(
                event = fakeEvent(
                    cardId = "n-$i",
                    position = i,
                    engagementScore = 0f,
                    timestamp = startMs + (i * 60_000L)
                ),
                cardMoods = emptyList()
            )
        }
        val laterGrateful = estimator.state.value.moodBelief["grateful"] ?: 0f
        assertTrue("Expected grateful to drift back toward baseline", laterGrateful > initialGrateful)
    }
}
