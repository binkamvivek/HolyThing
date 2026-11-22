package com.holything.app.domain.engine

import com.holything.app.data.local.room.entity.ReadingDayEntity
import com.holything.app.domain.model.DayActivity
import com.holything.app.domain.model.StreakInfo
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object StreakCalculator {
    const val TARGET_CARDS_PER_DAY = 5

    fun calculate(
        readingDays: List<ReadingDayEntity>,
        favoriteTopics: List<String> = emptyList(),
        referenceDate: LocalDate = LocalDate.now()
    ): StreakInfo {
        val daysMap = readingDays.associateBy { it.date }
        val todayStr = referenceDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val todayCount = daysMap[todayStr]?.cardsReadCount ?: 0
        val totalCount = readingDays.sumOf { it.cardsReadCount }

        // Streak evaluation:
        // A day counts at 5+ cards.
        // If today >= 5, streak starts from today.
        // If today < 5, streak is calculated from yesterday (today hasn't ended yet).
        var streak = 0
        var checkDate = if (todayCount >= TARGET_CARDS_PER_DAY) {
            streak = 1
            referenceDate.minusDays(1)
        } else {
            referenceDate.minusDays(1)
        }

        var graceDaysUsedInWindow = 0
        var daysEvaluated = 0

        while (true) {
            val dateStr = checkDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val dayRecord = daysMap[dateStr]
            val count = dayRecord?.cardsReadCount ?: 0

            // Reset grace day count every 7-day chunk evaluated
            if (daysEvaluated > 0 && daysEvaluated % 7 == 0) {
                graceDaysUsedInWindow = 0
            }

            if (count >= TARGET_CARDS_PER_DAY) {
                streak++
                daysEvaluated++
                checkDate = checkDate.minusDays(1)
            } else if (graceDaysUsedInWindow == 0) {
                // Forgive 1 missed day per 7-day window as a grace day
                graceDaysUsedInWindow++
                daysEvaluated++
                checkDate = checkDate.minusDays(1)
            } else {
                // Second missed day in the 7-day window ends the streak
                break
            }
        }

        // 7-day activity ending today
        val weeklyActivity = (6 downTo 0).map { daysAgo ->
            val date = referenceDate.minusDays(daysAgo.toLong())
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val count = daysMap[dateStr]?.cardsReadCount ?: 0
            val dayLabel = when (date.dayOfWeek) {
                DayOfWeek.MONDAY -> "M"
                DayOfWeek.TUESDAY -> "T"
                DayOfWeek.WEDNESDAY -> "W"
                DayOfWeek.THURSDAY -> "T"
                DayOfWeek.FRIDAY -> "F"
                DayOfWeek.SATURDAY -> "S"
                DayOfWeek.SUNDAY -> "S"
            }
            DayActivity(
                date = dateStr,
                dayLabel = dayLabel,
                count = count,
                isGoalMet = count >= TARGET_CARDS_PER_DAY,
                isGraceDay = count in 1 until TARGET_CARDS_PER_DAY,
                isToday = daysAgo == 0
            )
        }

        return StreakInfo(
            currentStreak = streak,
            versesReadToday = todayCount,
            totalVersesRead = totalCount,
            isTodayGoalMet = todayCount >= TARGET_CARDS_PER_DAY,
            weeklyActivity = weeklyActivity,
            favoriteTopics = favoriteTopics
        )
    }
}
