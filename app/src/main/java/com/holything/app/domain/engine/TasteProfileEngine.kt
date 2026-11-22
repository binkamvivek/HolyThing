package com.holything.app.domain.engine

import com.holything.app.domain.model.Card
import com.holything.app.domain.model.OnboardingPreferences
import com.holything.app.domain.model.TasteProfile
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

object TasteProfileEngine {

    // Weight table for interactions
    const val WEIGHT_MORE_LIKE_THIS = 5.0f
    const val WEIGHT_LESS_LIKE_THIS = -5.0f
    const val WEIGHT_SHARE = 4.0f
    const val WEIGHT_SAVE = 3.5f
    const val WEIGHT_AMEN = 3.0f
    const val WEIGHT_REFLECT = 2.5f
    const val WEIGHT_LONG_DWELL = 1.5f
    const val WEIGHT_VERY_LONG_DWELL = 2.5f
    const val WEIGHT_FAST_SKIP = -2.0f

    // Half life: 7 days in milliseconds
    private const val HALF_LIFE_MS = 7.0 * 24 * 60 * 60 * 1000

    /**
     * Initializes taste profile from Onboarding preferences (cold start).
     */
    fun createFromOnboarding(prefs: OnboardingPreferences): TasteProfile {
        val topicMap = mutableMapOf<String, Float>()
        val formatMap = mutableMapOf<String, Float>()
        val toneMap = mutableMapOf<String, Float>()

        // 1. Mood mapping
        when (prefs.mood.lowercase()) {
            "anxious", "tired" -> {
                topicMap["peace"] = 3.5f
                topicMap["anxiety"] = 3.0f
                topicMap["strength"] = 2.5f
                toneMap["comforting"] = 4.0f
            }
            "sad", "lonely", "lost" -> {
                topicMap["comfort"] = 3.5f
                topicMap["grief"] = 2.5f
                topicMap["hope"] = 3.0f
                topicMap["love"] = 2.5f
                toneMap["comforting"] = 4.0f
            }
            "angry" -> {
                topicMap["patience"] = 3.0f
                topicMap["forgiveness"] = 3.0f
                topicMap["peace"] = 2.5f
                toneMap["reflective"] = 3.0f
            }
            "grateful", "hopeful" -> {
                topicMap["gratitude"] = 3.5f
                topicMap["faith"] = 3.0f
                topicMap["hope"] = 3.0f
                toneMap["inspiring"] = 3.5f
            }
        }

        // 2. Format preferences
        prefs.styles.forEach { style ->
            formatMap[style.lowercase()] = 3.0f
        }

        // 3. Goal preferences
        when (prefs.goal.lowercase()) {
            "peace" -> topicMap["peace"] = (topicMap["peace"] ?: 0f) + 2.0f
            "answers" -> {
                topicMap["wisdom"] = 3.0f
                formatMap["didyouknow"] = (formatMap["didyouknow"] ?: 0f) + 2.0f
            }
            "growth" -> {
                topicMap["purpose"] = 3.0f
                topicMap["faith"] = 2.5f
            }
            "habit" -> {
                formatMap["verse"] = (formatMap["verse"] ?: 0f) + 2.0f
            }
        }

        return TasteProfile(
            topicScores = topicMap,
            formatScores = formatMap,
            toneScores = toneMap,
            lengthScores = mapOf("short" to 2.0f, "medium" to 1.5f),
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
    }

    /**
     * Updates taste profile with a new interaction, applying decay to older scores.
     */
    fun updateWithInteraction(
        current: TasteProfile,
        card: Card,
        interactionType: String,
        dwellMs: Long = 0,
        now: Long = System.currentTimeMillis()
    ): TasteProfile {
        // 1. Calculate time decay multiplier
        val elapsedMs = max(0L, now - current.lastUpdatedTimestamp)
        val decay = exp(-elapsedMs.toDouble() / HALF_LIFE_MS).toFloat()

        // 2. Compute interaction delta
        val delta = when (interactionType) {
            "more_like_this" -> WEIGHT_MORE_LIKE_THIS
            "less_like_this" -> WEIGHT_LESS_LIKE_THIS
            "share" -> WEIGHT_SHARE
            "save" -> WEIGHT_SAVE
            "amen" -> WEIGHT_AMEN
            "reflect" -> WEIGHT_REFLECT
            "dwell" -> if (dwellMs >= 10000) WEIGHT_VERY_LONG_DWELL else if (dwellMs >= 5000) WEIGHT_LONG_DWELL else 0.5f
            "fast_skip" -> WEIGHT_FAST_SKIP
            else -> 0f
        }

        // 3. Apply decayed scores + delta clamped to safe bounds (-15f to +25f)
        val updatedTopics = current.topicScores.mapValues { it.value * decay }.toMutableMap()
        card.topics.forEach { rawTopic ->
            val t = rawTopic.trim().lowercase()
            if (t.isNotBlank()) {
                val existing = updatedTopics[t] ?: 0f
                updatedTopics[t] = clamp(existing + delta, -15f, 25f)
            }
        }

        val updatedFormats = current.formatScores.mapValues { it.value * decay }.toMutableMap()
        val formatKey = card.format.trim().lowercase()
        updatedFormats[formatKey] = clamp((updatedFormats[formatKey] ?: 0f) + delta, -15f, 25f)

        val updatedTones = current.toneScores.mapValues { it.value * decay }.toMutableMap()
        val toneKey = card.tone.trim().lowercase()
        if (toneKey.isNotBlank()) {
            updatedTones[toneKey] = clamp((updatedTones[toneKey] ?: 0f) + delta, -15f, 25f)
        }

        val updatedLengths = current.lengthScores.mapValues { it.value * decay }.toMutableMap()
        val lengthKey = card.length.trim().lowercase()
        if (lengthKey.isNotBlank()) {
            updatedLengths[lengthKey] = clamp((updatedLengths[lengthKey] ?: 0f) + (delta * 0.5f), -15f, 25f)
        }

        val timeBucket = getCurrentTimeBucket(now)
        val updatedTimeOfDay = current.timeOfDayScores.mapValues { it.value * decay }.toMutableMap()
        if (delta > 0) {
            updatedTimeOfDay[timeBucket] = (updatedTimeOfDay[timeBucket] ?: 0f) + 1.0f
        }

        return TasteProfile(
            topicScores = updatedTopics,
            formatScores = updatedFormats,
            toneScores = updatedTones,
            lengthScores = updatedLengths,
            timeOfDayScores = updatedTimeOfDay,
            lastUpdatedTimestamp = now
        )
    }

    private fun getCurrentTimeBucket(timestamp: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..21 -> "evening"
            else -> "night"
        }
    }

    private fun clamp(value: Float, minVal: Float, maxVal: Float): Float =
        max(minVal, min(maxVal, value))
}
