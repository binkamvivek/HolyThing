package com.holything.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.holything.app.ui.screens.feed.FeedScreen
import com.holything.app.ui.screens.feed.FeedViewModel
import com.holything.app.ui.screens.home.HomeScreen
import com.holything.app.ui.screens.profile.ProfileScreen
import com.holything.app.ui.screens.profile.ProfileViewModel
import com.holything.app.ui.screens.saved.SavedScreen
import com.holything.app.ui.screens.saved.SavedViewModel

@Composable
fun HolyThingNavHost(
    navController: NavHostController = rememberNavController(),
    notificationCardId: String? = null,
    onClearNotificationCard: () -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in Screen.bottomNavItems.map { it.route }

    // Shared ViewModels across navigation graph destinations
    val feedViewModel: FeedViewModel = hiltViewModel()
    val savedViewModel: SavedViewModel = hiltViewModel()
    val profileViewModel: ProfileViewModel = hiltViewModel()

    // When app is opened via notification, navigate directly to that card in the feed
    LaunchedEffect(notificationCardId) {
        val targetId = notificationCardId
        if (!targetId.isNullOrBlank()) {
            feedViewModel.openCardInFeed(targetId)
            navController.navigate(Screen.FeedPager.route) {
                launchSingleTop = true
            }
            onClearNotificationCard()
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Screen.bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { screen.icon?.let { Icon(it, contentDescription = screen.title) } },
                            label = { screen.title?.let { Text(it) } },
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = if (currentRoute == Screen.FeedPager.route) {
                Modifier
            } else {
                Modifier.padding(innerPadding)
            }
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = feedViewModel,
                    onOpenFeed = {
                        navController.navigate(Screen.FeedPager.route)
                    }
                )
            }
            composable(Screen.FeedPager.route) {
                FeedScreen(
                    viewModel = feedViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Saved.route) {
                SavedScreen(viewModel = savedViewModel)
            }
            composable(Screen.Profile.route) {
                ProfileScreen(viewModel = profileViewModel)
            }
        }
    }
}

