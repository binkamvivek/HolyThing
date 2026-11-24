package com.holything.app.domain.signal

/**
 * Tunable configuration for signal thresholds, reading time estimation,
 * and engagement score weights.
 */
object SignalScoringConfig {
    // Visibility & timing thresholds
    const val MIN_VISIBLE_FRACTION: Float = 0.60f
    const val FAST_SKIP_THRESHOLD_MS: Long = 1200L
    const val READING_STILL_THRESHOLD_MS: Long = 8000L

    // Reading speed estimation
    const val READING_WORDS_PER_MINUTE: Int = 200
    const val MIN_EXPECTED_READING_TIME_MS: Long = 3000L

    // Dwell ratio bounds
    const val DWELL_RATIO_MIN: Float = 0f
    const val DWELL_RATIO_MAX: Float = 2f

    // Dwell ratio tier cutoffs
    const val DWELL_HIGH_CUTOFF: Float = 1.0f
    const val DWELL_MEDIUM_CUTOFF: Float = 0.6f
    const val DWELL_NEUTRAL_CUTOFF: Float = 0.3f

    // Strong positive weights
    const val WEIGHT_SAVE: Float = 0.45f
    const val WEIGHT_SHARE: Float = 0.50f
    const val WEIGHT_REFLECT: Float = 0.50f
    const val WEIGHT_SCROLL_BACK: Float = 0.35f
    const val WEIGHT_DWELL_HIGH: Float = 0.40f

    // Medium positive weights
    const val WEIGHT_AMEN: Float = 0.25f
    const val WEIGHT_DWELL_MEDIUM: Float = 0.20f

    // Neutral weight
    const val WEIGHT_DWELL_NEUTRAL: Float = 0.0f

    // Negative weights
    const val WEIGHT_FAST_SKIP_NO_ACTION: Float = -0.50f
    const val WEIGHT_DWELL_LOW: Float = -0.35f

    // Engagement score bounds
    const val SCORE_MIN: Float = -1f
    const val SCORE_MAX: Float = 1f

    // Action names
    const val ACTION_AMEN = "Amen"
    const val ACTION_SAVE = "Save"
    const val ACTION_SHARE = "Share"
    const val ACTION_REFLECT = "Reflect"
}

/**
 * Pure, testable calculator for expected reading time, dwell ratio, and engagement score.
 */
object SignalScoreCalculator {

    private val WHITESPACE_REGEX = Regex("\\s+")

    fun countWords(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return trimmed.split(WHITESPACE_REGEX).size
    }

    fun estimateExpectedReadingTimeMs(
        hook: String,
        verseText: String,
        insight: String
    ): Long {
        val totalWords = countWords(hook) + countWords(verseText) + countWords(insight)
        val msPerWord = 60_000L / SignalScoringConfig.READING_WORDS_PER_MINUTE
        val estimatedMs = totalWords * msPerWord
        return maxOf(SignalScoringConfig.MIN_EXPECTED_READING_TIME_MS, estimatedMs)
    }

    fun computeDwellRatio(
        visibleTimeMs: Long,
        expectedReadingTimeMs: Long
    ): Float {
        if (visibleTimeMs <= 0L || expectedReadingTimeMs <= 0L) {
            return SignalScoringConfig.DWELL_RATIO_MIN
        }
        val ratio = visibleTimeMs.toFloat() / expectedReadingTimeMs.toFloat()
        return ratio.coerceIn(SignalScoringConfig.DWELL_RATIO_MIN, SignalScoringConfig.DWELL_RATIO_MAX)
    }

    fun computeEngagementScore(
        dwellRatio: Float,
        isFastSkip: Boolean,
        isScrollBack: Boolean,
        actionsTaken: Set<String>
    ): Float {
        var score = 0f

        // Dwell ratio contribution
        score += when {
            dwellRatio > SignalScoringConfig.DWELL_HIGH_CUTOFF -> SignalScoringConfig.WEIGHT_DWELL_HIGH
            dwellRatio >= SignalScoringConfig.DWELL_MEDIUM_CUTOFF -> SignalScoringConfig.WEIGHT_DWELL_MEDIUM
            dwellRatio >= SignalScoringConfig.DWELL_NEUTRAL_CUTOFF -> SignalScoringConfig.WEIGHT_DWELL_NEUTRAL
            else -> SignalScoringConfig.WEIGHT_DWELL_LOW
        }

        // Fast skip with no action penalty
        if (isFastSkip && actionsTaken.isEmpty()) {
            score += SignalScoringConfig.WEIGHT_FAST_SKIP_NO_ACTION
        }

        // Scroll-back boost
        if (isScrollBack) {
            score += SignalScoringConfig.WEIGHT_SCROLL_BACK
        }

        // Action contributions
        if (SignalScoringConfig.ACTION_AMEN in actionsTaken) {
            score += SignalScoringConfig.WEIGHT_AMEN
        }
        if (SignalScoringConfig.ACTION_SAVE in actionsTaken) {
            score += SignalScoringConfig.WEIGHT_SAVE
        }
        if (SignalScoringConfig.ACTION_SHARE in actionsTaken) {
            score += SignalScoringConfig.WEIGHT_SHARE
        }
        if (SignalScoringConfig.ACTION_REFLECT in actionsTaken) {
            score += SignalScoringConfig.WEIGHT_REFLECT
        }

        return score.coerceIn(SignalScoringConfig.SCORE_MIN, SignalScoringConfig.SCORE_MAX)
    }
}
