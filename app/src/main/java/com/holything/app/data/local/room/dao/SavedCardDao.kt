package com.holything.app.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.holything.app.data.local.room.entity.SavedCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedCardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCard(savedCard: SavedCardEntity)

    @Query("DELETE FROM saved_cards WHERE cardId = :cardId")
    suspend fun removeSavedCard(cardId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM saved_cards WHERE cardId = :cardId)")
    fun isCardSaved(cardId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_cards WHERE cardId = :cardId)")
    suspend fun isCardSavedDirect(cardId: String): Boolean

    @Query("SELECT DISTINCT cardId FROM saved_cards")
    fun getAllSavedCardIds(): Flow<List<String>>

    @Query("SELECT * FROM saved_cards ORDER BY savedAt DESC")
    fun getAllSavedCards(): Flow<List<SavedCardEntity>>

    @Query("DELETE FROM saved_cards")
    suspend fun clearAll()
}

