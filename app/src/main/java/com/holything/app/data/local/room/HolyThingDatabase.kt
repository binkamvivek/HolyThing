package com.holything.app.data.local.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.holything.app.data.local.room.converter.StringListConverter
import com.holything.app.data.local.room.dao.CardDao
import com.holything.app.data.local.room.dao.InteractionDao
import com.holything.app.data.local.room.dao.JournalNoteDao
import com.holything.app.data.local.room.dao.ReadingDayDao
import com.holything.app.data.local.room.dao.SavedCardDao
import com.holything.app.data.local.room.entity.CardEntity
import com.holything.app.data.local.room.entity.InteractionEntity
import com.holything.app.data.local.room.entity.JournalNoteEntity
import com.holything.app.data.local.room.entity.ReadingDayEntity
import com.holything.app.data.local.room.entity.SavedCardEntity

@Database(
    entities = [
        CardEntity::class,
        InteractionEntity::class,
        SavedCardEntity::class,
        JournalNoteEntity::class,
        ReadingDayEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(StringListConverter::class)
abstract class HolyThingDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao
    abstract fun interactionDao(): InteractionDao
    abstract fun savedCardDao(): SavedCardDao
    abstract fun journalNoteDao(): JournalNoteDao
    abstract fun readingDayDao(): ReadingDayDao
}

