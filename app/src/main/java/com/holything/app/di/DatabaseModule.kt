package com.holything.app.di

import android.content.Context
import androidx.room.Room
import com.holything.app.core.Constants
import com.holything.app.data.local.room.HolyThingDatabase
import com.holything.app.data.local.room.dao.CardDao
import com.holything.app.data.local.room.dao.InteractionDao
import com.holything.app.data.local.room.dao.JournalNoteDao
import com.holything.app.data.local.room.dao.ReadingDayDao
import com.holything.app.data.local.room.dao.SavedCardDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideHolyThingDatabase(@ApplicationContext context: Context): HolyThingDatabase {
        return Room.databaseBuilder(
            context,
            HolyThingDatabase::class.java,
            Constants.DATABASE_NAME
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideCardDao(database: HolyThingDatabase): CardDao = database.cardDao()

    @Provides
    fun provideInteractionDao(database: HolyThingDatabase): InteractionDao = database.interactionDao()

    @Provides
    fun provideSavedCardDao(database: HolyThingDatabase): SavedCardDao = database.savedCardDao()

    @Provides
    fun provideJournalNoteDao(database: HolyThingDatabase): JournalNoteDao = database.journalNoteDao()

    @Provides
    fun provideReadingDayDao(database: HolyThingDatabase): ReadingDayDao = database.readingDayDao()
}

