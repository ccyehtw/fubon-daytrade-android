package com.fubon.daytrade.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fubon.daytrade.ui.screens.DayTradeScreen
import com.fubon.daytrade.ui.screens.FuturesScreen
import com.fubon.daytrade.ui.screens.LoginScreen
import com.fubon.daytrade.ui.screens.QuoteScreen
import com.fubon.daytrade.ui.screens.SettingsScreen
import com.fubon.daytrade.ui.viewmodel.DayTradeViewModel
import androidx.hilt.navigation.compose.hiltViewModel

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit
)

@Composable
fun FubonNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Login.route
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Main.route) {
            MainScreen(
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                    }
                }
            )
        }
    }
}

@Composable
fun MainScreen(
    onLogout: () -> Unit
) {
    val navController = rememberNavController()
    val dayTradeViewModel: DayTradeViewModel = hiltViewModel()

    val items = listOf(
        BottomNavItem(Screen.DayTrade.route, "當沖", { Icon(Icons.Default.Home, contentDescription = "當沖") }),
        BottomNavItem(Screen.Futures.route, "期貨", { Icon(Icons.Default.Home, contentDescription = "期貨") }),
        BottomNavItem(Screen.Settings.route, "設定", { Icon(Icons.Default.Settings, contentDescription = "設定") })
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                items.forEach { item ->
                    NavigationBarItem(
                        icon = item.icon,
                        label = { Text(item.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.DayTrade.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.DayTrade.route) {
                DayTradeScreen(
                    viewModel = dayTradeViewModel,
                    onNavigateToQuote = {
                        navController.navigate(Screen.Quote.route)
                    }
                )
            }
            composable(Screen.Quote.route) {
                QuoteScreen(
                    viewModel = dayTradeViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Futures.route) {
                FuturesScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(onLogout = onLogout)
            }
        }
    }
}