package com.holything.app.domain.model

data class DayActivity(
    val date: String, // "YYYY-MM-DD"
    val dayLabel: String, // "M", "T", "W", "T", "F", "S", "S"
    val count: Int,
    val isGoalMet: Boolean,
    val isGraceDay: Boolean = false,
    val isToday: Boolean = false
)

data class StreakInfo(
    val currentStreak: Int = 0,
    val versesReadToday: Int = 0,
    val totalVersesRead: Int = 0,
    val isTodayGoalMet: Boolean = false,
    val weeklyActivity: List<DayActivity> = emptyList(),
    val favoriteTopics: List<String> = emptyList()
)
