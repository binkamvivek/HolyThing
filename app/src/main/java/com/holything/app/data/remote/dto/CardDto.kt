package com.holything.app.data.remote.dto

import com.holything.app.data.local.room.entity.CardEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CardDto(
    @SerialName("id") val id: String,
    @SerialName("format") val format: String,
    @SerialName("hook") val hook: String,
    @SerialName("verse_ref") val verseRef: String,
    @SerialName("verse_text") val verseText: String,
    @SerialName("translation") val translation: String = "WEB",
    @SerialName("insight") val insight: String = "",
    @SerialName("topics") val topics: List<String> = emptyList(),
    @SerialName("moods") val moods: List<String> = emptyList(),
    @SerialName("tone") val tone: String = "",
    @SerialName("book") val book: String = "",
    @SerialName("length") val length: String = "short",
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("series_order") val seriesOrder: Int? = null,
    @SerialName("reflection_prompt") val reflectionPrompt: String = "",
    @SerialName("active") val active: Boolean = true
) {
    fun toEntity(syncedAt: Long = System.currentTimeMillis()): CardEntity = CardEntity(
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
        active = active,
        syncedAt = syncedAt
    )
}

