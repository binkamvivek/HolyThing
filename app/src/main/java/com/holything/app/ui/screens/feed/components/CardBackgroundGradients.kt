package com.holything.app.ui.screens.feed.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object CardBackgroundGradients {

    fun getGradient(tone: String, isDark: Boolean): Brush {
        val colors = if (isDark) {
            when (tone.lowercase()) {
                "comforting" -> listOf(Color(0xFF161B22), Color(0xFF1C2430), Color(0xFF14181E))
                "inspiring" -> listOf(Color(0xFF1C1A17), Color(0xFF2B251E), Color(0xFF161514))
                "historical" -> listOf(Color(0xFF171A1B), Color(0xFF1D2628), Color(0xFF121415))
                "challenging" -> listOf(Color(0xFF1D1719), Color(0xFF281E21), Color(0xFF161314))
                else -> listOf(Color(0xFF131517), Color(0xFF1E2226), Color(0xFF121415))
            }
        } else {
            when (tone.lowercase()) {
                "comforting" -> listOf(Color(0xFFF9F7F4), Color(0xFFF0EAE1), Color(0xFFFAF8F5))
                "inspiring" -> listOf(Color(0xFFFAF7F0), Color(0xFFF5EEDC), Color(0xFFFAF8F5))
                "historical" -> listOf(Color(0xFFF4F7F8), Color(0xFFE8EEF1), Color(0xFFFAFBFB))
                "challenging" -> listOf(Color(0xFFF9F4F4), Color(0xFFEFE6E6), Color(0xFFFBF8F8))
                else -> listOf(Color(0xFFFAF8F5), Color(0xFFEFECE6), Color(0xFFFAF8F5))
            }
        }
        return Brush.verticalGradient(colors)
    }
}
