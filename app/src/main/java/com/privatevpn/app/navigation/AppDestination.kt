package com.privatevpn.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import com.privatevpn.app.R

sealed class AppDestination(
    val route: String,
    @StringRes val titleRes: Int,
    @StringRes val bottomTitleRes: Int = titleRes,
    val showInBottomBar: Boolean,
    val icon: ImageVector? = null
) {
    data object Home : AppDestination("home", R.string.nav_home, R.string.nav_home, true, Icons.Filled.Home)
    data object Profiles : AppDestination("profiles", R.string.nav_profiles, R.string.nav_profiles, true, Icons.Filled.Storage)
    data object Add : AppDestination("add", R.string.nav_add, R.string.nav_add, true, Icons.Filled.Add)
    data object Traffic : AppDestination(
        "traffic",
        R.string.nav_traffic,
        R.string.nav_traffic,
        true,
        Icons.Filled.ShowChart
    )
    data object PrivateSession : AppDestination(
        "private_session",
        R.string.nav_private_session,
        R.string.nav_private_session,
        false,
        Icons.Filled.Shuffle
    )
    data object Logs : AppDestination("logs", R.string.nav_logs, R.string.nav_logs, false)
    data object Dns : AppDestination("dns", R.string.nav_dns, R.string.nav_dns, false)
    data object Settings : AppDestination("settings", R.string.nav_settings, R.string.nav_settings, true, Icons.Filled.Settings)

    companion object {
        val all: List<AppDestination> by lazy {
            listOf(Home, Profiles, Add, Traffic, PrivateSession, Logs, Dns, Settings)
        }
        val bottomBarItems: List<AppDestination> by lazy { all.filter { it.showInBottomBar } }
        val topLevelItems: List<AppDestination>
            get() = bottomBarItems

        fun fromRoute(route: String?): AppDestination =
            fromRouteOrNull(route) ?: Home

        fun fromRouteOrNull(route: String?): AppDestination? =
            all.firstOrNull { it.route == route }

        fun topLevelForRoute(route: String?): AppDestination {
            return when (fromRouteOrNull(route)) {
                Home -> Home
                Profiles -> Profiles
                Add -> Add
                Traffic -> Traffic
                PrivateSession, Settings, Logs, Dns -> Settings
                null -> Home
            }
        }
    }
}
