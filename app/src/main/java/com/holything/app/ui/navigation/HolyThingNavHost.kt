package com.holything.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in Screen.bottomNavItems.map { it.route }

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
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                val feedViewModel: FeedViewModel = hiltViewModel()
                HomeScreen(
                    viewModel = feedViewModel,
                    onOpenFeed = {
                        navController.navigate(Screen.FeedPager.route)
                    }
                )
            }
            composable(Screen.FeedPager.route) {
                val feedViewModel: FeedViewModel = hiltViewModel()
                FeedScreen(
                    viewModel = feedViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Saved.route) {
                val savedViewModel: SavedViewModel = hiltViewModel()
                SavedScreen(viewModel = savedViewModel)
            }
            composable(Screen.Profile.route) {
                val profileViewModel: ProfileViewModel = hiltViewModel()
                ProfileScreen(viewModel = profileViewModel)
            }
        }
    }
}

