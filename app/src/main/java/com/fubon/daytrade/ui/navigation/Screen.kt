package com.fubon.daytrade.ui.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Main : Screen("main")
    data object DayTrade : Screen("main/daytrade")
    data object Futures : Screen("main/futures")
    data object Settings : Screen("main/settings")
}