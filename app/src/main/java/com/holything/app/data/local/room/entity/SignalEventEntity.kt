package com.holything.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_events")
data class SignalEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val positionInSession: Int,
    val visibleTimeMs: Long,
    val timeToFirstInteractionMs: Long?,
    val isFastSkip: Boolean,
    val isScrollBack: Boolean,
    val isReading: Boolean,
    val isHesitation: Boolean,
    val scrollSpeedBucket: String,
    val actionsTaken: List<String>,
    val sessionId: String,
    val hourOfDay: Int,
    val dayOfWeek: Int,
    val cardsSeenSoFar: Int,
    val dwellRatio: Float,
    val engagementScore: Float,
    val timestamp: Long = System.currentTimeMillis()
)
