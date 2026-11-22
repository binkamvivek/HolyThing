package com.holything.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String? = null, val icon: ImageVector? = null) {
    object Welcome : Screen(route = "welcome")
    object Feed : Screen(route = "feed", title = "Feed", icon = Icons.AutoMirrored.Filled.MenuBook)
    object Saved : Screen(route = "saved", title = "Saved", icon = Icons.Default.Bookmark)
    object Profile : Screen(route = "profile", title = "Profile", icon = Icons.Default.Person)

    companion object {
        val bottomNavItems = listOf(Feed, Saved, Profile)
    }
}

