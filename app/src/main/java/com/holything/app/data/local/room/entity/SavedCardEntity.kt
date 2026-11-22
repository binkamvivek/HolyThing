package com.holything.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_cards")
data class SavedCardEntity(
    @PrimaryKey
    val cardId: String,
    val savedAt: Long = System.currentTimeMillis(),
    val collectionName: String? = null
)

