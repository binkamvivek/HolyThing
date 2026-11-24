package com.holything.app.data.local.room.entity

import androidx.room.Entity

@Entity(
    tableName = "beta_affinities",
    primaryKeys = ["dimension", "value"]
)
data class BetaAffinityEntity(
    val dimension: String,
    val value: String,
    val alpha: Float = 1f,
    val beta: Float = 1f,
    val updatedAt: Long = System.currentTimeMillis()
)
