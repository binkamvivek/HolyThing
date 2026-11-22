package com.holything.app.domain.usecase

import com.holything.app.domain.model.Card
import com.holything.app.domain.model.FeedCardItem

object FeedPacingEngine {

    /**
     * Arranges cards so that:
     * 1. The same format never appears more than 3 times in a row.
     * 2. The same primary topic never appears more than 3 times in a row.
     * 3. Cards seen in the last 30 days are excluded (falls back to full pool if all seen).
     * 4. Story cards calculate seriesTotalCount.
     * 5. Assigns an honest, calm recommendation reason.
     */
    fun paceFeed(
        cards: List<Card>,
        seenCardIds: Set<String>,
        amenedCardIds: Set<String>,
        savedCardIds: Set<String>
    ): List<FeedCardItem> {
        if (cards.isEmpty()) return emptyList()

        // 1. Filter out cards seen in last 30 days; if all seen, reset to full pool
        val unseenPool = cards.filterNot { it.id in seenCardIds }
        val pool = if (unseenPool.isNotEmpty()) unseenPool.shuffled() else cards.shuffled()

        // 2. Compute series totals
        val seriesTotals = cards
            .filter { !it.seriesId.isNullOrBlank() && it.seriesOrder != null }
            .groupBy { it.seriesId!! }
            .mapValues { (_, seriesCards) -> seriesCards.maxOf { it.seriesOrder ?: 1 } }

        // 3. Greedy anti-repetition placement
        val paced = mutableListOf<Card>()
        val remaining = pool.toMutableList()

        while (remaining.isNotEmpty()) {
            val candidateIndex = remaining.indexOfFirst { candidate ->
                canPlaceCard(candidate, paced)
            }

            if (candidateIndex != -1) {
                paced.add(remaining.removeAt(candidateIndex))
            } else {
                // If strict constraint cannot be met with remaining items, append first available
                paced.add(remaining.removeAt(0))
            }
        }

        // 4. Map to FeedCardItem with reason string
        return paced.map { card ->
            val totalForSeries = card.seriesId?.let { seriesTotals[it] }
            FeedCardItem(
                card = card,
                isAmened = card.id in amenedCardIds,
                isSaved = card.id in savedCardIds,
                seriesTotalCount = totalForSeries,
                recommendationReason = generateReason(card)
            )
        }
    }

    private fun canPlaceCard(candidate: Card, currentList: List<Card>): Boolean {
        if (currentList.size < 3) return true
        val last3 = currentList.takeLast(3)

        // Check format repetition: same format not more than 3 times in a row
        val sameFormatCount = last3.count { it.format.equals(candidate.format, ignoreCase = true) }
        if (sameFormatCount == 3) return false

        // Check topic repetition: same primary topic not more than 3 times in a row
        val candidateTopic = candidate.topics.firstOrNull()?.lowercase()
        if (candidateTopic != null) {
            val sameTopicCount = last3.count { it.topics.firstOrNull()?.lowercase() == candidateTopic }
            if (sameTopicCount == 3) return false
        }

        return true
    }

    private fun generateReason(card: Card): String {
        return when {
            card.format.equals("story", ignoreCase = true) -> "A narrative from Scripture"
            card.format.equals("didyouknow", ignoreCase = true) -> "Context behind the verse"
            card.format.equals("question", ignoreCase = true) -> "A question for your heart"
            card.format.equals("testimony", ignoreCase = true) -> "A real voice of faith"
            card.tone.isNotBlank() -> "A ${card.tone.lowercase()} reflection"
            card.topics.isNotEmpty() -> "Reflecting on ${card.topics.first()}"
            else -> "Picked for you"
        }
    }
}
