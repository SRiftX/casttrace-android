package com.streamcap.mobile.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.ui.home.HomeScreen
import com.streamcap.mobile.ui.player.RecordingPlayerScreen
import com.streamcap.mobile.ui.recordings.RecordingsScreen
import com.streamcap.mobile.ui.roomdetail.RoomDetailScreen
import com.streamcap.mobile.ui.rooms.AddRoomScreen
import com.streamcap.mobile.ui.settings.LogsScreen
import com.streamcap.mobile.ui.settings.PlatformsScreen
import com.streamcap.mobile.ui.settings.SettingsScreen
import com.streamcap.mobile.ui.settings.StorageScreen
import com.streamcap.mobile.ui.settings.BackupScreen
import com.streamcap.mobile.ui.common.MotionTokens
import com.streamcap.mobile.ui.common.rememberMotionScale
import com.streamcap.mobile.ui.common.scaledDuration

private object Routes {
    const val HOME = "home"
    const val RECORDINGS = "recordings"
    const val SETTINGS = "settings"
    const val ADD_ROOM = "add-room"
    const val ROOM_DETAIL = "room/{roomId}"
    const val LOGS = "logs"
    const val PLATFORMS = "platforms"
    const val PLAYER = "player/{recordingId}"

    fun roomDetail(roomId: Long) = "room/$roomId"
    fun player(recordingId: Long) = "player/$recordingId"
}

@Composable
fun StreamCapApp(sharedUrl: String?, notificationRoomId: Long? = null, launchToken: Int = 0) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val container = (context.applicationContext as StreamCapApplication).container
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevelRoutes = setOf(Routes.HOME, Routes.RECORDINGS, Routes.SETTINGS)
    val motionScale = rememberMotionScale()

    LaunchedEffect(sharedUrl, launchToken) {
        if (!sharedUrl.isNullOrBlank()) navController.navigate(Routes.ADD_ROOM)
    }

    LaunchedEffect(notificationRoomId, launchToken) {
        notificationRoomId?.let { navController.navigate(Routes.roomDetail(it)) { launchSingleTop = true } }
    }
    BoxWithConstraints(Modifier.fillMaxHeight()) {
    val wideNavigation = maxWidth >= 900.dp
    Scaffold(
        bottomBar = {
            if (currentRoute in topLevelRoutes && !wideNavigation) {
                NavigationBar {
                    listOf(
                        Triple(Routes.HOME, "直播间", Icons.Outlined.Home),
                        Triple(Routes.RECORDINGS, "视频库", Icons.Outlined.VideoLibrary),
                        Triple(Routes.SETTINGS, "设置", Icons.Outlined.Settings),
                    ).forEach { (route, label, icon) ->
                        NavigationBarItem(
                            selected = currentRoute == route,
                            onClick = {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxHeight()) {
        if (wideNavigation && currentRoute in topLevelRoutes) NavigationRail {
            listOf(
                Triple(Routes.HOME, "直播间", Icons.Outlined.Home),
                Triple(Routes.RECORDINGS, "视频库", Icons.Outlined.VideoLibrary),
                Triple(Routes.SETTINGS, "设置", Icons.Outlined.Settings),
            ).forEach { (route, label, icon) ->
                NavigationRailItem(
                    selected = currentRoute == route,
                    onClick = { navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    } },
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label) },
                )
            }
        }
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.weight(1f),
            enterTransition = {
                fadeIn(tween(scaledDuration(MotionTokens.Emphasis, motionScale))) +
                    slideInHorizontally(tween(scaledDuration(MotionTokens.Emphasis, motionScale))) { it / 5 }
            },
            exitTransition = {
                fadeOut(tween(scaledDuration(MotionTokens.Exit, motionScale))) +
                    slideOutHorizontally(tween(scaledDuration(MotionTokens.Exit, motionScale))) { -it / 12 }
            },
            popEnterTransition = {
                fadeIn(tween(scaledDuration(MotionTokens.Emphasis, motionScale))) +
                    slideInHorizontally(tween(scaledDuration(MotionTokens.Emphasis, motionScale))) { -it / 5 }
            },
            popExitTransition = {
                fadeOut(tween(scaledDuration(MotionTokens.Exit, motionScale))) +
                    slideOutHorizontally(tween(scaledDuration(MotionTokens.Exit, motionScale))) { it / 12 }
            },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    contentPadding = padding,
                    onAddRoom = { navController.navigate(Routes.ADD_ROOM) },
                    onOpenRoom = { navController.navigate(Routes.roomDetail(it)) },
                    onOpenRecording = { navController.navigate(Routes.player(it)) },
                )
            }
            composable(Routes.RECORDINGS) {
                RecordingsScreen(
                    container = container,
                    contentPadding = padding,
                    onOpenRecording = { navController.navigate(Routes.player(it)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    contentPadding = padding,
                    onOpenStorage = { navController.navigate("storage") },
                    onOpenBackup = { navController.navigate("backup") },
                    onOpenLicenses = { navController.navigate("licenses") },
                    onOpenUserGuide = { navController.navigate("user-guide") },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    onOpenPlatforms = { navController.navigate(Routes.PLATFORMS) },
                )
            }
            composable(Routes.ADD_ROOM) {
                AddRoomScreen(
                    container = container,
                    initialUrl = sharedUrl,
                    onBack = navController::popBackStack,
                    onSaved = { roomId ->
                        navController.navigate(Routes.roomDetail(roomId)) {
                            popUpTo(Routes.ADD_ROOM) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.ROOM_DETAIL,
                arguments = listOf(navArgument("roomId") { type = NavType.LongType }),
            ) { entry ->
                RoomDetailScreen(
                    roomId = entry.arguments?.getLong("roomId") ?: return@composable,
                    container = container,
                    onBack = navController::popBackStack,
                )
            }
            composable("storage") { StorageScreen(container, navController::popBackStack) { navController.navigate(Routes.RECORDINGS) { popUpTo(Routes.HOME); launchSingleTop = true } } }
            composable("backup") { BackupScreen(container, navController::popBackStack) }
            composable("licenses") { com.streamcap.mobile.ui.settings.OpenSourceScreen(navController::popBackStack) }
            composable("user-guide") { com.streamcap.mobile.ui.settings.UserGuideScreen(navController::popBackStack) }
            composable(Routes.LOGS) {
                LogsScreen(container = container, onBack = navController::popBackStack)
            }
            composable(Routes.PLATFORMS) {
                PlatformsScreen(container = container, onBack = navController::popBackStack)
            }
            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("recordingId") { type = NavType.LongType }),
            ) { entry ->
                RecordingPlayerScreen(
                    recordingId = entry.arguments?.getLong("recordingId") ?: return@composable,
                    container = container,
                    onBack = navController::popBackStack,
                )
            }
        }
        }
    }
    }
    }
