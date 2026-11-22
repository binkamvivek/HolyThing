package com.holything.app.domain.model

data class FeedCardItem(
    val card: Card,
    val isAmened: Boolean = false,
    val isSaved: Boolean = false,
    val seriesTotalCount: Int? = null,
    val recommendationReason: String = "Picked for you",
    val isPauseCard: Boolean = false,
    val cardsReadMilestone: Int = 25
) {
    companion object {
        fun createPauseCard(milestone: Int, previousCard: Card): FeedCardItem {
            return FeedCardItem(
                card = previousCard.copy(
                    id = "pause_$milestone",
                    format = "pause",
                    hook = "You've read $milestone verses.",
                    insight = "Want to pause and pray on one?"
                ),
                isPauseCard = true,
                cardsReadMilestone = milestone,
                recommendationReason = "A gentle moment of rest"
            )
        }
    }
}
