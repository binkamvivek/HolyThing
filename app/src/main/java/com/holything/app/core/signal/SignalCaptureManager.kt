package com.holything.app.core.signal

import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.local.room.dao.BetaProfileDao
import com.holything.app.data.local.room.dao.SignalDao
import com.holything.app.data.local.room.entity.BetaAffinityEntity
import com.holything.app.data.local.room.entity.SessionSummaryEntity
import com.holything.app.data.local.room.entity.SignalEventEntity
import com.holything.app.domain.model.Card
import com.holything.app.domain.ranking.BetaDimension
import com.holything.app.domain.ranking.SessionSeriesState
import com.holything.app.domain.signal.SignalScoreCalculator
import com.holything.app.domain.signal.SignalScoringConfig
import com.holything.app.domain.state.UserStateEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures per-card engagement signals off the main thread, computes dwell ratios
 * and engagement scores, updates UserStateEstimator, and writes events in batches to Room.
 */
@Singleton
class SignalCaptureManager @Inject constructor(
    private val signalDao: SignalDao,
    private val betaProfileDao: BetaProfileDao,
    private val dataStore: UserPreferencesDataStore,
    private val userStateEstimator: UserStateEstimator
) {
    companion object {
        const val BATCH_SIZE = 10
        const val SPEED_SLOW = "slow"
        const val SPEED_MEDIUM = "medium"
        const val SPEED_FAST = "fast"

        fun classifyScrollSpeed(transitionDurationMs: Long): String {
            return when {
                transitionDurationMs <= 0L -> SPEED_MEDIUM
                transitionDurationMs < 250L -> SPEED_FAST
                transitionDurationMs <= 600L -> SPEED_MEDIUM
                else -> SPEED_SLOW
            }
        }
    }

    private data class ActiveCardSession(
        val card: Card,
        val positionInSession: Int,
        val isScrollBack: Boolean,
        val scrollSpeedBucket: String,
        val cardsSeenAtEntry: Int,
        var accumulatedVisibleMs: Long = 0L,
        var visibleSegmentStartMs: Long? = null,
        var firstVisibleTimestampMs: Long? = null,
        var timeToFirstInteractionMs: Long? = null,
        var isHesitation: Boolean = false,
        val actionsTaken: MutableSet<String> = mutableSetOf()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var sessionId: String = UUID.randomUUID().toString()
    private var sessionStartTimeMs: Long = System.currentTimeMillis()
    private var lastPosition: Int = -1
    private val seenCardIds = mutableSetOf<String>()

    private var activeCard: ActiveCardSession? = null
    private val pendingEvents = mutableListOf<SignalEventEntity>()

    private var totalDwellRatioSum: Float = 0f
    private var recordedEventCount: Int = 0
    private var sessionInitialized: Boolean = false

    private val _seriesState = MutableStateFlow(SessionSeriesState())
    val seriesState: StateFlow<SessionSeriesState> = _seriesState.asStateFlow()

    private suspend fun ensureSessionInitializedLocked(nowMs: Long) {
        if (sessionInitialized) return
        sessionInitialized = true
        val baseline = dataStore.moodBaseline.first()
        val hint = dataStore.initialMoodHint.first()
        val hintTs = dataStore.moodHintTimestamp.first()
        userStateEstimator.startSession(
            longTermBaseline = baseline,
            initialMoodHint = hint,
            hintTimestampMs = hintTs,
            nowMs = nowMs
        )
    }

    fun onCardEntered(
        card: Card,
        positionInSession: Int,
        isVisibleEnough: Boolean = true,
        scrollSpeedBucket: String = SPEED_MEDIUM
    ) {
        val now = System.currentTimeMillis()
        scope.launch {
            mutex.withLock {
                ensureSessionInitializedLocked(now)
                // Avoid duplicate re-entry if already tracking the exact same card & position
                val current = activeCard
                if (current != null && current.card.id == card.id && current.positionInSession == positionInSession) {
                    return@withLock
                }

                // Finalize previous card if any
                if (current != null) {
                    finalizeCardLocked(current, now)
                    activeCard = null
                }

                val isScrollBack = (lastPosition != -1 && positionInSession < lastPosition) ||
                    (card.id in seenCardIds)
                lastPosition = positionInSession
                seenCardIds.add(card.id)

                val newSession = ActiveCardSession(
                    card = card,
                    positionInSession = positionInSession,
                    isScrollBack = isScrollBack,
                    scrollSpeedBucket = scrollSpeedBucket,
                    cardsSeenAtEntry = seenCardIds.size,
                    visibleSegmentStartMs = if (isVisibleEnough) now else null,
                    firstVisibleTimestampMs = if (isVisibleEnough) now else null
                )
                activeCard = newSession

                flushIfBatchReadyLocked(now)
            }
        }
    }

    fun onVisibilityChanged(cardId: String, isVisibleEnough: Boolean) {
        val now = System.currentTimeMillis()
        scope.launch {
            mutex.withLock {
                val current = activeCard ?: return@withLock
                if (current.card.id != cardId) return@withLock

                if (isVisibleEnough) {
                    if (current.visibleSegmentStartMs == null) {
                        current.visibleSegmentStartMs = now
                    }
                    if (current.firstVisibleTimestampMs == null) {
                        current.firstVisibleTimestampMs = now
                    }
                } else {
                    val start = current.visibleSegmentStartMs
                    if (start != null) {
                        current.accumulatedVisibleMs += (now - start).coerceAtLeast(0L)
                        current.visibleSegmentStartMs = null
                    }
                }
            }
        }
    }

    fun onHesitationDetected(cardId: String) {
        scope.launch {
            mutex.withLock {
                val current = activeCard ?: return@withLock
                if (current.card.id == cardId) {
                    current.isHesitation = true
                }
            }
        }
    }

    fun onActionTaken(cardId: String, action: String) {
        val now = System.currentTimeMillis()
        scope.launch {
            mutex.withLock {
                val current = activeCard ?: return@withLock
                if (current.card.id != cardId) return@withLock

                current.actionsTaken.add(action)
                if (current.timeToFirstInteractionMs == null) {
                    val start = current.firstVisibleTimestampMs ?: now
                    current.timeToFirstInteractionMs = (now - start).coerceAtLeast(0L)
                }
            }
        }
    }

    fun flush() {
        val now = System.currentTimeMillis()
        scope.launch {
            mutex.withLock {
                val current = activeCard
                if (current != null) {
                    finalizeCardLocked(current, now)
                    activeCard = null
                }
                flushBatchLocked(now)
            }
        }
    }

    private fun finalizeCardLocked(session: ActiveCardSession, endTimeMs: Long) {
        val segmentStart = session.visibleSegmentStartMs
        if (segmentStart != null) {
            session.accumulatedVisibleMs += (endTimeMs - segmentStart).coerceAtLeast(0L)
            session.visibleSegmentStartMs = null
        }

        val visibleTimeMs = session.accumulatedVisibleMs
        val expectedReadingTimeMs = SignalScoreCalculator.estimateExpectedReadingTimeMs(
            hook = session.card.hook,
            verseText = session.card.verseText,
            insight = session.card.insight
        )
        val dwellRatio = SignalScoreCalculator.computeDwellRatio(
            visibleTimeMs = visibleTimeMs,
            expectedReadingTimeMs = expectedReadingTimeMs
        )

        val isFastSkip = visibleTimeMs < SignalScoringConfig.FAST_SKIP_THRESHOLD_MS
        val isReading = visibleTimeMs > SignalScoringConfig.READING_STILL_THRESHOLD_MS
        val actions = session.actionsTaken.toSet()

        val engagementScore = SignalScoreCalculator.computeEngagementScore(
            dwellRatio = dwellRatio,
            isFastSkip = isFastSkip,
            isScrollBack = session.isScrollBack,
            actionsTaken = actions
        )

        val nowDateTime = LocalDateTime.now()
        val event = SignalEventEntity(
            cardId = session.card.id,
            positionInSession = session.positionInSession,
            visibleTimeMs = visibleTimeMs,
            timeToFirstInteractionMs = session.timeToFirstInteractionMs,
            isFastSkip = isFastSkip,
            isScrollBack = session.isScrollBack,
            isReading = isReading,
            isHesitation = session.isHesitation,
            scrollSpeedBucket = session.scrollSpeedBucket,
            actionsTaken = actions.toList(),
            sessionId = sessionId,
            hourOfDay = nowDateTime.hour,
            dayOfWeek = nowDateTime.dayOfWeek.value,
            cardsSeenSoFar = session.cardsSeenAtEntry,
            dwellRatio = dwellRatio,
            engagementScore = engagementScore,
            timestamp = endTimeMs
        )

        pendingEvents.add(event)
        totalDwellRatioSum += dwellRatio
        recordedEventCount += 1

        userStateEstimator.onSignalEvent(
            event = event,
            cardMoods = session.card.moods,
            nowMs = endTimeMs
        )

        // Update session story series state
        val sId = session.card.seriesId
        val sOrder = session.card.seriesOrder
        if (!sId.isNullOrBlank() && sOrder != null) {
            val currentSeries = _seriesState.value
            if (isFastSkip) {
                _seriesState.value = currentSeries.copy(
                    pendingNextParts = currentSeries.pendingNextParts - sId,
                    droppedSeriesIds = currentSeries.droppedSeriesIds + sId
                )
            } else if (dwellRatio > 0.8f && sId !in currentSeries.droppedSeriesIds) {
                _seriesState.value = currentSeries.copy(
                    pendingNextParts = currentSeries.pendingNextParts + (sId to (sOrder + 1))
                )
            }
        }

        // Update Beta distributions in Room on background IO thread
        if (engagementScore != 0f) {
            val cardCopy = session.card
            scope.launch {
                updateBetaAffinities(cardCopy, engagementScore, endTimeMs)
            }
        }
    }

    private suspend fun updateBetaAffinities(card: Card, engagementScore: Float, nowMs: Long) {
        val posDelta = if (engagementScore > 0f) engagementScore else 0f
        val negDelta = if (engagementScore < 0f) -engagementScore else 0f

        val pairs = buildList {
            card.topics.forEach { add(BetaDimension.TOPIC to it.lowercase().trim()) }
            add(BetaDimension.FORMAT to card.format.lowercase().trim())
            add(BetaDimension.TONE to card.tone.lowercase().trim())
            add(BetaDimension.LENGTH to card.length.lowercase().trim())
        }.filter { it.second.isNotBlank() }.distinct()

        val updatedEntities = pairs.map { (dim, value) ->
            val existing = betaProfileDao.getAffinity(dim, value)
            val prevAlpha = existing?.alpha ?: 1f
            val prevBeta = existing?.beta ?: 1f
            BetaAffinityEntity(
                dimension = dim,
                value = value,
                alpha = (prevAlpha + posDelta).coerceAtLeast(1f),
                beta = (prevBeta + negDelta).coerceAtLeast(1f),
                updatedAt = nowMs
            )
        }
        if (updatedEntities.isNotEmpty()) {
            betaProfileDao.upsertAffinities(updatedEntities)
        }
    }

    private suspend fun flushIfBatchReadyLocked(now: Long) {
        if (pendingEvents.size >= BATCH_SIZE) {
            flushBatchLocked(now)
        }
    }

    private suspend fun flushBatchLocked(now: Long) {
        if (pendingEvents.isNotEmpty()) {
            val batch = pendingEvents.toList()
            pendingEvents.clear()
            signalDao.insertSignalEvents(batch)
        }

        if (recordedEventCount > 0) {
            val avgDwell = totalDwellRatioSum / recordedEventCount.toFloat()
            signalDao.upsertSessionSummary(
                SessionSummaryEntity(
                    sessionId = sessionId,
                    startTimeMs = sessionStartTimeMs,
                    endTimeMs = now,
                    cardsSeen = seenCardIds.size,
                    averageDwellRatio = avgDwell
                )
            )
            dataStore.setMoodBaseline(userStateEstimator.computeUpdatedBaseline())
        }
    }
}
