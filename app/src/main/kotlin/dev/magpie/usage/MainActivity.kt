package dev.magpie.usage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.magpie.usage.MagpieApp
import dev.magpie.usage.ui.settings.SettingsScreen
import dev.magpie.usage.ui.settings.SettingsViewModel
import dev.magpie.usage.ui.theme.Card
import dev.magpie.usage.ui.theme.MagpieTheme
import dev.magpie.usage.ui.theme.Page
import dev.magpie.usage.ui.theme.Purple
import dev.magpie.usage.ui.theme.TextSecondary
import dev.magpie.usage.ui.usage.OverviewScreen
import dev.magpie.usage.ui.usage.OrganizeScreen
import dev.magpie.usage.ui.usage.UsageScreen
import dev.magpie.usage.ui.usage.UsageViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val magpie = application as MagpieApp
        // Draw under the system bars ourselves and pad the root with the
        // safe-drawing insets, so the header never collides with the status
        // bar (enforced edge-to-edge on Android 15+).
        enableEdgeToEdge()
        val usageViewModel = UsageViewModel(magpie)
        val settingsViewModel = SettingsViewModel(magpie)
        setContent {
            val appSettings by magpie.settingsStore.settings.collectAsState(initial = null)
            MagpieTheme(themeMode = appSettings?.themeMode ?: "system") {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Page)
                        .safeDrawingPadding()
                ) {
                    val nav = rememberNavController()
                    val lifecycleOwner = LocalLifecycleOwner.current
                    var resumedOnce by remember { mutableStateOf(false) }
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> {
                                    usageViewModel.onResumed()
                                    resumedOnce = true
                                }
                                Lifecycle.Event.ON_PAUSE -> usageViewModel.onPaused()
                                else -> {}
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }
                    Scaffold(
                        containerColor = Page,
                        bottomBar = {
                            // 两个一级入口：概览（额度+今日）、用量（完整看板）。
                            val backStack by nav.currentBackStackEntryAsState()
                            val route = backStack?.destination?.route
                            if (route == "usage" || route == "overview") {
                                NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer) {
                                    NavigationBarItem(
                                        selected = route == "overview",
                                        onClick = {
                                            // 回到栈底再切换：两个 tab 互为顶层目的地，
                                            // 不互相堆栈——返回键一次就能退出应用，
                                            // 来回点多少次都不会把返回栈垫高。
                                            nav.navigate("overview") {
                                                popUpTo("overview") { saveState = true; inclusive = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(Icons.Outlined.SpaceDashboard, contentDescription = null) },
                                        label = { Text("概览") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Purple,
                                            selectedTextColor = Purple,
                                            indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary,
                                        ),
                                    )
                                    NavigationBarItem(
                                        selected = route == "usage",
                                        onClick = {
                                            nav.navigate("usage") {
                                                popUpTo("overview") { saveState = true; inclusive = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(Icons.Outlined.BarChart, contentDescription = null) },
                                        label = { Text("用量") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Purple,
                                            selectedTextColor = Purple,
                                            indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary,
                                        ),
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        // 大屏（平板/折叠屏展开）时把内容收在可读宽度内居中。
                        Box(
                            Modifier.padding(padding).fillMaxSize(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            NavHost(
                                navController = nav,
                                startDestination = "overview",
                                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                            ) {
                                composable("overview") {
                                    OverviewScreen(
                                        usageViewModel,
                                        onOpenSettings = { nav.navigate("settings") },
                                        onOpenOrganize = { nav.navigate("organize") },
                                    )
                                }
                                composable("usage") {
                                    UsageScreen(usageViewModel, onOpenSettings = { nav.navigate("settings") })
                                }
                                composable("organize") {
                                    OrganizeScreen(usageViewModel, onDone = { nav.popBackStack() })
                                }
                                composable("settings") {
                                    SettingsScreen(settingsViewModel, onBack = { nav.popBackStack() })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
