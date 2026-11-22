package com.holything.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.ui.navigation.HolyThingNavHost
import com.holything.app.ui.theme.HolyThingTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var dataStore: UserPreferencesDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val textScale by dataStore.textScale.collectAsState(initial = 1.0f)
            val darkModeConfig by dataStore.darkModeConfig.collectAsState(initial = "SYSTEM")
            val hasSeenWelcome by dataStore.hasSeenWelcome.collectAsState(initial = false)

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
                    hasSeenWelcome = hasSeenWelcome,
                    onCompleteWelcome = {
                        lifecycleScope.launch {
                            dataStore.setHasSeenWelcome(true)
                        }
                    }
                )
            }
        }
    }
}

