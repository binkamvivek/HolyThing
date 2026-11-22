package com.holything.app.domain.engine

import com.holything.app.data.local.room.entity.ReadingDayEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StreakCalculatorTest {

    private val baseDate = LocalDate.of(2026, 11, 23)

    @Test
    fun `reading 5+ cards today and consecutive past days maintains streak`() {
        val records = listOf(
            ReadingDayEntity(date = "2026-11-23", cardsReadCount = 6),
            ReadingDayEntity(date = "2026-11-22", cardsReadCount = 5),
            ReadingDayEntity(date = "2026-11-21", cardsReadCount = 7)
        )

        val info = StreakCalculator.calculate(records, referenceDate = baseDate)

        assertEquals(3, info.currentStreak)
        assertEquals(6, info.versesReadToday)
        assertEquals(18, info.totalVersesRead)
        assertTrue(info.isTodayGoalMet)
    }

    @Test
    fun `one grace day per week is forgiven without breaking streak`() {
        // Today = 5, Yesterday = missed (0), 2 days ago = 5, 3 days ago = 5
        val records = listOf(
            ReadingDayEntity(date = "2026-11-23", cardsReadCount = 5),
            ReadingDayEntity(date = "2026-11-22", cardsReadCount = 0), // Grace day
            ReadingDayEntity(date = "2026-11-21", cardsReadCount = 6),
            ReadingDayEntity(date = "2026-11-20", cardsReadCount = 5)
        )

        val info = StreakCalculator.calculate(records, referenceDate = baseDate)

        // 3 active days + 1 grace day = 4 consecutive days maintained
        assertEquals(4, info.currentStreak)
    }

    @Test
    fun `second missed day in a week breaks streak`() {
        // Today = 5, Day-1 = 0 (grace day 1), Day-2 = 0 (grace day 2 -> breaks)
        val records = listOf(
            ReadingDayEntity(date = "2026-11-23", cardsReadCount = 5),
            ReadingDayEntity(date = "2026-11-22", cardsReadCount = 0),
            ReadingDayEntity(date = "2026-11-21", cardsReadCount = 0),
            ReadingDayEntity(date = "2026-11-20", cardsReadCount = 10)
        )

        val info = StreakCalculator.calculate(records, referenceDate = baseDate)

        // Only today + 1 grace day = 2
        assertEquals(2, info.currentStreak)
    }

    @Test
    fun `weekly activity accurately marks days and counts`() {
        val records = listOf(
            ReadingDayEntity(date = "2026-11-23", cardsReadCount = 5)
        )

        val info = StreakCalculator.calculate(records, referenceDate = baseDate)

        assertEquals(7, info.weeklyActivity.size)
        val todayActivity = info.weeklyActivity.last()
        assertEquals("2026-11-23", todayActivity.date)
        assertEquals(5, todayActivity.count)
        assertTrue(todayActivity.isGoalMet)
        assertTrue(todayActivity.isToday)
    }
}
