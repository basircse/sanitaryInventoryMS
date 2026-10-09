package com.basir.inventory.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.basir.inventory.R
import com.basir.inventory.license.LicenseState
import com.basir.inventory.backup.WorkScheduler
import com.basir.inventory.ui.catalog.CategoriesScreen
import com.basir.inventory.ui.catalog.SuppliersScreen
import com.basir.inventory.ui.home.DashboardScreen
import com.basir.inventory.ui.reports.ReportsScreen
import com.basir.inventory.ui.settings.SettingsScreen
import com.basir.inventory.ui.stock.InventoryScreen
import com.basir.inventory.ui.stock.ItemDetailScreen
import com.basir.inventory.ui.stock.ItemEditScreen
import androidx.compose.ui.platform.LocalContext

object Routes {
    const val HOME = "home"
    const val STOCK = "stock"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"
    const val PICK_TO_SELL = "pick"
    const val CATEGORIES = "categories"
    const val SUPPLIERS = "suppliers"
    const val LICENSE = "license"
    fun item(id: Long, sell: Boolean = false) = "item/$id?sell=$sell"
    fun edit(id: Long) = "edit/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Default.Home),
    Tab(Routes.STOCK, R.string.nav_stock, Icons.Default.Inventory2),
    Tab(Routes.REPORTS, R.string.nav_reports, Icons.Default.Assessment),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings),
)

@Composable
fun MainNav(license: LicenseState.Active) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Expiry reminders are notifications; ask once on Android 13+.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (tabs.any { it.route == route }) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = { nav.switchTab(tab.route) },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
                composable(Routes.HOME) { DashboardScreen(license, nav) }
                composable(Routes.STOCK) { InventoryScreen(nav, pickToSell = false) }
                composable(Routes.PICK_TO_SELL) { InventoryScreen(nav, pickToSell = true) }
                composable(Routes.REPORTS) { ReportsScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(license, nav) }
                composable(Routes.CATEGORIES) { CategoriesScreen(onBack = nav::popBackStack) }
                composable(Routes.SUPPLIERS) { SuppliersScreen(onBack = nav::popBackStack) }
                composable(Routes.LICENSE) {
                    ActivationScreen(
                        state = license,
                        onActivated = { mode -> WorkScheduler.scheduleBackups(context, mode) },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "item/{id}?sell={sell}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.LongType },
                        navArgument("sell") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) { entry ->
                    ItemDetailScreen(
                        itemId = entry.arguments!!.getLong("id"),
                        openSell = entry.arguments!!.getBoolean("sell"),
                        nav = nav,
                    )
                }
                composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    ItemEditScreen(itemId = entry.arguments!!.getLong("id"), nav = nav)
                }
            }
        }
    }
}

fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
