package com.holything.app.domain.engine

import com.holything.app.domain.model.Card
import com.holything.app.domain.model.FeedCardItem
import com.holything.app.domain.model.OnboardingPreferences
import com.holything.app.domain.model.TasteProfile

object FeedRankingEngine {

    // Natural biblical & emotional topical adjacencies
    private val ADJACENT_TOPICS: Map<String, Set<String>> = mapOf(
        "peace" to setOf("anxiety", "comfort", "prayer", "rest"),
        "anxiety" to setOf("peace", "strength", "comfort", "prayer"),
        "hope" to setOf("faith", "patience", "grief", "love"),
        "grief" to setOf("comfort", "hope", "love", "peace"),
        "strength" to setOf("patience", "faith", "temptation", "courage"),
        "forgiveness" to setOf("love", "anger", "patience", "grace"),
        "anger" to setOf("patience", "forgiveness", "peace"),
        "gratitude" to setOf("prayer", "faith", "hope", "joy"),
        "wisdom" to setOf("purpose", "work", "identity"),
        "purpose" to setOf("identity", "wisdom", "faith", "work"),
        "love" to setOf("relationships", "forgiveness", "comfort")
    )

    /**
     * Ranks and paces feed items according to:
     * - ~70% Close Match
     * - ~20% Adjacent Topics
     * - ~10% Wildcards
     * - First 10-15 swipes: Style Test on chosen topic
     * - Story continuation priority
     * - Gentle challenge injection if predominantly comforting
     * - Anti-repetition: Max 3 of same format or topic in a row
     * - 30-day recency filter
     */
    fun rankFeed(
        allCards: List<Card>,
        tasteProfile: TasteProfile,
        onboardingPrefs: OnboardingPreferences,
        seenCardIds: Set<String>,
        amenedCardIds: Set<String>,
        savedCardIds: Set<String>,
        lastInteractedCard: Card? = null,
        swipeCount: Int = 0
    ): List<FeedCardItem> {
        if (allCards.isEmpty()) return emptyList()

        // 1. Filter out cards seen within last 30 days
        val unseen = allCards.filterNot { it.id in seenCardIds }
        val pool = if (unseen.isNotEmpty()) unseen else allCards

        // 2. Precompute series totals
        val seriesTotals = allCards
            .filter { !it.seriesId.isNullOrBlank() && it.seriesOrder != null }
            .groupBy { it.seriesId!! }
            .mapValues { (_, seriesCards) -> seriesCards.maxOf { it.seriesOrder ?: 1 } }

        // 3. Check for mid-story continuation
        var nextStoryCard: Card? = null
        if (lastInteractedCard?.seriesId != null && lastInteractedCard.seriesOrder != null) {
            val targetOrder = lastInteractedCard.seriesOrder + 1
            nextStoryCard = pool.firstOrNull {
                it.seriesId == lastInteractedCard.seriesId && it.seriesOrder == targetOrder
            }
        }

        // 4. Style Test Mode (for first 10-15 swipes)
        if (swipeCount < 15) {
            return generateStyleTestFeed(
                pool = pool,
                onboardingPrefs = onboardingPrefs,
                seriesTotals = seriesTotals,
                amenedCardIds = amenedCardIds,
                savedCardIds = savedCardIds,
                nextStoryCard = nextStoryCard
            )
        }

        // 5. Partition pool into 70% Close Match, 20% Adjacent, 10% Wildcard
        val topTopic = tasteProfile.topTopic ?: onboardingPrefs.goal
        val adjacentTopics = ADJACENT_TOPICS[topTopic.lowercase()] ?: emptySet()

        val scoredCards = pool.map { card ->
            val score = scoreCard(card, tasteProfile, onboardingPrefs)
            card to score
        }.shuffled()

        val closeMatchList = scoredCards
            .filter { (card, _) ->
                card.topics.any { it.equals(topTopic, ignoreCase = true) } ||
                card.format.equals(tasteProfile.topFormat, ignoreCase = true) ||
                card.tone.equals("comforting", ignoreCase = true) && onboardingPrefs.isHeavyMood
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .toMutableList()

        val adjacentList = scoredCards
            .filter { (card, _) ->
                card.topics.any { it.lowercase() in adjacentTopics } && card !in closeMatchList
            }
            .map { it.first }
            .toMutableList()

        val wildcardList = scoredCards
            .filter { (card, _) -> card !in closeMatchList && card !in adjacentList }
            .map { it.first }
            .toMutableList()

        // 6. Check if user is predominantly in comfort mode -> inject gentle challenge
        val isHeavyComfort = (tasteProfile.getToneScore("comforting") > 5f)
        val challengeCards = if (isHeavyComfort) {
            pool.filter { it.tone.equals("challenging", ignoreCase = true) }.shuffled().toMutableList()
        } else {
            mutableListOf()
        }

        // 7. Assemble 70 / 20 / 10 interleaved sequence with anti-repetition rules
        val rankedCards = mutableListOf<Card>()
        var storyInserted = false

        while (closeMatchList.isNotEmpty() || adjacentList.isNotEmpty() || wildcardList.isNotEmpty()) {
            val step = rankedCards.size

            // Insert story continuation within first 2-4 swipes
            if (nextStoryCard != null && !storyInserted && step in 2..4) {
                if (canPlace(nextStoryCard, rankedCards)) {
                    rankedCards.add(nextStoryCard)
                    storyInserted = true
                    closeMatchList.remove(nextStoryCard)
                    adjacentList.remove(nextStoryCard)
                    wildcardList.remove(nextStoryCard)
                    continue
                }
            }

            // Inject gentle challenge card every ~12 cards if in comfort mode
            if (challengeCards.isNotEmpty() && step > 0 && step % 12 == 0) {
                val challengeCard = challengeCards.removeAt(0)
                if (canPlace(challengeCard, rankedCards)) {
                    rankedCards.add(challengeCard)
                    closeMatchList.remove(challengeCard)
                    continue
                }
            }

            // 70% Close Match, 20% Adjacent, 10% Wildcard
            val roll = (step % 10)
            val candidate = when {
                roll < 7 -> pickValid(closeMatchList, rankedCards)
                    ?: pickValid(adjacentList, rankedCards)
                    ?: pickValid(wildcardList, rankedCards)
                roll < 9 -> pickValid(adjacentList, rankedCards)
                    ?: pickValid(closeMatchList, rankedCards)
                    ?: pickValid(wildcardList, rankedCards)
                else -> pickValid(wildcardList, rankedCards)
                    ?: pickValid(closeMatchList, rankedCards)
                    ?: pickValid(adjacentList, rankedCards)
            }

            if (candidate != null) {
                rankedCards.add(candidate)
            } else {
                // If strict pacing fails, search for any card across all lists that at least satisfies format pacing
                val fallbackPool = listOf(closeMatchList, adjacentList, wildcardList)
                var fallback: Card? = null
                for (list in fallbackPool) {
                    val idx = list.indexOfFirst { canPlaceFormatOnly(it, rankedCards) }
                    if (idx != -1) {
                        fallback = list.removeAt(idx)
                        break
                    }
                }
                if (fallback != null) {
                    rankedCards.add(fallback)
                } else {
                    // Cannot place any card without creating 4 identical formats in a row
                    break
                }
            }
        }

        // 8. Map to FeedCardItem with contextual reasons
        val mappedItems = rankedCards.map { card ->
            FeedCardItem(
                card = card,
                isAmened = card.id in amenedCardIds,
                isSaved = card.id in savedCardIds,
                seriesTotalCount = card.seriesId?.let { seriesTotals[it] },
                recommendationReason = generateReason(card, tasteProfile, onboardingPrefs, nextStoryCard)
            )
        }

        // Insert pause cards after every 25 cards
        val feedWithPauses = mutableListOf<FeedCardItem>()
        mappedItems.forEachIndexed { index, item ->
            feedWithPauses.add(item)
            if ((index + 1) % 25 == 0 && index + 1 < mappedItems.size) {
                feedWithPauses.add(FeedCardItem.createPauseCard(index + 1, item.card))
            }
        }
        return feedWithPauses
    }

    private fun generateStyleTestFeed(
        pool: List<Card>,
        onboardingPrefs: OnboardingPreferences,
        seriesTotals: Map<String, Int>,
        amenedCardIds: Set<String>,
        savedCardIds: Set<String>,
        nextStoryCard: Card?
    ): List<FeedCardItem> {
        val targetMood = onboardingPrefs.mood.lowercase()
        val preferredStyles = onboardingPrefs.styles

        // Find cards matching the chosen mood or comforting tone first
        val relevant = pool.filter { card ->
            card.moods.any { it.equals(targetMood, ignoreCase = true) } ||
            (onboardingPrefs.isHeavyMood && card.tone.equals("comforting", ignoreCase = true))
        }.shuffled()

        val otherCards = pool.filterNot { it in relevant }.shuffled().toMutableList()
        val testFeed = mutableListOf<Card>()

        // Ensure the very first card directly matches user mood/comfort
        val firstCard = relevant.firstOrNull() ?: pool.first()
        testFeed.add(firstCard)

        // Alternate formats (verse, story, didyouknow, question) on the topic
        val remainingRelevant = (relevant - firstCard).toMutableList()
        while (remainingRelevant.isNotEmpty() || otherCards.isNotEmpty()) {
            val candidate = pickValid(remainingRelevant, testFeed) ?: pickValid(otherCards, testFeed)
            if (candidate != null) {
                testFeed.add(candidate)
            } else {
                val fallback = if (remainingRelevant.any { canPlaceFormatOnly(it, testFeed) }) {
                    val idx = remainingRelevant.indexOfFirst { canPlaceFormatOnly(it, testFeed) }
                    remainingRelevant.removeAt(idx)
                } else if (otherCards.any { canPlaceFormatOnly(it, testFeed) }) {
                    val idx = otherCards.indexOfFirst { canPlaceFormatOnly(it, testFeed) }
                    otherCards.removeAt(idx)
                } else null

                if (fallback != null) testFeed.add(fallback) else break
            }
        }

        return testFeed.map { card ->
            FeedCardItem(
                card = card,
                isAmened = card.id in amenedCardIds,
                isSaved = card.id in savedCardIds,
                seriesTotalCount = card.seriesId?.let { seriesTotals[it] },
                recommendationReason = if (card == firstCard) {
                    "Selected for you based on feeling ${onboardingPrefs.mood}"
                } else {
                    generateReason(card, TasteProfile(), onboardingPrefs, nextStoryCard)
                }
            )
        }
    }

    private fun scoreCard(card: Card, taste: TasteProfile, onboarding: OnboardingPreferences): Float {
        var score = 0f
        card.topics.forEach { t -> score += taste.getTopicScore(t) * 1.5f }
        score += taste.getFormatScore(card.format) * 1.2f
        score += taste.getToneScore(card.tone) * 1.0f
        score += taste.getLengthScore(card.length) * 0.5f

        if (onboarding.isHeavyMood && card.tone.equals("comforting", ignoreCase = true)) {
            score += 4.0f
        }
        return score
    }

    private fun pickValid(pool: MutableList<Card>, current: List<Card>): Card? {
        val lastCard = current.lastOrNull()
        val lastFormat = lastCard?.format
        val lastTopic = lastCard?.topics?.firstOrNull()?.lowercase()

        // 1st priority: valid pacing + different format + different topic
        val p1 = pool.indexOfFirst {
            canPlace(it, current) &&
            (lastFormat == null || !it.format.equals(lastFormat, ignoreCase = true)) &&
            (lastTopic == null || it.topics.none { t -> t.equals(lastTopic, ignoreCase = true) })
        }
        if (p1 != -1) return pool.removeAt(p1)

        // 2nd priority: valid pacing + different format
        val p2 = pool.indexOfFirst {
            canPlace(it, current) &&
            (lastFormat == null || !it.format.equals(lastFormat, ignoreCase = true))
        }
        if (p2 != -1) return pool.removeAt(p2)

        // 3rd priority: any card satisfying strict pacing
        val p3 = pool.indexOfFirst { canPlace(it, current) }
        if (p3 != -1) return pool.removeAt(p3)

        return null
    }

    private fun canPlace(candidate: Card, current: List<Card>): Boolean {
        if (current.size < 3) return true
        val last3 = current.takeLast(3)

        // Format anti-repetition: max 3 consecutive
        val sameFormat = last3.count { it.format.equals(candidate.format, ignoreCase = true) }
        if (sameFormat == 3) return false

        // Topic anti-repetition: max 3 consecutive
        val candidateTopic = candidate.topics.firstOrNull()?.lowercase()
        if (candidateTopic != null) {
            val sameTopic = last3.count { it.topics.firstOrNull()?.lowercase() == candidateTopic }
            if (sameTopic == 3) return false
        }

        return true
    }

    private fun canPlaceFormatOnly(candidate: Card, current: List<Card>): Boolean {
        if (current.size < 3) return true
        val last3 = current.takeLast(3)
        return last3.count { it.format.equals(candidate.format, ignoreCase = true) } < 3
    }

    private fun generateReason(
        card: Card,
        taste: TasteProfile,
        onboarding: OnboardingPreferences,
        nextStory: Card?
    ): String {
        return when {
            nextStory != null && card.id == nextStory.id ->
                "Continuing Part ${card.seriesOrder} of the story"
            card.tone.equals("challenging", ignoreCase = true) ->
                "A gentle encouragement to stretch your heart"
            taste.topTopic != null && card.topics.any { it.equals(taste.topTopic, ignoreCase = true) } ->
                "Because you resonated with passages on ${taste.topTopic}"
            taste.topFormat != null && card.format.equals(taste.topFormat, ignoreCase = true) ->
                "Because you enjoy ${card.format.lowercase()} cards"
            card.moods.any { it.equals(onboarding.mood, ignoreCase = true) } ->
                "Encouragement for when you feel ${onboarding.mood}"
            card.format.equals("story", ignoreCase = true) ->
                "A narrative from Scripture"
            card.format.equals("didyouknow", ignoreCase = true) ->
                "Historical context behind the verse"
            card.format.equals("question", ignoreCase = true) ->
                "A reflective question for your day"
            else -> "Picked for your quiet reading"
        }
    }
}
