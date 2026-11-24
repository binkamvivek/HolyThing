package com.holything.app.domain.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalScoreCalculatorTest {

    @Test
    fun `estimateExpectedReadingTimeMs respects sensible minimum for short text`() {
        val ms = SignalScoreCalculator.estimateExpectedReadingTimeMs(
            hook = "Peace.",
            verseText = "Be still.",
            insight = "Rest."
        )
        assertEquals(SignalScoringConfig.MIN_EXPECTED_READING_TIME_MS, ms)
    }

    @Test
    fun `estimateExpectedReadingTimeMs scales with word count above minimum`() {
        val twentyWords = (1..20).joinToString(" ") { "word" }
        val ms = SignalScoreCalculator.estimateExpectedReadingTimeMs(
            hook = twentyWords,
            verseText = twentyWords,
            insight = twentyWords
        )
        // 60 words at 200 WPM (300ms/word) = 18_000ms
        assertEquals(18_000L, ms)
    }

    @Test
    fun `computeDwellRatio clamps between 0 and 2`() {
        assertEquals(0f, SignalScoreCalculator.computeDwellRatio(0L, 5000L), 0.001f)
        assertEquals(0f, SignalScoreCalculator.computeDwellRatio(-100L, 5000L), 0.001f)
        assertEquals(1.0f, SignalScoreCalculator.computeDwellRatio(5000L, 5000L), 0.001f)
        assertEquals(2.0f, SignalScoreCalculator.computeDwellRatio(25_000L, 5000L), 0.001f)
    }

    @Test
    fun `computeEngagementScore returns negative for fast skip with no action`() {
        val score = SignalScoreCalculator.computeEngagementScore(
            dwellRatio = 0.1f,
            isFastSkip = true,
            isScrollBack = false,
            actionsTaken = emptySet()
        )
        assertTrue(score < 0f)
        assertEquals(
            (SignalScoringConfig.WEIGHT_DWELL_LOW + SignalScoringConfig.WEIGHT_FAST_SKIP_NO_ACTION)
                .coerceIn(-1f, 1f),
            score,
            0.001f
        )
    }

    @Test
    fun `computeEngagementScore returns neutral for 0_3 to 0_6 dwell with no actions`() {
        val score = SignalScoreCalculator.computeEngagementScore(
            dwellRatio = 0.45f,
            isFastSkip = false,
            isScrollBack = false,
            actionsTaken = emptySet()
        )
        assertEquals(0f, score, 0.001f)
    }

    @Test
    fun `computeEngagementScore returns medium positive for Amen and 0_6 to 1_0 dwell`() {
        val score = SignalScoreCalculator.computeEngagementScore(
            dwellRatio = 0.8f,
            isFastSkip = false,
            isScrollBack = false,
            actionsTaken = setOf(SignalScoringConfig.ACTION_AMEN)
        )
        assertTrue(score > 0f && score <= 1f)
        assertEquals(
            SignalScoringConfig.WEIGHT_DWELL_MEDIUM + SignalScoringConfig.WEIGHT_AMEN,
            score,
            0.001f
        )
    }

    @Test
    fun `computeEngagementScore clamps at +1 for multiple strong positive signals`() {
        val score = SignalScoreCalculator.computeEngagementScore(
            dwellRatio = 1.5f,
            isFastSkip = false,
            isScrollBack = true,
            actionsTaken = setOf(
                SignalScoringConfig.ACTION_SAVE,
                SignalScoringConfig.ACTION_SHARE,
                SignalScoringConfig.ACTION_REFLECT
            )
        )
        assertEquals(1.0f, score, 0.001f)
    }
}
