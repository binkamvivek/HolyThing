package com.holything.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.holything.app.domain.model.Card

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey
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
    val active: Boolean,
    val syncedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Card = Card(
        id = id,
        format = format,
        hook = hook,
        verseRef = verseRef,
        verseText = verseText,
        translation = translation,
        insight = insight,
        topics = topics,
        moods = moods,
        tone = tone,
        book = book,
        length = length,
        seriesId = seriesId,
        seriesOrder = seriesOrder,
        reflectionPrompt = reflectionPrompt,
        active = active
    )
}

