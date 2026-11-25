package com.holything.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.holything.app.core.Constants
import com.holything.app.data.local.room.HolyThingDatabase
import com.holything.app.data.local.room.dao.BetaProfileDao
import com.holything.app.data.local.room.dao.CardDao
import com.holything.app.data.local.room.dao.InteractionDao
import com.holything.app.data.local.room.dao.JournalNoteDao
import com.holything.app.data.local.room.dao.ReadingDayDao
import com.holything.app.data.local.room.dao.SavedCardDao
import com.holything.app.data.local.room.dao.SignalDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `signal_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `cardId` TEXT NOT NULL,
                    `positionInSession` INTEGER NOT NULL,
                    `visibleTimeMs` INTEGER NOT NULL,
                    `timeToFirstInteractionMs` INTEGER,
                    `isFastSkip` INTEGER NOT NULL,
                    `isScrollBack` INTEGER NOT NULL,
                    `isReading` INTEGER NOT NULL,
                    `isHesitation` INTEGER NOT NULL,
                    `scrollSpeedBucket` TEXT NOT NULL,
                    `actionsTaken` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `hourOfDay` INTEGER NOT NULL,
                    `dayOfWeek` INTEGER NOT NULL,
                    `cardsSeenSoFar` INTEGER NOT NULL,
                    `dwellRatio` REAL NOT NULL,
                    `engagementScore` REAL NOT NULL,
                    `timestamp` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `session_summaries` (
                    `sessionId` TEXT NOT NULL,
                    `startTimeMs` INTEGER NOT NULL,
                    `endTimeMs` INTEGER NOT NULL,
                    `cardsSeen` INTEGER NOT NULL,
                    `averageDwellRatio` REAL NOT NULL,
                    PRIMARY KEY(`sessionId`)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `beta_affinities` (
                    `dimension` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    `alpha` REAL NOT NULL,
                    `beta` REAL NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`dimension`, `value`)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `mood_baseline` (
                    `mood` TEXT NOT NULL,
                    `probability` REAL NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`mood`)
                )
                """.trimIndent()
            )
        }
    }

    @Provides
    @Singleton
    fun provideHolyThingDatabase(@ApplicationContext context: Context): HolyThingDatabase {
        return Room.databaseBuilder(
            context,
            HolyThingDatabase::class.java,
            Constants.DATABASE_NAME
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
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

    @Provides
    fun provideSignalDao(database: HolyThingDatabase): SignalDao = database.signalDao()

    @Provides
    fun provideBetaProfileDao(database: HolyThingDatabase): BetaProfileDao = database.betaProfileDao()
}

