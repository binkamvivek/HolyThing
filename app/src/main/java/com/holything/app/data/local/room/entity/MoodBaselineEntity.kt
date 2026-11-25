package com.holything.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mood_baseline")
data class MoodBaselineEntity(
    @PrimaryKey val mood: String,
    val probability: Float,
    val updatedAt: Long = System.currentTimeMillis()
)
