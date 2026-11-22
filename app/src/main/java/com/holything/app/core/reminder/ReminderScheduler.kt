package com.holything.app.core.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.holything.app.MainActivity
import com.holything.app.R
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
        var scheduledDateTime = now.withHour(targetTime.hour).withMinute(targetTime.minute).withSecond(0).withNano(0)

        // If the scheduled time was earlier today (by more than 60 seconds), roll over to tomorrow
        if (scheduledDateTime.isBefore(now.minusSeconds(60))) {
            scheduledDateTime = scheduledDateTime.plusDays(1)
        }

        val delaySeconds = Duration.between(now, scheduledDateTime).seconds
        val initialDelaySeconds = if (delaySeconds <= 5) 10L else delaySeconds

        val reminderRequest = PeriodicWorkRequestBuilder<DailyReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            .addTag(REMINDER_WORK_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            REMINDER_WORK_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            reminderRequest
        )
    }

    fun postNotificationImmediately(cardId: String, hookText: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DailyReminderWorker.CHANNEL_ID,
                "Daily Scripture Reminder",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Gentle daily invitation to read and reflect on Scripture."
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(DailyReminderWorker.EXTRA_CARD_ID, cardId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            DailyReminderWorker.NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, DailyReminderWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("HolyThing")
            .setContentText(hookText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(hookText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(DailyReminderWorker.NOTIFICATION_ID, notification)
    }

    fun cancelReminder() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(REMINDER_WORK_TAG)
    }
}
