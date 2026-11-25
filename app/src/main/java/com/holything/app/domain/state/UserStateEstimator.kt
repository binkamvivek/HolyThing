package com.holything.app.domain.state

import com.holything.app.data.local.room.entity.SignalEventEntity
import com.holything.app.domain.engine.MoodMapper
import com.holything.app.domain.signal.SignalScoringConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow

/**
 * Standalone, UI-independent estimator that maintains a probability distribution
 * over the seven card moods, confidence, session mode, and late-night flag.
 */
@Singleton
class UserStateEstimator @Inject constructor() {

    private val _state = MutableStateFlow(UserStateEstimate())
    val state: StateFlow<UserStateEstimate> = _state.asStateFlow()

    private var baseline: Map<String, Float> = UserStateConfig.uniformDistribution()
    private var currentBelief: MutableMap<String, Float> = baseline.toMutableMap()

    // Home chip hint tracking for decay
    private var activeHintExtra: Map<String, Float> = emptyMap()
    private var hintDecayAppliedFactor: Float = 1f
    private var hintStartTimeMs: Long = 0L
    private var cardsSinceHint: Int = 0

    // Session tracking
    private val recentEvents = ArrayDeque<SignalEventEntity>(UserStateConfig.SKIM_WINDOW_SIZE)
    private val sessionBeliefSum = UserStateConfig.CARD_MOODS.associateWith { 0f }.toMutableMap()
    private var sessionEventCount: Int = 0
    private var heavyBeliefStreakCards: Int = 0
    private var isSadChipToday: Boolean = false

    /**
     * Starts or resets a session from the user's long-term baseline plus today's home chip hint (if any).
     */
    @Synchronized
    fun startSession(
        longTermBaseline: Map<String, Float>? = null,
        initialMoodHint: String? = null,
        hintTimestampMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ) {
        baseline = normalizeWithFloor(longTermBaseline ?: UserStateConfig.uniformDistribution())
        recentEvents.clear()
        UserStateConfig.CARD_MOODS.forEach { sessionBeliefSum[it] = 0f }
        sessionEventCount = 0

        val isHintFromToday = !initialMoodHint.isNullOrBlank() &&
            hintTimestampMs > 0L &&
            isSameLocalDay(hintTimestampMs, nowMs)
        isSadChipToday = isHintFromToday && initialMoodHint.equals("sad", ignoreCase = true)
        heavyBeliefStreakCards = 0

        if (isHintFromToday) {
            val mappedTags = MoodMapper.mapMoodToCardTags(initialMoodHint)
                .filter { it in UserStateConfig.CARD_MOODS }
                .toSet()

            if (mappedTags.isNotEmpty()) {
                val hintDist = buildHintDistribution(mappedTags)
                val uniform = UserStateConfig.uniformDistribution()
                activeHintExtra = UserStateConfig.CARD_MOODS.associateWith { mood ->
                    (hintDist[mood] ?: 0f) - (uniform[mood] ?: 0f)
                }
                hintDecayAppliedFactor = 1f
                hintStartTimeMs = hintTimestampMs
                cardsSinceHint = 0

                val initial = UserStateConfig.CARD_MOODS.associateWith { mood ->
                    (baseline[mood] ?: 0f) + (activeHintExtra[mood] ?: 0f)
                }
                currentBelief = normalizeWithFloor(initial).toMutableMap()
            } else {
                clearHintState()
                currentBelief = baseline.toMutableMap()
            }
        } else {
            clearHintState()
            currentBelief = baseline.toMutableMap()
        }

        val currentHour = Instant.ofEpochMilli(nowMs)
            .atZone(ZoneId.systemDefault())
            .hour
        publishState(hourOfDay = currentHour)
    }

    /**
     * Updates mood belief, hint decay, baseline drift, confidence, and session mode
     * from a single completed card signal event.
     */
    @Synchronized
    fun onSignalEvent(
        event: SignalEventEntity,
        cardMoods: List<String>,
        nowMs: Long = event.timestamp
    ) {
        // 1. Decay any active home-chip hint based on cards seen and elapsed minutes
        if (activeHintExtra.isNotEmpty()) {
            cardsSinceHint += 1
            val elapsedMinutes = ((nowMs - hintStartTimeMs).coerceAtLeast(0L)) / 60_000f
            val cardDecay = 0.5f.pow(cardsSinceHint / UserStateConfig.HINT_HALF_LIFE_CARDS)
            val timeDecay = 0.5f.pow(elapsedMinutes / UserStateConfig.HINT_HALF_LIFE_MINUTES)
            val targetFactor = minOf(cardDecay, timeDecay).coerceIn(0f, 1f)

            val deltaFactor = (targetFactor - hintDecayAppliedFactor)
            if (deltaFactor < 0f) {
                UserStateConfig.CARD_MOODS.forEach { mood ->
                    val extra = activeHintExtra[mood] ?: 0f
                    currentBelief[mood] = (currentBelief[mood] ?: 0f) + (extra * deltaFactor)
                }
                hintDecayAppliedFactor = targetFactor
                currentBelief = normalizeWithFloor(currentBelief).toMutableMap()
            }
        }

        // 2. Evidence update from engagement score and card mood tags (capped per card)
        val validCardMoods = cardMoods
            .map { it.lowercase().trim() }
            .filter { it in UserStateConfig.CARD_MOODS }
            .distinct()

        if (validCardMoods.isNotEmpty() && event.engagementScore != 0f) {
            val rawDelta = (event.engagementScore * UserStateConfig.EVIDENCE_LEARNING_RATE)
                .coerceIn(-UserStateConfig.MAX_SHIFT_PER_CARD, UserStateConfig.MAX_SHIFT_PER_CARD)
            val perTagDelta = rawDelta / validCardMoods.size.toFloat()

            validCardMoods.forEach { mood ->
                val prev = currentBelief[mood] ?: UserStateConfig.MIN_MOOD_PROBABILITY
                currentBelief[mood] = (prev + perTagDelta).coerceAtLeast(UserStateConfig.MIN_MOOD_PROBABILITY)
            }
            currentBelief = normalizeWithFloor(currentBelief).toMutableMap()
        }

        // 3. Gentle drift toward long-term baseline
        val drifted = UserStateConfig.CARD_MOODS.associateWith { mood ->
            val curr = currentBelief[mood] ?: 0f
            val base = baseline[mood] ?: 0f
            (1f - UserStateConfig.DRIFT_TOWARD_BASELINE_RATE) * curr +
                UserStateConfig.DRIFT_TOWARD_BASELINE_RATE * base
        }
        currentBelief = normalizeWithFloor(drifted).toMutableMap()

        // 4. Track sliding window for session mode & accumulate session average for baseline
        if (recentEvents.size >= UserStateConfig.SKIM_WINDOW_SIZE) {
            recentEvents.removeFirst()
        }
        recentEvents.addLast(event)

        UserStateConfig.CARD_MOODS.forEach { mood ->
            sessionBeliefSum[mood] = (sessionBeliefSum[mood] ?: 0f) + (currentBelief[mood] ?: 0f)
        }
        sessionEventCount += 1

        // Care rule: track sustained sad/lost/anxious belief with good confidence
        val heavyBeliefSum = (currentBelief["sad"] ?: 0f) +
            (currentBelief["lost"] ?: 0f) +
            (currentBelief["anxious"] ?: 0f)
        val confidence = computeConfidence(currentBelief)
        if (heavyBeliefSum >= UserStateConfig.CARE_HEAVY_BELIEF_THRESHOLD &&
            confidence >= UserStateConfig.CARE_MIN_CONFIDENCE
        ) {
            heavyBeliefStreakCards += 1
        } else {
            heavyBeliefStreakCards = 0
        }

        publishState(hourOfDay = event.hourOfDay)
    }

    /**
     * Computes an updated long-term baseline by blending the current session's average belief
     * into the previous baseline.
     */
    @Synchronized
    fun computeUpdatedBaseline(): Map<String, Float> {
        if (sessionEventCount == 0) return baseline
        val sessionAvg = UserStateConfig.CARD_MOODS.associateWith { mood ->
            (sessionBeliefSum[mood] ?: 0f) / sessionEventCount.toFloat()
        }
        val alpha = UserStateConfig.BASELINE_EMA_ALPHA
        val blended = UserStateConfig.CARD_MOODS.associateWith { mood ->
            (1f - alpha) * (baseline[mood] ?: 0f) + alpha * (sessionAvg[mood] ?: 0f)
        }
        baseline = normalizeWithFloor(blended)
        return baseline
    }

    private fun publishState(hourOfDay: Int) {
        val beliefSnapshot = currentBelief.toMap()
        val careActive = isSadChipToday || heavyBeliefStreakCards >= UserStateConfig.CARE_MIN_CARDS
        _state.value = UserStateEstimate(
            moodBelief = beliefSnapshot,
            confidence = computeConfidence(beliefSnapshot),
            sessionMode = classifySessionMode(),
            isLateNight = isLateNightHour(hourOfDay),
            isCareModeActive = careActive
        )
    }

    private fun classifySessionMode(): SessionMode {
        if (recentEvents.isEmpty()) return SessionMode.READING

        val fastSkipsInWindow = recentEvents.count { it.isFastSkip }
        if (fastSkipsInWindow >= UserStateConfig.SKIM_FAST_SKIP_THRESHOLD) {
            return SessionMode.SKIMMING
        }

        val latest = recentEvents.last()
        val hasDeepAction = recentEvents.takeLast(3).any { evt ->
            SignalScoringConfig.ACTION_REFLECT in evt.actionsTaken ||
                SignalScoringConfig.ACTION_SAVE in evt.actionsTaken
        }
        val hasDeepDwell = latest.isReading || latest.dwellRatio > UserStateConfig.DEEP_DWELL_RATIO_THRESHOLD
        if (hasDeepAction || hasDeepDwell) {
            return SessionMode.DEEP
        }

        val avgDwell = recentEvents.map { it.dwellRatio }.average().toFloat()
        if (avgDwell > UserStateConfig.READING_DWELL_RATIO_THRESHOLD) {
            return SessionMode.READING
        }

        return SessionMode.READING
    }

    private fun computeConfidence(belief: Map<String, Float>): Float {
        val n = UserStateConfig.CARD_MOODS.size
        val maxEntropy = ln(n.toFloat())
        if (maxEntropy <= 0f) return 0f

        var entropy = 0f
        UserStateConfig.CARD_MOODS.forEach { mood ->
            val p = (belief[mood] ?: 0f).coerceAtLeast(1e-6f)
            entropy -= p * ln(p)
        }
        return (1f - (entropy / maxEntropy)).coerceIn(0f, 1f)
    }

    private fun buildHintDistribution(hintTags: Set<String>): Map<String, Float> {
        val otherCount = (UserStateConfig.CARD_MOODS.size - hintTags.size).coerceAtLeast(1)
        val perHint = UserStateConfig.INITIAL_HINT_TOTAL_PROB / hintTags.size.toFloat()
        val perOther = (1f - UserStateConfig.INITIAL_HINT_TOTAL_PROB) / otherCount.toFloat()
        return UserStateConfig.CARD_MOODS.associateWith { mood ->
            if (mood in hintTags) perHint else perOther
        }
    }

    private fun normalizeWithFloor(raw: Map<String, Float>): Map<String, Float> {
        val floored = UserStateConfig.CARD_MOODS.associateWith { mood ->
            (raw[mood] ?: UserStateConfig.MIN_MOOD_PROBABILITY)
                .coerceAtLeast(UserStateConfig.MIN_MOOD_PROBABILITY)
        }
        val sum = floored.values.sum()
        if (sum <= 0f) return UserStateConfig.uniformDistribution()
        return floored.mapValues { (_, v) -> v / sum }
    }

    private fun clearHintState() {
        activeHintExtra = emptyMap()
        hintDecayAppliedFactor = 1f
        hintStartTimeMs = 0L
        cardsSinceHint = 0
    }

    private fun isLateNightHour(hourOfDay: Int): Boolean {
        return hourOfDay >= UserStateConfig.LATE_NIGHT_START_HOUR ||
            hourOfDay < UserStateConfig.LATE_NIGHT_END_HOUR
    }

    private fun isSameLocalDay(ts1Ms: Long, ts2Ms: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val d1 = Instant.ofEpochMilli(ts1Ms).atZone(zone).toLocalDate()
        val d2 = Instant.ofEpochMilli(ts2Ms).atZone(zone).toLocalDate()
        return d1 == d2
    }
}
