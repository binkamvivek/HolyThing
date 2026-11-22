package com.holything.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.holything.app.core.reminder.DailyReminderWorker
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.ui.navigation.HolyThingNavHost
import com.holything.app.ui.theme.HolyThingTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var dataStore: UserPreferencesDataStore

    private var notificationCardId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialCard = intent?.getStringExtra(DailyReminderWorker.EXTRA_CARD_ID)
        if (!initialCard.isNullOrBlank()) {
            notificationCardId = initialCard
        }

        setContent {
            val textScale by dataStore.textScale.collectAsState(initial = 1.0f)
            val darkModeConfig by dataStore.darkModeConfig.collectAsState(initial = "SYSTEM")

            val isDark = when (darkModeConfig) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemInDarkTheme()
            }

            HolyThingTheme(
                darkTheme = isDark,
                textScaleFactor = textScale
            ) {
                HolyThingNavHost(
                    notificationCardId = notificationCardId,
                    onClearNotificationCard = { notificationCardId = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val cardId = intent.getStringExtra(DailyReminderWorker.EXTRA_CARD_ID)
        if (!cardId.isNullOrBlank()) {
            notificationCardId = cardId
        }
    }
}
