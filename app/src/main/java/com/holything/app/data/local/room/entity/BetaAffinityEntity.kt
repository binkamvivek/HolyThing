package com.holything.app.data.local.room.entity

import androidx.room.Entity
import com.holything.app.domain.ranking.BetaParams

@Entity(
    tableName = "beta_affinities",
    primaryKeys = ["dimension", "value"]
)
data class BetaAffinityEntity(
    val dimension: String,
    val value: String,
    val alpha: Float = BetaParams.DEFAULT_PRIOR,
    val beta: Float = BetaParams.DEFAULT_PRIOR,
    val updatedAt: Long = System.currentTimeMillis()
)
