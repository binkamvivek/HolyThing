package com.holything.app.core.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.holything.app.MainActivity
import com.holything.app.R
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: UserPreferencesDataStore
) {
    companion object {
        const val ACTION_TRIGGER_REMINDER = "com.holything.app.ACTION_TRIGGER_REMINDER"
        const val EXTRA_SLOT_INDEX = "extra_slot_index"
        const val REQUEST_CODE_FIXED = 1000
        const val REQUEST_CODE_BASE = 2000
        const val SLOT_FIXED = -1
        const val MAX_SLOTS = 3
    }

    suspend fun rescheduleFromPreferences() {
        val enabled = dataStore.reminderEnabled.first()
        if (enabled) {
            val time = dataStore.reminderTime.first()
            scheduleDailyReminder(time)
        } else {
            cancelDailyReminder()
        }

        val randomEnabled = dataStore.randomReminderEnabled.first()
        if (randomEnabled) {
            val startTime = dataStore.reminderStartTime.first()
            val endTime = dataStore.reminderEndTime.first()
            val frequency = dataStore.reminderFrequency.first()
            scheduleRandomReminders(startTime, endTime, frequency)
        } else {
            cancelRandomReminders()
        }
    }

    fun scheduleDailyReminder(timeStr: String = "08:00") {
        cancelDailyReminder()

        val time = try {
            LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            LocalTime.of(8, 0)
        }

        val now = LocalDateTime.now()
        var targetDateTime = LocalDateTime.of(LocalDate.now(), time)
        if (targetDateTime.isBefore(now.plusSeconds(30))) {
            targetDateTime = targetDateTime.plusDays(1)
        }

        val triggerAtMillis = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setAlarmForSlot(SLOT_FIXED, triggerAtMillis)
    }

    fun cancelDailyReminder() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailyReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_REMINDER
            putExtra(EXTRA_SLOT_INDEX, SLOT_FIXED)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_FIXED,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    suspend fun rescheduleDailyReminderForTomorrow() {
        val timeStr = dataStore.reminderTime.first()
        val time = try {
            LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            LocalTime.of(8, 0)
        }
        val tomorrow = LocalDate.now().plusDays(1)
        val targetDateTime = LocalDateTime.of(tomorrow, time)
        val triggerAtMillis = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setAlarmForSlot(SLOT_FIXED, triggerAtMillis)
    }

    fun scheduleRandomReminders(
        startTimeStr: String = "09:00",
        endTimeStr: String = "21:00",
        frequency: Int = 2
    ) {
        cancelRandomReminders()

        val startTime = try {
            LocalTime.parse(startTimeStr, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            LocalTime.of(9, 0)
        }

        val endTime = try {
            LocalTime.parse(endTimeStr, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            LocalTime.of(21, 0)
        }

        val startMinutes = startTime.hour * 60 + startTime.minute
        val endMinutesRaw = endTime.hour * 60 + endTime.minute
        val totalWindowMinutes = if (endMinutesRaw > startMinutes) {
            endMinutesRaw - startMinutes
        } else {
            (24 * 60 - startMinutes) + endMinutesRaw
        }.coerceAtLeast(30)

        val slots = frequency.coerceIn(2, MAX_SLOTS)
        val chunkSize = totalWindowMinutes / slots
        val now = LocalDateTime.now()
        val todayDate = LocalDate.now()

        for (i in 0 until slots) {
            val minOffset = 2
            val maxOffset = maxOf(minOffset + 1, chunkSize - 2)
            val randomOffsetInChunk = (minOffset until maxOffset).random()
            val totalOffsetMinutes = (i * chunkSize) + randomOffsetInChunk

            val targetMinuteOfDay = (startMinutes + totalOffsetMinutes) % (24 * 60)
            val targetHour = targetMinuteOfDay / 60
            val targetMin = targetMinuteOfDay % 60
            val targetTime = LocalTime.of(targetHour, targetMin)

            var targetDateTime = LocalDateTime.of(todayDate, targetTime)
            if (targetDateTime.isBefore(now.plusSeconds(30))) {
                targetDateTime = targetDateTime.plusDays(1)
            }

            val triggerAtMillis = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            setAlarmForSlot(i, triggerAtMillis)
        }
    }

    suspend fun rescheduleRandomSlotForTomorrow(slotIndex: Int) {
        val startTimeStr = dataStore.reminderStartTime.first()
        val endTimeStr = dataStore.reminderEndTime.first()
        val frequency = dataStore.reminderFrequency.first()

        val startTime = try {
            LocalTime.parse(startTimeStr, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            LocalTime.of(9, 0)
        }

        val endTime = try {
            LocalTime.parse(endTimeStr, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            LocalTime.of(21, 0)
        }

        val startMinutes = startTime.hour * 60 + startTime.minute
        val endMinutesRaw = endTime.hour * 60 + endTime.minute
        val totalWindowMinutes = if (endMinutesRaw > startMinutes) {
            endMinutesRaw - startMinutes
        } else {
            (24 * 60 - startMinutes) + endMinutesRaw
        }.coerceAtLeast(30)

        val slots = frequency.coerceIn(2, MAX_SLOTS)
        val chunkSize = totalWindowMinutes / slots
        val tomorrowDate = LocalDate.now().plusDays(1)

        val minOffset = 2
        val maxOffset = maxOf(minOffset + 1, chunkSize - 2)
        val randomOffsetInChunk = (minOffset until maxOffset).random()
        val totalOffsetMinutes = (slotIndex * chunkSize) + randomOffsetInChunk

        val targetMinuteOfDay = (startMinutes + totalOffsetMinutes) % (24 * 60)
        val targetHour = targetMinuteOfDay / 60
        val targetMin = targetMinuteOfDay % 60
        val targetTime = LocalTime.of(targetHour, targetMin)

        val targetDateTime = LocalDateTime.of(tomorrowDate, targetTime)
        val triggerAtMillis = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        setAlarmForSlot(slotIndex, triggerAtMillis)
    }

    fun cancelRandomReminders() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until MAX_SLOTS) {
            val intent = Intent(context, DailyReminderReceiver::class.java).apply {
                action = ACTION_TRIGGER_REMINDER
                putExtra(EXTRA_SLOT_INDEX, i)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BASE + i,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    private fun setAlarmForSlot(slotIndex: Int, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, DailyReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_REMINDER
            putExtra(EXTRA_SLOT_INDEX, slotIndex)
        }

        val requestCode = if (slotIndex == SLOT_FIXED) REQUEST_CODE_FIXED else (REQUEST_CODE_BASE + slotIndex)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    fun cancelAllReminders() {
        cancelDailyReminder()
        cancelRandomReminders()
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
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
}
