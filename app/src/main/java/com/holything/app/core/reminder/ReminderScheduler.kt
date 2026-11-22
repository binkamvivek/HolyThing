package com.holything.app.core.reminder

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val REMINDER_WORK_TAG = "daily_scripture_reminder_work"
    }

    fun scheduleDailyReminder(timeString: String) {
        val workManager = WorkManager.getInstance(context)

        val targetTime = try {
            LocalTime.parse(timeString, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            LocalTime.of(8, 0)
        }

        val now = LocalDateTime.now()
        var scheduledDateTime = now.with(targetTime)
        if (scheduledDateTime.isBefore(now)) {
            scheduledDateTime = scheduledDateTime.plusDays(1)
        }

        val initialDelayMinutes = Duration.between(now, scheduledDateTime).toMinutes()

        val reminderRequest = PeriodicWorkRequestBuilder<DailyReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .addTag(REMINDER_WORK_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            REMINDER_WORK_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            reminderRequest
        )
    }

    fun cancelReminder() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(REMINDER_WORK_TAG)
    }
}
