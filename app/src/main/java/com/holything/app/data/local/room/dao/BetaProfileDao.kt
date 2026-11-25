package com.holything.app.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.holything.app.data.local.room.entity.BetaAffinityEntity
import com.holything.app.data.local.room.entity.MoodBaselineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BetaProfileDao {
    @Query("SELECT * FROM beta_affinities")
    suspend fun getAllAffinitiesList(): List<BetaAffinityEntity>

    @Query("SELECT * FROM beta_affinities")
    fun observeAllAffinities(): Flow<List<BetaAffinityEntity>>

    @Query("SELECT * FROM beta_affinities WHERE dimension = :dimension AND value = :value LIMIT 1")
    suspend fun getAffinity(dimension: String, value: String): BetaAffinityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAffinities(items: List<BetaAffinityEntity>)

    @Query("DELETE FROM beta_affinities")
    suspend fun clearAll()

    @Query("SELECT * FROM mood_baseline")
    suspend fun getMoodBaselineList(): List<MoodBaselineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMoodBaseline(items: List<MoodBaselineEntity>)

    @Query("DELETE FROM mood_baseline")
    suspend fun clearMoodBaseline()
}
