package com.holything.app.domain.state

/**
 * Behavioral mode of the current reading session (separate from emotional mood).
 * Used to guide card length and format selection; never shown to the user as an emotion.
 */
enum class SessionMode {
    SKIMMING,
    READING,
    DEEP
}

/**
 * Read-only snapshot of the user's estimated current state.
 */
data class UserStateEstimate(
    val moodBelief: Map<String, Float> = UserStateConfig.uniformDistribution(),
    val confidence: Float = 0f,
    val sessionMode: SessionMode = SessionMode.READING,
    val isLateNight: Boolean = false,
    val isCareModeActive: Boolean = false
)

/**
 * Tunable configuration for mood belief updates, hint decay, drift, and session mode classification.
 */
object UserStateConfig {
    val CARD_MOODS: List<String> = listOf(
        "anxious",
        "grateful",
        "lost",
        "tired",
        "hopeful",
        "sad",
        "angry"
    )

    const val INITIAL_HINT_TOTAL_PROB: Float = 0.50f
    const val HINT_HALF_LIFE_CARDS: Float = 10f
    const val HINT_HALF_LIFE_MINUTES: Float = 15f

    const val EVIDENCE_LEARNING_RATE: Float = 0.12f
    const val MAX_SHIFT_PER_CARD: Float = 0.10f
    const val MIN_MOOD_PROBABILITY: Float = 0.01f

    const val DRIFT_TOWARD_BASELINE_RATE: Float = 0.02f
    const val BASELINE_EMA_ALPHA: Float = 0.20f

    // Session mode thresholds
    const val SKIM_WINDOW_SIZE: Int = 8
    const val SKIM_FAST_SKIP_THRESHOLD: Int = 5
    const val READING_DWELL_RATIO_THRESHOLD: Float = 0.6f
    const val DEEP_DWELL_RATIO_THRESHOLD: Float = 1.0f
    const val LATE_NIGHT_START_HOUR: Int = 23
    const val LATE_NIGHT_END_HOUR: Int = 5

    // Care rule thresholds
    const val CARE_MIN_CARDS: Int = 15
    const val CARE_HEAVY_BELIEF_THRESHOLD: Float = 0.45f
    const val CARE_MIN_CONFIDENCE: Float = 0.25f

    fun uniformDistribution(): Map<String, Float> {
        val p = 1f / CARD_MOODS.size.toFloat()
        return CARD_MOODS.associateWith { p }
    }
}
