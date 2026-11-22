package com.holything.app.domain.model

data class FeedCardItem(
    val card: Card,
    val isAmened: Boolean = false,
    val isSaved: Boolean = false,
    val seriesTotalCount: Int? = null,
    val recommendationReason: String = "Picked for you"
)
