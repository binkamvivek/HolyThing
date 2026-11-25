package com.holything.app.domain.ranking

import com.holything.app.data.local.room.entity.SignalEventEntity
import com.holything.app.domain.model.Card
import com.holything.app.domain.model.OnboardingPreferences
import com.holything.app.domain.signal.SignalScoringConfig
import com.holything.app.domain.state.UserStateConfig
import com.holything.app.domain.state.UserStateEstimator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedSimulationTest {

    private data class PersonaSpec(
        val name: String,
        val targetMoods: Set<String>,
        val preferredFormats: Set<String>,
        val preferredTopic: String? = null,
        val preferredLength: String? = null,
        val prefersComforting: Boolean = false,
        val hourOfDay: Int = 14,
        val skimsNonPreferred: Boolean = false,
        val onboardingGoal: String = "Peace"
    )

    private fun buildRichLibrary(): List<Card> {
        val books = listOf("Psalms", "John", "Genesis", "Romans", "Isaiah", "Proverbs", "Luke", "James")
        val formats = listOf("verse", "story", "didyouknow", "question")
        val topics = listOf("hope", "peace", "grief", "strength", "faith", "gratitude", "love", "wisdom")
        val lengths = listOf("short", "medium", "long")
        val tones = listOf("comforting", "inspiring", "reflective", "challenging")

        val list = mutableListOf<Card>()

        // Add 2 multi-part story series (4 parts each) first so they appear in 100-card runs
        for (part in 1..4) {
            list.add(
                Card(
                    id = "series-hope-$part",
                    format = "story",
                    hook = "Hope story part $part",
                    verseRef = "Genesis 32:${part + 20}",
                    verseText = "Story verse $part",
                    translation = "WEB",
                    insight = "Story insight $part",
                    topics = listOf("hope"),
                    moods = listOf("hopeful"),
                    tone = "comforting",
                    book = "Genesis",
                    length = "medium",
                    seriesId = "hope_journey",
                    seriesOrder = part,
                    reflectionPrompt = "Reflect",
                    active = true
                )
            )
            list.add(
                Card(
                    id = "series-grief-$part",
                    format = "story",
                    hook = "Comfort story part $part",
                    verseRef = "John 11:${part + 30}",
                    verseText = "Comfort verse $part",
                    translation = "WEB",
                    insight = "Comfort insight $part",
                    topics = listOf("grief"),
                    moods = listOf("sad"),
                    tone = "comforting",
                    book = "John",
                    length = "short",
                    seriesId = "comfort_journey",
                    seriesOrder = part,
                    reflectionPrompt = "Reflect",
                    active = true
                )
            )
        }

        // Create 840 varied cards covering all moods, formats, topics, tones, lengths, and books
        // with natural mood-topic correlation so 100-card simulations never exhaust a persona's pool.
        for (i in 0 until 840) {
            val mood = UserStateConfig.CARD_MOODS[i % UserStateConfig.CARD_MOODS.size]
            val format = formats[(i / 7) % formats.size]
            val topic = when (mood) {
                "hopeful" -> if (i % 3 == 0) "faith" else "hope"
                "anxious" -> if (i % 3 == 0) "strength" else "peace"
                "tired" -> if (i % 3 == 0) "patience" else "rest"
                "sad", "lost" -> if (i % 3 == 0) "comfort" else "grief"
                "grateful" -> if (i % 3 == 0) "joy" else "gratitude"
                else -> topics[(i / 3) % topics.size]
            }
            val length = lengths[i % lengths.size]
            val tone = if (mood in setOf("sad", "anxious", "tired", "lost")) {
                if (i % 5 == 0) "reflective" else "comforting"
            } else {
                tones[i % tones.size]
            }
            val book = books[(i * 3 + 1) % books.size]
            list.add(
                Card(
                    id = "sim-$i",
                    format = format,
                    hook = "Hook $i for $mood",
                    verseRef = "$book ${(i % 20) + 1}:${(i % 12) + 1}",
                    verseText = "Word ".repeat(15),
                    translation = "WEB",
                    insight = "Insight ".repeat(20),
                    topics = listOf(topic),
                    moods = listOf(mood),
                    tone = tone,
                    book = book,
                    length = length,
                    seriesId = null,
                    seriesOrder = null,
                    reflectionPrompt = "Reflect",
                    active = true
                )
            )
        }
        return list
    }

    private fun runSimulation(persona: PersonaSpec, seed: Long) {
        val library = buildRichLibrary()
        val estimator = UserStateEstimator()
        val startMs = 1_700_000_000_000L
        estimator.startSession(nowMs = startMs)

        val alphaMap = mutableMapOf<Pair<String, String>, Float>()
        val betaMap = mutableMapOf<Pair<String, String>, Float>()
        var seriesState = SessionSeriesState()
        val seenIds = mutableSetOf<String>()
        val servedCards = mutableListOf<Card>()
        val servedReasons = mutableListOf<String>()
        var seriesContinuationVerified = false
        var seriesPart1Served = false

        for (step in 0 until 100) {
            val entries = (alphaMap.keys + betaMap.keys).associateWith { key ->
                BetaParams(
                    alpha = (alphaMap[key] ?: BetaParams.DEFAULT_PRIOR).coerceAtLeast(BetaParams.DEFAULT_PRIOR),
                    beta = (betaMap[key] ?: BetaParams.DEFAULT_PRIOR).coerceAtLeast(BetaParams.DEFAULT_PRIOR)
                )
            }
            val betaProfile = LongTermBetaProfile(entries)

            val ranked = AdaptiveFeedRanker.rankFeed(
                allCards = library,
                userState = estimator.state.value,
                betaProfile = betaProfile,
                onboardingPrefs = OnboardingPreferences(goal = persona.onboardingGoal),
                seenCardIds = seenIds,
                amenedCardIds = emptySet(),
                savedCardIds = emptySet(),
                seriesState = seriesState,
                swipeCount = step,
                seed = seed + step,
                recentHistory = servedCards.takeLast(3)
            ).filterNot { it.isPauseCard }

            val nextItem = ranked.firstOrNull() ?: break
            val card = nextItem.card
            seenIds.add(card.id)
            servedCards.add(card)
            servedReasons.add(nextItem.recommendationReason)

            // Check series continuation if we previously triggered pendingNextParts
            if (seriesPart1Served && card.seriesOrder == 2 && nextItem.recommendationReason == "Continuing the story") {
                seriesContinuationVerified = true
            }

            // Simulate persona reaction to this card
            val matchesMood = card.moods.any { it in persona.targetMoods }
            val matchesFormat = card.format in persona.preferredFormats
            val matchesTopic = persona.preferredTopic == null || persona.preferredTopic in card.topics
            val matchesLength = persona.preferredLength == null || card.length == persona.preferredLength
            val matchesComfort = !persona.prefersComforting || card.tone == "comforting"

            val isMatch = matchesMood && matchesFormat && matchesTopic && matchesLength && matchesComfort

            val dwellRatio = when {
                isMatch -> 1.4f
                matchesMood && matchesFormat -> 0.85f
                persona.skimsNonPreferred -> 0.12f
                else -> 0.25f
            }
            val isFastSkip = dwellRatio < 0.2f
            val actions = if (isMatch && persona.prefersComforting) {
                listOf(SignalScoringConfig.ACTION_SAVE)
            } else if (isMatch) {
                listOf(SignalScoringConfig.ACTION_AMEN)
            } else {
                emptyList()
            }
            val engagementScore = when {
                isMatch -> 0.75f
                matchesMood && matchesFormat -> 0.25f
                isFastSkip -> -0.75f
                else -> -0.30f
            }

            val evt = SignalEventEntity(
                cardId = card.id,
                positionInSession = step,
                visibleTimeMs = (dwellRatio * 5000f).toLong(),
                timeToFirstInteractionMs = if (actions.isNotEmpty()) 2000L else null,
                isFastSkip = isFastSkip,
                isScrollBack = false,
                isReading = dwellRatio > 1.2f,
                isHesitation = false,
                scrollSpeedBucket = if (isFastSkip) "fast" else "slow",
                actionsTaken = actions,
                sessionId = "sim",
                hourOfDay = persona.hourOfDay,
                dayOfWeek = 2,
                cardsSeenSoFar = step + 1,
                dwellRatio = dwellRatio,
                engagementScore = engagementScore,
                timestamp = startMs + (step * 10_000L)
            )
            estimator.onSignalEvent(evt, card.moods)

            // Update Beta profile
            val pos = if (engagementScore > 0f) engagementScore else 0f
            val neg = if (engagementScore < 0f) -engagementScore else 0f
            val dims = buildList {
                card.topics.forEach { add(BetaDimension.TOPIC to it) }
                add(BetaDimension.FORMAT to card.format)
                add(BetaDimension.TONE to card.tone)
                add(BetaDimension.LENGTH to card.length)
            }
            dims.forEach { key ->
                alphaMap[key] = (alphaMap[key] ?: BetaParams.DEFAULT_PRIOR) + pos
                betaMap[key] = (betaMap[key] ?: BetaParams.DEFAULT_PRIOR) + neg
            }

            // Update series continuation
            if (card.seriesId != null && card.seriesOrder != null) {
                if (isFastSkip) {
                    seriesState = seriesState.copy(
                        pendingNextParts = seriesState.pendingNextParts - card.seriesId,
                        droppedSeriesIds = seriesState.droppedSeriesIds + card.seriesId
                    )
                } else if (dwellRatio > 0.8f && card.seriesOrder == 1) {
                    seriesPart1Served = true
                    seriesState = seriesState.copy(
                        pendingNextParts = seriesState.pendingNextParts + (card.seriesId to 2)
                    )
                }
            }
        }

        // Assertions over cards 30..99
        val postWarmup = servedCards.drop(30)
        val matchCount = postWarmup.count { card ->
            val mMood = card.moods.any { it in persona.targetMoods }
            val mFormat = card.format in persona.preferredFormats
            val mTopic = persona.preferredTopic != null && persona.preferredTopic in card.topics
            (mMood || mTopic) && mFormat
        }
        val matchRate = matchCount.toFloat() / postWarmup.size.toFloat()

        // Fatigue checks across all 100 cards
        for (i in 3 until servedCards.size) {
            val w4 = servedCards.subList(i - 3, i + 1)
            assertFalse("${persona.name}: 4 consecutive same format at $i", w4.all { it.format == w4[0].format })
            assertFalse(
                "${persona.name}: 4 consecutive same topic at $i",
                w4.all { it.topics.firstOrNull() == w4[0].topics.firstOrNull() }
            )
        }

        // Wildcard rate (>= 8%)
        val wildcardCount = servedReasons.count { it == "Something new for you" }
        val wildcardRate = wildcardCount.toFloat() / servedCards.size.toFloat()

        println(
            "SIMULATION [${persona.name}]: cards=${servedCards.size}, " +
                "post30Match=${"%.1f".format(matchRate * 100)}%, " +
                "wildcards=${"%.1f".format(wildcardRate * 100)}%, " +
                "mode=${estimator.state.value.sessionMode}, " +
                "lateNight=${estimator.state.value.isLateNight}, " +
                "seriesContinued=$seriesContinuationVerified"
        )

        assertTrue("${persona.name}: expected >= 55% match after 30 cards, got ${matchRate * 100}%", matchRate >= 0.55f)
        assertTrue("${persona.name}: expected >= 8% wildcards, got ${wildcardRate * 100}%", wildcardRate >= 0.08f)
    }

    @Test
    fun `simulate four personas over 100 cards each`() {
        runSimulation(
            PersonaSpec(
                name = "A: Hopeful Story-Lover",
                targetMoods = setOf("hopeful"),
                preferredFormats = setOf("story", "verse", "didyouknow"),
                onboardingGoal = "Hope"
            ),
            seed = 101L
        )
        runSimulation(
            PersonaSpec(
                name = "B: Anxious Skimmer Saving Comforting",
                targetMoods = setOf("anxious"),
                preferredFormats = setOf("verse", "question", "didyouknow"),
                prefersComforting = true,
                skimsNonPreferred = true,
                onboardingGoal = "Peace"
            ),
            seed = 202L
        )
        runSimulation(
            PersonaSpec(
                name = "C: Tired Late-Night Short Reader",
                targetMoods = setOf("tired"),
                preferredFormats = setOf("verse", "didyouknow", "question"),
                preferredLength = "short",
                hourOfDay = 23,
                onboardingGoal = "Rest"
            ),
            seed = 303L
        )
        runSimulation(
            PersonaSpec(
                name = "D: Slow Grief Reader",
                targetMoods = setOf("sad", "lost"),
                preferredFormats = setOf("verse", "story", "didyouknow"),
                preferredTopic = "grief",
                onboardingGoal = "Grief"
            ),
            seed = 404L
        )
    }
}
