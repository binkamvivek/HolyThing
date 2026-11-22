package com.holything.app.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.data.local.room.dao.CardDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun reminderScheduler(): ReminderScheduler
    fun cardDao(): CardDao
    fun dataStore(): UserPreferencesDataStore
}

class DailyReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReminderEntryPoint::class.java
        )
        val scheduler = entryPoint.reminderScheduler()
        val cardDao = entryPoint.cardDao()
        val dataStore = entryPoint.dataStore()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    scheduler.rescheduleFromPreferences()
                    return@launch
                }

                if (intent.action == ReminderScheduler.ACTION_TRIGGER_REMINDER) {
                    val slotIndex = intent.getIntExtra(ReminderScheduler.EXTRA_SLOT_INDEX, ReminderScheduler.SLOT_FIXED)

                    val shouldDeliver = if (slotIndex == ReminderScheduler.SLOT_FIXED) {
                        dataStore.reminderEnabled.first()
                    } else {
                        dataStore.randomReminderEnabled.first()
                    }

                    if (shouldDeliver) {
                        val activeCards = cardDao.getActiveCardsList()
                        val chosen = activeCards.shuffled().firstOrNull()
                        if (chosen != null) {
                            scheduler.postNotificationImmediately(chosen.id, chosen.hook)
                        }

                        if (slotIndex == ReminderScheduler.SLOT_FIXED) {
                            scheduler.rescheduleDailyReminderForTomorrow()
                        } else {
                            scheduler.rescheduleRandomSlotForTomorrow(slotIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                // Safe handling
            } finally {
                pendingResult.finish()
            }
        }
    }
}
