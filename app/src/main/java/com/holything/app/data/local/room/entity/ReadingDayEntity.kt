package com.holything.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_days")
data class ReadingDayEntity(
    @PrimaryKey
    val date: String, // "YYYY-MM-DD"
    val cardsReadCount: Int = 0,
    val streakCount: Int = 0
)

