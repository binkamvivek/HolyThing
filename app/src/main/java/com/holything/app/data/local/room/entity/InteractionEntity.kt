package com.holything.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "interactions")
data class InteractionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val cardId: String,
    val type: String,
    val dwellMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

