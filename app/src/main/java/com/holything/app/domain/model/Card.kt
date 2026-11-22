package com.holything.app.domain.model

data class Card(
    val id: String,
    val format: String,
    val hook: String,
    val verseRef: String,
    val verseText: String,
    val translation: String,
    val insight: String,
    val topics: List<String>,
    val moods: List<String>,
    val tone: String,
    val book: String,
    val length: String,
    val seriesId: String?,
    val seriesOrder: Int?,
    val reflectionPrompt: String,
    val active: Boolean
)

