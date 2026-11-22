package com.holything.app.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.holything.app.data.local.room.entity.CardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE active = 1 ORDER BY rowid ASC")
    fun getAllActiveCards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE active = 1 ORDER BY rowid ASC")
    suspend fun getActiveCardsList(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE id = :id LIMIT 1")
    suspend fun getCardById(id: String): CardEntity?

    @Query("SELECT COUNT(*) FROM cards WHERE active = 1")
    fun getActiveCardsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM cards WHERE active = 1")
    suspend fun getActiveCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCards(cards: List<CardEntity>)

    @Query("DELETE FROM cards WHERE id NOT IN (:activeIds)")
    suspend fun deleteCardsNotIn(activeIds: List<String>)

    @Query("DELETE FROM cards")
    suspend fun clearAll()
}

