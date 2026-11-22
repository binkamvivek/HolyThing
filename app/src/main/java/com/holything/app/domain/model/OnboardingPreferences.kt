package com.holything.app.domain.model

data class OnboardingPreferences(
    val mood: String = "hopeful",
    val styles: Set<String> = setOf("verse"),
    val goal: String = "peace",
    val isHeavyMood: Boolean = mood in HEAVY_MOODS
) {
    companion object {
        val HEAVY_MOODS = setOf("sad", "anxious", "lost", "lonely", "angry")
        
        val DEFAULT_MOODS = listOf(
            "anxious",
            "tired",
            "sad",
            "angry",
            "lost",
            "hopeful",
            "grateful",
            "lonely"
        )

        val DEFAULT_STYLES = mapOf(
            "verse" to "Short Verses",
            "story" to "Biblical Stories",
            "testimony" to "Voices of Faith",
            "didyouknow" to "Deeper Context"
        )

        val DEFAULT_GOALS = listOf(
            "peace" to "Peace of mind",
            "answers" to "Answers to hard questions",
            "growth" to "Spiritual growth",
            "habit" to "A quiet daily habit"
        )
    }
}
