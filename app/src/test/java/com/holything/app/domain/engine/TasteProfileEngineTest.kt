package com.holything.app.domain.engine

import com.holything.app.domain.model.Card
import com.holything.app.domain.model.OnboardingPreferences
import com.holything.app.domain.model.TasteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteProfileEngineTest {

    private val sampleCard = Card(
        id = "c1",
        format = "verse",
        hook = "Hook",
        verseRef = "Psalm 23:1",
        verseText = "The Lord is my shepherd",
        translation = "WEB",
        insight = "Insight",
        topics = listOf("peace", "comfort"),
        moods = listOf("tired"),
        tone = "comforting",
        book = "Psalms",
        length = "short",
        seriesId = null,
        seriesOrder = null,
        reflectionPrompt = "Prompt",
        active = true
    )

    @Test
    fun `more like this and less like this have strong weights`() {
        val initial = TasteProfile()

        // Apply More Like This (+5.0)
        val boosted = TasteProfileEngine.updateWithInteraction(
            current = initial,
            card = sampleCard,
            interactionType = "more_like_this"
        )

        assertEquals(5.0f, boosted.getTopicScore("peace"), 0.01f)
        assertEquals(5.0f, boosted.getFormatScore("verse"), 0.01f)
        assertEquals(5.0f, boosted.getToneScore("comforting"), 0.01f)

        // Apply Less Like This (-5.0)
        val reduced = TasteProfileEngine.updateWithInteraction(
            current = boosted,
            card = sampleCard,
            interactionType = "less_like_this"
        )

        assertEquals(0.0f, reduced.getTopicScore("peace"), 0.01f)
        assertEquals(0.0f, reduced.getFormatScore("verse"), 0.01f)
    }

    @Test
    fun `positive interactions increase weights and fast skip decreases`() {
        var profile = TasteProfile()

        // Amen (+3.0)
        profile = TasteProfileEngine.updateWithInteraction(profile, sampleCard, "amen")
        assertEquals(3.0f, profile.getTopicScore("peace"), 0.01f)

        // Save (+3.5)
        profile = TasteProfileEngine.updateWithInteraction(profile, sampleCard, "save")
        assertEquals(6.5f, profile.getTopicScore("peace"), 0.01f)

        // Fast skip (-2.0)
        profile = TasteProfileEngine.updateWithInteraction(profile, sampleCard, "fast_skip")
        assertEquals(4.5f, profile.getTopicScore("peace"), 0.01f)
    }

    @Test
    fun `time decay applies exponential reduction over elapsed time`() {
        val baseTime = 1000000L
        val initial = TasteProfile(
            topicScores = mapOf("peace" to 10f),
            lastUpdatedTimestamp = baseTime
        )

        // Advance 7 days (one half-life)
        val sevenDaysMs = (7.0 * 24 * 60 * 60 * 1000).toLong()
        val futureTime = baseTime + sevenDaysMs

        // Dummy interaction with 0 delta to check decay alone
        val decayed = TasteProfileEngine.updateWithInteraction(
            current = initial,
            card = sampleCard.copy(topics = emptyList(), format = "none", tone = ""),
            interactionType = "unknown",
            now = futureTime
        )

        // Value should be decayed by approx half (10f * 0.5 ~ 5f)
        val decayedScore = decayed.getTopicScore("peace")
        assertTrue("Expected score ~5f but was $decayedScore", decayedScore in 3.5f..5.5f)
    }

    @Test
    fun `cold start creates initial profile from onboarding`() {
        val prefs = OnboardingPreferences(
            mood = "anxious",
            styles = setOf("story"),
            goal = "peace",
            isHeavyMood = true
        )

        val profile = TasteProfileEngine.createFromOnboarding(prefs)

        assertTrue(profile.getTopicScore("peace") > 0f)
        assertTrue(profile.getFormatScore("story") > 0f)
        assertTrue(profile.getToneScore("comforting") > 0f)
    }
}
