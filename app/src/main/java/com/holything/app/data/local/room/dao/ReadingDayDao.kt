package com.holything.app.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.holything.app.data.local.room.entity.ReadingDayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingDay(readingDay: ReadingDayEntity)

    @Query("SELECT * FROM reading_days WHERE date = :date LIMIT 1")
    suspend fun getReadingDay(date: String): ReadingDayEntity?

    @Query("SELECT * FROM reading_days ORDER BY date DESC")
    fun getAllReadingDays(): Flow<List<ReadingDayEntity>>

    @Query("SELECT * FROM reading_days WHERE date >= :startDate ORDER BY date ASC")
    fun getRecentReadingDays(startDate: String): Flow<List<ReadingDayEntity>>

    @Query("SELECT SUM(cardsReadCount) FROM reading_days")
    fun getTotalCardsRead(): Flow<Int?>

    @Query("DELETE FROM reading_days")
    suspend fun clearAll()
}

