package com.holything.app.ui.screens.feed.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object CardBackgroundGradients {

    fun getGradient(tone: String, isDark: Boolean = true): Brush {
        // Card backgrounds are always dark gradients in both light and dark theme
        val colors = when (tone.lowercase()) {
            "comforting" -> listOf(Color(0xFF161B22), Color(0xFF1C2430), Color(0xFF14181E))
            "inspiring" -> listOf(Color(0xFF1C1A17), Color(0xFF2B251E), Color(0xFF161514))
            "historical" -> listOf(Color(0xFF171A1B), Color(0xFF1D2628), Color(0xFF121415))
            "challenging" -> listOf(Color(0xFF1D1719), Color(0xFF281E21), Color(0xFF161314))
            else -> listOf(Color(0xFF131517), Color(0xFF1E2226), Color(0xFF121415))
        }
        return Brush.verticalGradient(colors)
    }
}
