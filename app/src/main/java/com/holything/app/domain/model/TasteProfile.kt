package com.holything.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TasteProfile(
    val topicScores: Map<String, Float> = emptyMap(),
    val formatScores: Map<String, Float> = emptyMap(),
    val toneScores: Map<String, Float> = emptyMap(),
    val lengthScores: Map<String, Float> = emptyMap(),
    val timeOfDayScores: Map<String, Float> = emptyMap(),
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    fun getTopicScore(topic: String): Float = topicScores[topic.lowercase()] ?: 0f
    fun getFormatScore(format: String): Float = formatScores[format.lowercase()] ?: 0f
    fun getToneScore(tone: String): Float = toneScores[tone.lowercase()] ?: 0f
    fun getLengthScore(length: String): Float = lengthScores[length.lowercase()] ?: 0f

    val topTopic: String?
        get() = topicScores.maxByOrNull { it.value }?.takeIf { it.value > 0f }?.key

    val topFormat: String?
        get() = formatScores.maxByOrNull { it.value }?.takeIf { it.value > 0f }?.key
}
