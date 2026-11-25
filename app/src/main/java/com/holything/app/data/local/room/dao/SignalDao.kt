package com.holything.app.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.holything.app.data.local.room.entity.SessionSummaryEntity
import com.holything.app.data.local.room.entity.SignalEventEntity

@Dao
interface SignalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignalEvents(events: List<SignalEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessionSummary(summary: SessionSummaryEntity)

    @Query("SELECT * FROM signal_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getEventsForSession(sessionId: String): List<SignalEventEntity>

    @Query("SELECT * FROM session_summaries WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionSummary(sessionId: String): SessionSummaryEntity?

    @Query("SELECT * FROM signal_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 20): List<SignalEventEntity>

    @Query("DELETE FROM signal_events")
    suspend fun clearEvents()

    @Query("DELETE FROM session_summaries")
    suspend fun clearSummaries()
}
