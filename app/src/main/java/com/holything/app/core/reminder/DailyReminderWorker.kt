package com.holything.app.core.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.holything.app.MainActivity
import com.holything.app.R
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.local.room.dao.CardDao
import com.holything.app.data.local.room.dao.ReadingDayDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@HiltWorker
class DailyReminderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val dataStore: UserPreferencesDataStore,
    private val cardDao: CardDao,
    private val readingDayDao: ReadingDayDao
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val CHANNEL_ID = "daily_scripture_reminder"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_CARD_ID = "extra_card_id"
    }

    override suspend fun doWork(): Result {
        val enabled = dataStore.reminderEnabled.first()
        if (!enabled) return Result.success()

        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // 1. Check if user already read >= 5 cards today; if so, skip (never guilt or spam)
        val todayReading = readingDayDao.getReadingDay(todayStr)
        if ((todayReading?.cardsReadCount ?: 0) >= 5) {
            return Result.success()
        }

        // 2. Check backoff: if 5 in a row are ignored, back off to ~3 per week (Mon, Wed, Fri)
        val ignoredCount = dataStore.reminderIgnoredCount.first()
        if (ignoredCount >= 5) {
            val allowedDays = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
            if (today.dayOfWeek !in allowedDays) {
                return Result.success()
            }
        }

        // 3. Pick a card to show hook line from
        val activeCards = cardDao.getActiveCardsList()
        if (activeCards.isEmpty()) return Result.success()

        val chosenCard = activeCards.shuffled().first()

        // 4. Post Notification with the hook line (not the verse)
        postReminderNotification(chosenCard.id, chosenCard.hook)

        // Track that notification was posted
        dataStore.incrementReminderIgnored()

        return Result.success()
    }

    private fun postReminderNotification(cardId: String, hookText: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Daily Scripture Reminder",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Gentle daily invitation to read and reflect on Scripture."
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CARD_ID, cardId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("HolyThing")
            .setContentText(hookText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(hookText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
