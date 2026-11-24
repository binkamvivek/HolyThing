package com.holything.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_summaries")
data class SessionSummaryEntity(
    @PrimaryKey val sessionId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val cardsSeen: Int,
    val averageDwellRatio: Float
)
