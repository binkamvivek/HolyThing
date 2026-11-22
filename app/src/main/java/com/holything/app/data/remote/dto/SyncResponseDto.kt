package com.holything.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncResponseDto(
    @SerialName("version") val version: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
    @SerialName("count") val count: Int? = null,
    @SerialName("cards") val cards: List<CardDto>? = null,
    @SerialName("upToDate") val upToDate: Boolean? = null
)

