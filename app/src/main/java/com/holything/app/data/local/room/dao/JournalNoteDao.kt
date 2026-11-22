package com.holything.app.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.holything.app.data.local.room.entity.JournalNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: JournalNoteEntity): Long

    @Query("SELECT * FROM journal_notes WHERE cardId = :cardId ORDER BY createdAt DESC")
    fun getNotesForCard(cardId: String): Flow<List<JournalNoteEntity>>

    @Query("DELETE FROM journal_notes")
    suspend fun clearAll()
}

