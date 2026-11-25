package com.holything.app.domain.ranking

import com.holything.app.domain.engine.MoodMapper
import com.holything.app.domain.model.Card
import com.holything.app.domain.model.FeedCardItem
import com.holything.app.domain.model.OnboardingPreferences
import com.holything.app.domain.state.SessionMode
import com.holything.app.domain.state.UserStateEstimate
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Tracks story series state within an active reading session.
 */
data class SessionSeriesState(
    val pendingNextParts: Map<String, Int> = emptyMap(),
    val droppedSeriesIds: Set<String> = emptySet()
)

/**
 * Tunable weights and exploration bounds for the adaptive feed ranker.
 */
object AdaptiveRankerConfig {
    const val WEIGHT_MOOD_MATCH = 3.0f
    const val WEIGHT_TOPIC_THOMPSON = 2.5f
    const val WEIGHT_TONE_AFFINITY = 1.2f
    const val WEIGHT_FORMAT_THOMPSON = 2.0f
    const val WEIGHT_SESSION_FORMAT_FIT = 2.2f
    const val WEIGHT_LENGTH_FIT = 1.5f
    const val WEIGHT_SERIES_CONTINUATION = 4.0f

    const val BASE_BEST_RATIO = 0.70f
    const val BASE_ADJACENT_RATIO = 0.20f
    const val BASE_WILDCARD_RATIO = 0.10f
    const val MIN_WILDCARD_RATIO = 0.10f

    const val NEW_USER_INTERACTION_THRESHOLD = 6
    const val SERIES_INSERT_WINDOW_MAX = 3
    const val PAUSE_CARD_INTERVAL = 25
}

internal enum class BucketSlot { BEST, ADJACENT, WILDCARD }

/**
 * Standalone, deterministic-seedable feed ranker using Phase 3 UserStateEstimate
 * and the Room-backed LongTermBetaProfile with Thompson sampling.
 */
object AdaptiveFeedRanker {

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
        "love" to setOf("relationships", "forgiveness", "comfort"),
        "faith" to setOf("hope", "patience", "strength", "gratitude"),
        "patience" to setOf("peace", "strength", "hope", "comfort"),
        "comfort" to setOf("peace", "grief", "love", "hope"),
        "joy" to setOf("gratitude", "hope", "love", "faith"),
        "rest" to setOf("peace", "patience", "comfort", "hope")
    )

    fun rankFeed(
        allCards: List<Card>,
        userState: UserStateEstimate,
        betaProfile: LongTermBetaProfile,
        onboardingPrefs: OnboardingPreferences,
        seenCardIds: Set<String>,
        amenedCardIds: Set<String>,
        savedCardIds: Set<String>,
        seriesState: SessionSeriesState = SessionSeriesState(),
        swipeCount: Int = 0,
        seed: Long? = null,
        initialMoodHint: String? = null,
        recentHistory: List<Card> = emptyList()
    ): List<FeedCardItem> {
        if (allCards.isEmpty()) return emptyList()
        val random = if (seed != null) Random(seed) else Random.Default

        // 1. 30-day exclusion (fallback to allCards if unseen pool runs out)
        val nonDropped = allCards.filterNot { it.seriesId != null && it.seriesId in seriesState.droppedSeriesIds }
        val activePool = if (nonDropped.isNotEmpty()) nonDropped else allCards
        val unseen = activePool.filterNot { it.id in seenCardIds }
        val basePool = if (unseen.isNotEmpty()) unseen else activePool

        // Care rule: hold back challenging-tone cards while care mode is active
        val pool = if (userState.isCareModeActive) {
            val nonChallenging = basePool.filterNot { it.tone.equals("challenging", ignoreCase = true) }
            if (nonChallenging.isNotEmpty()) nonChallenging else basePool
        } else {
            basePool
        }

        val seriesTotals = allCards
            .filter { !it.seriesId.isNullOrBlank() && it.seriesOrder != null }
            .groupBy { it.seriesId!! }
            .mapValues { (_, cardsInSeries) -> cardsInSeries.maxOf { it.seriesOrder ?: 1 } }

        // 2. First 15 cards of a brand-new user: compare different formats on the same topic
        val isBrandNewUser = swipeCount < AdaptiveRankerConfig.NEW_USER_INTERACTION_THRESHOLD &&
            betaProfile.totalInteractionsCount() < AdaptiveRankerConfig.NEW_USER_INTERACTION_THRESHOLD
        if (isBrandNewUser) {
            return buildNewUserFormatComparisonFeed(
                pool = pool,
                userState = userState,
                onboardingPrefs = onboardingPrefs,
                seriesTotals = seriesTotals,
                amenedCardIds = amenedCardIds,
                savedCardIds = savedCardIds,
                random = random,
                initialMoodHint = initialMoodHint,
                recentHistory = recentHistory
            )
        }

        // 3. Score each card via mood belief + Thompson sampling + session mode + series bonus
        val scoredPool = pool.map { card ->
            card to scoreCard(card, userState, betaProfile, seriesState, random)
        }.sortedByDescending { it.second }

        // 4. Partition into Best Matches (70%), Adjacent (20%), Wildcards (10%), adjusting for confidence
        val learnedTopTopic = betaProfile.topValue(BetaDimension.TOPIC)
        val topTopic = learnedTopTopic ?: onboardingPrefs.goal.lowercase()
        val adjacentSet = ADJACENT_TOPICS[topTopic.lowercase()] ?: emptySet()
        val dominantMood = userState.moodBelief.maxByOrNull { it.value }?.key

        val totalCount = scoredPool.size
        val (bestShare, adjacentShare, wildcardShare) = computeMixRatios(userState.confidence)

        val wildcardCandidates = if (learnedTopTopic != null) {
            scoredPool.filter { (card, _) ->
                isWildcard(card, betaProfile, topTopic, adjacentSet, dominantMood)
            }.map { it.first }.toMutableList()
        } else {
            // Before any topic has positive affinity, keep unseen topics in best/adjacent
            // and reserve unseen formats or lower-scoring cards for the wildcard slot.
            val cutoff = (scoredPool.size * 0.85f).toInt().coerceAtLeast(1)
            scoredPool.filterIndexed { index, (card, _) ->
                !betaProfile.hasSeenDimensionValue(BetaDimension.FORMAT, card.format) || index >= cutoff
            }.map { it.first }.toMutableList()
        }

        val adjacentCandidates = scoredPool.filter { (card, _) ->
            card !in wildcardCandidates &&
                card.topics.any { it.lowercase() in adjacentSet } &&
                !(dominantMood != null && card.moods.any { it.equals(dominantMood, ignoreCase = true) })
        }.map { it.first }.toMutableList()

        val bestCandidates = scoredPool.filter { (card, _) ->
            card !in wildcardCandidates && card !in adjacentCandidates
        }.map { it.first }.toMutableList()

        // 5. Identify pending series continuations (dwellRatio > 0.8 on part N -> part N+1 in next 1..3 cards)
        val pendingSeriesCards = seriesState.pendingNextParts.entries.mapNotNull { (sId, nextOrder) ->
            pool.firstOrNull { it.seriesId == sId && it.seriesOrder == nextOrder }
        }.toMutableList()

        // Remove pending series cards from regular buckets so they aren't duplicated
        bestCandidates.removeAll(pendingSeriesCards.toSet())
        adjacentCandidates.removeAll(pendingSeriesCards.toSet())
        wildcardCandidates.removeAll(pendingSeriesCards.toSet())

        val wildcardIds = wildcardCandidates.map { it.id }.toSet()
        val rankedCards = mutableListOf<Card>()
        val fatigueHistory = recentHistory.toMutableList()
        var comfortingCount = 0
        val schedule = buildCycleSchedule(bestShare, adjacentShare)

        var step = swipeCount
        while (bestCandidates.isNotEmpty() || adjacentCandidates.isNotEmpty() ||
            wildcardCandidates.isNotEmpty() || pendingSeriesCards.isNotEmpty()
        ) {
            // Insert pending series continuation within the first 1..3 cards (respecting fatigue rules)
            if (pendingSeriesCards.isNotEmpty() && rankedCards.size in 0 until AdaptiveRankerConfig.SERIES_INSERT_WINDOW_MAX) {
                val seriesCandidate = pickValid(pendingSeriesCards, fatigueHistory)
                if (seriesCandidate != null) {
                    rankedCards.add(seriesCandidate)
                    fatigueHistory.add(seriesCandidate)
                    if (seriesCandidate.tone.equals("comforting", ignoreCase = true)) comfortingCount++
                    step++
                    continue
                }
            }

            val needComfort = userState.isCareModeActive &&
                (comfortingCount.toFloat() / (rankedCards.size + 1).toFloat()) < 0.60f

            val slotType = schedule[step % schedule.size]
            val buckets = when (slotType) {
                BucketSlot.BEST -> listOf(bestCandidates, adjacentCandidates, wildcardCandidates)
                BucketSlot.ADJACENT -> listOf(adjacentCandidates, bestCandidates, wildcardCandidates)
                BucketSlot.WILDCARD -> listOf(wildcardCandidates, adjacentCandidates, bestCandidates)
            }
            val candidate = if (needComfort) {
                buckets.firstNotNullOfOrNull { pickComfortingValid(it, fatigueHistory) }
                    ?: buckets.firstNotNullOfOrNull { pickValid(it, fatigueHistory) }
            } else {
                buckets.firstNotNullOfOrNull { pickValid(it, fatigueHistory) }
            }

            if (candidate != null) {
                rankedCards.add(candidate)
                fatigueHistory.add(candidate)
                if (candidate.tone.equals("comforting", ignoreCase = true)) comfortingCount++
            } else {
                // Relaxed fallback while still respecting format/book fatigue if possible
                val fallback = pickValidWithRelaxation(bestCandidates, fatigueHistory)
                    ?: pickValidWithRelaxation(adjacentCandidates, fatigueHistory)
                    ?: pickValidWithRelaxation(wildcardCandidates, fatigueHistory)
                    ?: pickValidWithRelaxation(pendingSeriesCards, fatigueHistory)
                if (fallback != null) {
                    rankedCards.add(fallback)
                    fatigueHistory.add(fallback)
                    if (fallback.tone.equals("comforting", ignoreCase = true)) comfortingCount++
                } else {
                    break
                }
            }
            step++
        }

        val mapped = rankedCards.map { card ->
            FeedCardItem(
                card = card,
                isAmened = card.id in amenedCardIds,
                isSaved = card.id in savedCardIds,
                seriesTotalCount = card.seriesId?.let { seriesTotals[it] },
                recommendationReason = buildReason(
                    card = card,
                    userState = userState,
                    topTopic = topTopic,
                    seriesState = seriesState,
                    isWildcard = card.id in wildcardIds,
                    initialMoodHint = initialMoodHint
                )
            )
        }

        return insertPauseCards(mapped)
    }

    internal fun computeMixRatios(confidence: Float): Triple<Float, Float, Float> {
        // Confidence in [0, 1]. Low confidence raises exploration while keeping wildcard >= 10%.
        val clampedConf = confidence.coerceIn(0f, 1f)
        val extraExploration = if (clampedConf < 0.5f) {
            ((0.5f - clampedConf) / 0.5f) * 0.12f
        } else {
            0f
        }
        val wildcard = (AdaptiveRankerConfig.BASE_WILDCARD_RATIO + extraExploration * 0.5f)
            .coerceAtLeast(AdaptiveRankerConfig.MIN_WILDCARD_RATIO)
        val adjacent = AdaptiveRankerConfig.BASE_ADJACENT_RATIO + extraExploration * 0.5f
        val best = (1f - wildcard - adjacent).coerceAtLeast(0.50f)
        return Triple(best, adjacent, wildcard)
    }

    internal fun buildCycleSchedule(
        bestShare: Float,
        adjacentShare: Float,
        cycleSize: Int = 20
    ): List<BucketSlot> {
        val slots = MutableList(cycleSize) { BucketSlot.BEST }
        val targetBest = (cycleSize * bestShare).roundToInt().coerceAtLeast(10)
        val targetAdjacent = (cycleSize * adjacentShare).roundToInt().coerceAtLeast(2)
        val targetWildcards = (cycleSize - targetBest - targetAdjacent).coerceAtLeast(2)

        // Space wildcards evenly across the cycle so two wildcards never appear back-to-back
        for (i in 0 until targetWildcards) {
            val pos = (((i + 1) * cycleSize) / targetWildcards) - 1
            slots[pos.coerceIn(0, cycleSize - 1)] = BucketSlot.WILDCARD
        }

        // Space adjacent slots evenly across the remaining open positions
        val openIndices = slots.indices.filter { slots[it] == BucketSlot.BEST }
        val adjCount = targetAdjacent.coerceAtMost(openIndices.size)
        for (i in 0 until adjCount) {
            val openPos = (((i + 1) * openIndices.size) / adjCount) - 1
            slots[openIndices[openPos.coerceIn(0, openIndices.lastIndex)]] = BucketSlot.ADJACENT
        }
        return slots
    }

    internal fun scoreCard(
        card: Card,
        userState: UserStateEstimate,
        betaProfile: LongTermBetaProfile,
        seriesState: SessionSeriesState,
        random: Random
    ): Float {
        // 1. Mood belief match
        val moodScore = if (card.moods.isEmpty()) {
            0f
        } else {
            card.moods.maxOf { mood ->
                userState.moodBelief[mood.lowercase().trim()] ?: 0f
            }
        }

        // 2. Topic & tone Thompson sampling / affinity
        val topicSample = if (card.topics.isEmpty()) {
            0.5f
        } else {
            card.topics.maxOf { topic ->
                betaProfile.sample(BetaDimension.TOPIC, topic, random)
            }
        }
        val toneAffinity = betaProfile.mean(BetaDimension.TONE, card.tone)

        // 3. Format fit for session mode + Thompson sampling on format
        val formatSample = betaProfile.sample(BetaDimension.FORMAT, card.format, random)
        val sessionFormatFit = computeSessionFormatFit(card.format, userState.sessionMode)

        // 4. Length fit (session mode + late night + Beta affinity)
        val lengthAffinity = betaProfile.mean(BetaDimension.LENGTH, card.length)
        val sessionLengthFit = computeSessionLengthFit(card.length, userState.sessionMode, userState.isLateNight)

        // 5. Series continuation bonus
        val seriesBonus = if (card.seriesId != null &&
            seriesState.pendingNextParts[card.seriesId] == card.seriesOrder
        ) {
            AdaptiveRankerConfig.WEIGHT_SERIES_CONTINUATION
        } else {
            0f
        }

        return (moodScore * AdaptiveRankerConfig.WEIGHT_MOOD_MATCH) +
            (topicSample * AdaptiveRankerConfig.WEIGHT_TOPIC_THOMPSON) +
            (toneAffinity * AdaptiveRankerConfig.WEIGHT_TONE_AFFINITY) +
            (formatSample * AdaptiveRankerConfig.WEIGHT_FORMAT_THOMPSON) +
            (sessionFormatFit * AdaptiveRankerConfig.WEIGHT_SESSION_FORMAT_FIT) +
            ((lengthAffinity + sessionLengthFit) * 0.5f * AdaptiveRankerConfig.WEIGHT_LENGTH_FIT) +
            seriesBonus
    }

    private fun computeSessionFormatFit(format: String, mode: SessionMode): Float {
        val f = format.lowercase().trim()
        return when (mode) {
            SessionMode.SKIMMING -> when (f) {
                "verse" -> 1.0f
                "question" -> 0.7f
                "didyouknow" -> 0.5f
                "story" -> 0.2f
                else -> 0.5f
            }
            SessionMode.READING -> when (f) {
                "verse" -> 0.8f
                "didyouknow" -> 0.8f
                "question" -> 0.75f
                "story" -> 0.7f
                else -> 0.7f
            }
            SessionMode.DEEP -> when (f) {
                "story" -> 1.0f
                "didyouknow" -> 0.9f
                "question" -> 0.85f
                "verse" -> 0.5f
                else -> 0.7f
            }
        }
    }

    private fun computeSessionLengthFit(length: String, mode: SessionMode, isLateNight: Boolean): Float {
        val l = length.lowercase().trim()
        val base = when (mode) {
            SessionMode.SKIMMING -> when (l) {
                "short" -> 1.0f
                "medium" -> 0.5f
                else -> 0.15f
            }
            SessionMode.READING -> when (l) {
                "medium" -> 0.9f
                "short" -> 0.8f
                else -> 0.6f
            }
            SessionMode.DEEP -> when (l) {
                "long" -> 1.0f
                "medium" -> 0.85f
                else -> 0.4f
            }
        }
        return if (isLateNight && l == "long") base * 0.8f else base
    }

    private fun isWildcard(
        card: Card,
        betaProfile: LongTermBetaProfile,
        topTopic: String,
        adjacentSet: Set<String>,
        dominantMood: String? = null
    ): Boolean {
        val isNewFormat = !betaProfile.hasSeenDimensionValue(BetaDimension.FORMAT, card.format)
        val isNewTopic = card.topics.any { !betaProfile.hasSeenDimensionValue(BetaDimension.TOPIC, it) }
        val matchesDominantMood = dominantMood != null &&
            card.moods.any { it.equals(dominantMood, ignoreCase = true) }
        val isOutsideCoreTopics = card.topics.none {
            it.equals(topTopic, ignoreCase = true) || it.lowercase() in adjacentSet
        }
        return isNewFormat || isNewTopic || (isOutsideCoreTopics && !matchesDominantMood)
    }

    private fun buildNewUserFormatComparisonFeed(
        pool: List<Card>,
        userState: UserStateEstimate,
        onboardingPrefs: OnboardingPreferences,
        seriesTotals: Map<String, Int>,
        amenedCardIds: Set<String>,
        savedCardIds: Set<String>,
        random: Random,
        initialMoodHint: String? = null,
        recentHistory: List<Card> = emptyList()
    ): List<FeedCardItem> {
        // Choose a single focus topic for the first 15 cards so formats can be compared cleanly
        val goalTopic = onboardingPrefs.goal.lowercase().trim()
        val hasGoalCards = goalTopic.isNotBlank() && pool.any { card ->
            card.topics.any { it.equals(goalTopic, ignoreCase = true) }
        }
        val dominantMood = if (userState.confidence >= 0.15f) {
            userState.moodBelief.maxByOrNull { it.value }?.key
        } else {
            null
        }
        val focusTopic = if (dominantMood != null) {
            pool
                .filter { card -> card.moods.any { it.equals(dominantMood, ignoreCase = true) } }
                .flatMap { it.topics }
                .groupingBy { it.lowercase() }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: goalTopic
        } else if (hasGoalCards) {
            goalTopic
        } else {
            pool.flatMap { it.topics }.groupingBy { it.lowercase() }.eachCount().maxByOrNull { it.value }?.key ?: goalTopic
        }

        val sessionFitScore: (Card) -> Float = { card ->
            computeSessionFormatFit(card.format, userState.sessionMode) +
                computeSessionLengthFit(card.length, userState.sessionMode, userState.isLateNight)
        }

        val topicCards = pool.filter { card ->
            card.topics.any { it.equals(focusTopic, ignoreCase = true) }
        }.shuffled(random).sortedByDescending(sessionFitScore).toMutableList()

        val remainingPool = pool.filterNot { it in topicCards }
            .shuffled(random)
            .sortedByDescending(sessionFitScore)
            .toMutableList()

        val result = mutableListOf<Card>()
        val fatigueHistory = recentHistory.toMutableList()
        var comfortingCount = 0
        val targetFormats = when {
            userState.sessionMode == SessionMode.SKIMMING || userState.isLateNight ->
                listOf("verse", "question", "didyouknow")
            userState.sessionMode == SessionMode.DEEP ->
                listOf("story", "didyouknow", "question", "verse")
            else ->
                listOf("verse", "story", "didyouknow", "question")
        }
        var formatCursor = 0

        while (result.size < AdaptiveRankerConfig.NEW_USER_INTERACTION_THRESHOLD &&
            (topicCards.isNotEmpty() || remainingPool.isNotEmpty())
        ) {
            val desiredFormat = targetFormats[formatCursor % targetFormats.size]
            formatCursor++

            val needComfort = userState.isCareModeActive &&
                (comfortingCount.toFloat() / (result.size + 1).toFloat()) < 0.60f

            val picked = if (needComfort) {
                pickComfortingValid(topicCards, fatigueHistory)
                    ?: pickComfortingValid(remainingPool, fatigueHistory)
                    ?: pickMatchingFormat(topicCards, desiredFormat, fatigueHistory)
                    ?: pickValid(topicCards, fatigueHistory)
                    ?: pickMatchingFormat(remainingPool, desiredFormat, fatigueHistory)
                    ?: pickValid(remainingPool, fatigueHistory)
                    ?: pickValidWithRelaxation(topicCards, fatigueHistory)
                    ?: pickValidWithRelaxation(remainingPool, fatigueHistory)
                    ?: break
            } else {
                pickMatchingFormat(topicCards, desiredFormat, fatigueHistory)
                    ?: pickValid(topicCards, fatigueHistory)
                    ?: pickMatchingFormat(remainingPool, desiredFormat, fatigueHistory)
                    ?: pickValid(remainingPool, fatigueHistory)
                    ?: pickValidWithRelaxation(topicCards, fatigueHistory)
                    ?: pickValidWithRelaxation(remainingPool, fatigueHistory)
                    ?: break
            }

            result.add(picked)
            fatigueHistory.add(picked)
            if (picked.tone.equals("comforting", ignoreCase = true)) comfortingCount++
        }

        // Append the rest of the pool respecting fatigue rules
        while (topicCards.isNotEmpty() || remainingPool.isNotEmpty()) {
            val needComfort = userState.isCareModeActive &&
                (comfortingCount.toFloat() / (result.size + 1).toFloat()) < 0.60f
            val next = if (needComfort) {
                pickComfortingValid(topicCards, fatigueHistory)
                    ?: pickComfortingValid(remainingPool, fatigueHistory)
                    ?: pickValid(topicCards, fatigueHistory)
                    ?: pickValid(remainingPool, fatigueHistory)
                    ?: pickValidWithRelaxation(topicCards, fatigueHistory)
                    ?: pickValidWithRelaxation(remainingPool, fatigueHistory)
                    ?: break
            } else {
                pickValid(topicCards, fatigueHistory)
                    ?: pickValid(remainingPool, fatigueHistory)
                    ?: pickValidWithRelaxation(topicCards, fatigueHistory)
                    ?: pickValidWithRelaxation(remainingPool, fatigueHistory)
                    ?: break
            }
            result.add(next)
            fatigueHistory.add(next)
            if (next.tone.equals("comforting", ignoreCase = true)) comfortingCount++
        }

        val mapped = result.mapIndexed { index, card ->
            FeedCardItem(
                card = card,
                isAmened = card.id in amenedCardIds,
                isSaved = card.id in savedCardIds,
                seriesTotalCount = card.seriesId?.let { seriesTotals[it] },
                recommendationReason = if (index < AdaptiveRankerConfig.NEW_USER_INTERACTION_THRESHOLD) {
                    "Exploring ${card.format.lowercase()} on $focusTopic"
                } else {
                    buildReason(
                        card = card,
                        userState = userState,
                        topTopic = focusTopic,
                        seriesState = SessionSeriesState(),
                        isWildcard = false,
                        initialMoodHint = initialMoodHint
                    )
                }
            )
        }
        return insertPauseCards(mapped)
    }

    private fun pickComfortingValid(pool: MutableList<Card>, current: List<Card>): Card? {
        val idx = pool.indexOfFirst {
            it.tone.equals("comforting", ignoreCase = true) && satisfiesFatigue(it, current)
        }
        return if (idx != -1) pool.removeAt(idx) else null
    }

    private fun pickMatchingFormat(
        pool: MutableList<Card>,
        desiredFormat: String,
        current: List<Card>
    ): Card? {
        val idx = pool.indexOfFirst {
            it.format.equals(desiredFormat, ignoreCase = true) && satisfiesFatigue(it, current)
        }
        return if (idx != -1) pool.removeAt(idx) else null
    }

    private fun pickValid(pool: MutableList<Card>, current: List<Card>): Card? {
        val last = current.lastOrNull()
        val lastFormat = last?.format
        val lastTopic = last?.topics?.firstOrNull()

        // Prefer variety on immediate transition while strictly enforcing fatigue limits
        val p1 = pool.indexOfFirst { card ->
            satisfiesFatigue(card, current) &&
                (lastFormat == null || !card.format.equals(lastFormat, ignoreCase = true)) &&
                (lastTopic == null || card.topics.none { it.equals(lastTopic, ignoreCase = true) })
        }
        if (p1 != -1) return pool.removeAt(p1)

        val p2 = pool.indexOfFirst { satisfiesFatigue(it, current) }
        if (p2 != -1) return pool.removeAt(p2)

        return null
    }

    private fun pickValidWithRelaxation(pool: MutableList<Card>, current: List<Card>): Card? {
        if (pool.isEmpty()) return null
        val fullFatigueOk = pool.indexOfFirst { satisfiesFatigue(it, current) }
        if (fullFatigueOk != -1) return pool.removeAt(fullFatigueOk)

        val formatAndBookOk = pool.indexOfFirst { satisfiesFormatAndBookFatigue(it, current) }
        if (formatAndBookOk != -1) return pool.removeAt(formatAndBookOk)

        val formatOnlyOk = pool.indexOfFirst { card ->
            current.size < 3 || current.takeLast(3).count { it.format.equals(card.format, ignoreCase = true) } < 3
        }
        if (formatOnlyOk != -1) return pool.removeAt(formatOnlyOk)

        return pool.removeAt(0)
    }

    internal fun satisfiesFatigue(candidate: Card, current: List<Card>): Boolean {
        if (!satisfiesFormatAndBookFatigue(candidate, current)) return false
        if (current.size >= 3) {
            val last3 = current.takeLast(3)
            val candidateTopic = candidate.topics.firstOrNull()?.lowercase()
            if (candidateTopic != null) {
                val sameTopic = last3.count { it.topics.firstOrNull()?.lowercase() == candidateTopic }
                if (sameTopic >= 3) return false
            }
        }
        return true
    }

    private fun satisfiesFormatAndBookFatigue(candidate: Card, current: List<Card>): Boolean {
        if (current.size >= 3) {
            val last3 = current.takeLast(3)
            val sameFormat = last3.count { it.format.equals(candidate.format, ignoreCase = true) }
            if (sameFormat >= 3) return false
        }
        if (current.size >= 2 && candidate.book.isNotBlank()) {
            val last2 = current.takeLast(2)
            val sameBook = last2.count { it.book.equals(candidate.book, ignoreCase = true) }
            if (sameBook >= 2) return false
        }
        return true
    }

    internal fun buildReason(
        card: Card,
        userState: UserStateEstimate,
        topTopic: String,
        seriesState: SessionSeriesState,
        isWildcard: Boolean = false,
        initialMoodHint: String? = null
    ): String {
        val hintTags = MoodMapper.mapMoodToCardTags(initialMoodHint)
        return when {
            isWildcard -> "Something new for you"
            card.seriesId != null && seriesState.pendingNextParts[card.seriesId] == card.seriesOrder ->
                "Continuing the story"
            !initialMoodHint.isNullOrBlank() && card.moods.any { it.lowercase() in hintTags } ->
                "Because you chose feeling ${initialMoodHint.lowercase()}"
            card.topics.any { it.equals(topTopic, ignoreCase = true) } ->
                "You've been lingering on $topTopic"
            userState.isCareModeActive && card.tone.equals("comforting", ignoreCase = true) ->
                "A gentle word for right now"
            card.format.equals("story", ignoreCase = true) ->
                "A story from Scripture"
            card.format.equals("didyouknow", ignoreCase = true) ->
                "Context behind the verse"
            card.format.equals("question", ignoreCase = true) ->
                "A quiet question to sit with"
            else -> "Picked for your reading"
        }
    }

    internal fun insertPauseCards(items: List<FeedCardItem>): List<FeedCardItem> {
        val feedWithPauses = mutableListOf<FeedCardItem>()
        items.forEachIndexed { index, item ->
            feedWithPauses.add(item)
            if ((index + 1) % AdaptiveRankerConfig.PAUSE_CARD_INTERVAL == 0 && index + 1 < items.size) {
                feedWithPauses.add(FeedCardItem.createPauseCard(index + 1, item.card))
            }
        }
        return feedWithPauses
    }
}
