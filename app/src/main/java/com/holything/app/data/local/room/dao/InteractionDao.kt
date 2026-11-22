package com.holything.app.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.holything.app.data.local.room.entity.InteractionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InteractionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInteraction(interaction: InteractionEntity): Long

    @Query("SELECT * FROM interactions ORDER BY timestamp DESC")
    fun getAllInteractions(): Flow<List<InteractionEntity>>

    @Query("SELECT DISTINCT cardId FROM interactions WHERE timestamp >= :sinceTimestamp AND type IN ('dwell', 'fast_skip')")
    suspend fun getRecentlySeenCardIds(sinceTimestamp: Long): List<String>

    @Query("SELECT DISTINCT cardId FROM interactions WHERE type = 'amen'")
    fun getAmenedCardIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM interactions WHERE cardId = :cardId AND type = 'amen')")
    suspend fun isCardAmened(cardId: String): Boolean

    @Query("DELETE FROM interactions WHERE cardId = :cardId AND type = 'amen'")
    suspend fun removeAmen(cardId: String)

    @Query("DELETE FROM interactions")
    suspend fun clearAll()
}

