package com.streamcap.mobile.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.saveable.rememberSaveable
import com.streamcap.mobile.ui.home.HomeScreen
import com.streamcap.mobile.ui.player.RecordingPlayerScreen
import com.streamcap.mobile.ui.recordings.RecordingsScreen
import com.streamcap.mobile.ui.reminders.LiveRemindersScreen
import com.streamcap.mobile.ui.reminders.LiveReminderPlayerScreen
import com.streamcap.mobile.ui.roomdetail.RoomDetailScreen
import com.streamcap.mobile.ui.rooms.AddRoomScreen
import com.streamcap.mobile.ui.settings.LogsScreen
import com.streamcap.mobile.ui.settings.PlatformsScreen
import com.streamcap.mobile.ui.settings.SettingsScreen
import com.streamcap.mobile.ui.settings.SupportDialog
import com.streamcap.mobile.ui.settings.StorageScreen
import com.streamcap.mobile.ui.settings.BackupScreen
import com.streamcap.mobile.ui.common.MotionTokens
import com.streamcap.mobile.ui.common.FeatureTourAnchor
import com.streamcap.mobile.ui.common.FeatureTourOverlay
import com.streamcap.mobile.ui.common.featureTourAnchor
import com.streamcap.mobile.ui.common.featureTourSteps
import com.streamcap.mobile.ui.common.rememberMotionScale
import com.streamcap.mobile.ui.common.scaledDuration

private object Routes {
    const val HOME = "home"
    const val RECORDINGS = "recordings"
    const val SETTINGS = "settings"
    const val REMINDERS = "reminders"
    const val ADD_ROOM = "add-room"
    const val ROOM_DETAIL = "room/{roomId}"
    const val LOGS = "logs"
    const val PLATFORMS = "platforms"
    const val PLAYER = "player/{recordingId}"
    const val LIVE_REMINDER_PLAYER = "live-reminder-player/{reminderId}"

    fun roomDetail(roomId: Long) = "room/$roomId"
    fun player(recordingId: Long) = "player/$recordingId"
    fun liveReminderPlayer(reminderId: Long) = "live-reminder-player/$reminderId"
}

@Composable
fun StreamCapApp(sharedUrl: String?, notificationRoomId: Long? = null, launchToken: Int = 0, openReminders: Boolean = false) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val welcomePreferences = remember(context) {
        context.getSharedPreferences("welcome", android.content.Context.MODE_PRIVATE)
    }
    var showWelcome by remember { mutableStateOf(!welcomePreferences.getBoolean("shown", false)) }
    var showWelcomeQr by remember { mutableStateOf(false) }
    var showFeatureTour by remember(welcomePreferences) {
        mutableStateOf(
            !welcomePreferences.getBoolean("feature_tour_shown", false) &&
                (!welcomePreferences.getBoolean("shown", false) ||
                    welcomePreferences.getBoolean("feature_tour_pending", false)),
        )
    }
    var featureTourStep by rememberSaveable { mutableStateOf(0) }
    var featureTourTargets by remember { mutableStateOf<Map<FeatureTourAnchor, Rect>>(emptyMap()) }
    val updateFeatureTourTarget: (FeatureTourAnchor, Rect) -> Unit = { anchor, bounds ->
        if (bounds.width > 0f && bounds.height > 0f && featureTourTargets[anchor] != bounds) {
            featureTourTargets = featureTourTargets + (anchor to bounds)
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevelRoutes = setOf(Routes.HOME, Routes.REMINDERS, Routes.RECORDINGS, Routes.SETTINGS)
    val motionScale = rememberMotionScale()

    LaunchedEffect(showFeatureTour, featureTourStep) {
        if (showFeatureTour) {
            val route = when (featureTourSteps[featureTourStep].anchor) {
                FeatureTourAnchor.HOME, FeatureTourAnchor.ADD_ROOM -> Routes.HOME
                FeatureTourAnchor.REMINDERS -> Routes.REMINDERS
                FeatureTourAnchor.RECORDINGS -> Routes.RECORDINGS
                FeatureTourAnchor.SETTINGS -> Routes.SETTINGS
            }
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val finishFeatureTour = {
        welcomePreferences.edit()
            .putBoolean("feature_tour_shown", true)
            .putBoolean("feature_tour_pending", false)
            .apply()
        showFeatureTour = false
        featureTourStep = 0
    }

    LaunchedEffect(sharedUrl, launchToken) {
        if (!sharedUrl.isNullOrBlank()) navController.navigate(Routes.ADD_ROOM)
    }

    LaunchedEffect(notificationRoomId, launchToken) {
        notificationRoomId?.let { navController.navigate(Routes.roomDetail(it)) { launchSingleTop = true } }
    }
    LaunchedEffect(openReminders, launchToken) {
        if (openReminders) navController.navigate(Routes.REMINDERS) { launchSingleTop = true }
    }
    BoxWithConstraints(Modifier.fillMaxHeight()) {
    val wideNavigation = maxWidth >= 900.dp
    Box(Modifier.fillMaxSize()) {
    Scaffold { padding ->
        val pagePadding = if (currentRoute in topLevelRoutes && !wideNavigation) {
            PaddingValues(top = padding.calculateTopPadding())
        } else padding
        Row(Modifier.fillMaxHeight()) {
        if (wideNavigation && currentRoute in topLevelRoutes) NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            listOf(
                Triple(Routes.HOME, "直播间", Icons.Outlined.Home),
                Triple(Routes.REMINDERS, "提醒", Icons.Outlined.NotificationsActive),
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
                    modifier = Modifier.featureTourAnchor(tourAnchorForRoute(route), updateFeatureTourTarget),
                    colors = NavigationRailItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ),
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
                    contentPadding = pagePadding,
                    onAddRoom = { navController.navigate(Routes.ADD_ROOM) },
                    onOpenRoom = { navController.navigate(Routes.roomDetail(it)) },
                    onOpenRecording = { navController.navigate(Routes.player(it)) },
                    onTourTarget = updateFeatureTourTarget,
                )
            }
            composable(Routes.RECORDINGS) {
                RecordingsScreen(
                    contentPadding = pagePadding,
                    onOpenRecording = { navController.navigate(Routes.player(it)) },
                )
            }
            composable(Routes.REMINDERS) {
                LiveRemindersScreen(
                    contentPadding = pagePadding,
                    onWatch = { navController.navigate(Routes.liveReminderPlayer(it)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    contentPadding = pagePadding,
                    onOpenStorage = { navController.navigate("storage") },
                    onOpenBackup = { navController.navigate("backup") },
                    onOpenLicenses = { navController.navigate("licenses") },
                    onOpenUserGuide = { navController.navigate("user-guide") },
                    onOpenFeatureTour = {
                        featureTourStep = 0
                        welcomePreferences.edit().putBoolean("feature_tour_pending", true).apply()
                        showFeatureTour = true
                    },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    onOpenPlatforms = { navController.navigate(Routes.PLATFORMS) },
                )
            }
            composable(Routes.ADD_ROOM) {
                AddRoomScreen(
                    initialUrl = sharedUrl,
                    onBack = navController::popBackStack,
                    onOpenBackup = { navController.navigate("backup") },
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
                    onBack = navController::popBackStack,
                )
            }
            composable("storage") { StorageScreen(navController::popBackStack) { navController.navigate(Routes.RECORDINGS) { popUpTo(Routes.HOME); launchSingleTop = true } } }
            composable("backup") { BackupScreen(navController::popBackStack) }
            composable("licenses") { com.streamcap.mobile.ui.settings.OpenSourceScreen(navController::popBackStack) }
            composable("user-guide") { com.streamcap.mobile.ui.settings.UserGuideScreen(navController::popBackStack) }
            composable(Routes.LOGS) {
                LogsScreen(onBack = navController::popBackStack)
            }
            composable(Routes.PLATFORMS) {
                PlatformsScreen(onBack = navController::popBackStack)
            }
            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("recordingId") { type = NavType.LongType }),
            ) { entry ->
                RecordingPlayerScreen(
                    recordingId = entry.arguments?.getLong("recordingId") ?: return@composable,
                    onBack = navController::popBackStack,
                )
            }
            composable(
                route = Routes.LIVE_REMINDER_PLAYER,
                arguments = listOf(navArgument("reminderId") { type = NavType.LongType }),
            ) { entry ->
                LiveReminderPlayerScreen(
                    reminderId = entry.arguments?.getLong("reminderId") ?: return@composable,
                    contentPadding = padding,
                    onBack = navController::popBackStack,
                )
            }
        }
        }
    }
            if (currentRoute in topLevelRoutes && !wideNavigation) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 14.dp,
                    tonalElevation = 2.dp,
                ) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        Triple(Routes.HOME, "直播间", Icons.Outlined.Home),
                        Triple(Routes.REMINDERS, "提醒", Icons.Outlined.NotificationsActive),
                        Triple(Routes.RECORDINGS, "视频库", Icons.Outlined.VideoLibrary),
                        Triple(Routes.SETTINGS, "设置", Icons.Outlined.Settings),
                    ).forEach { (route, label, icon) ->
                        val selected = currentRoute == route
                        Surface(
                            modifier = Modifier.weight(1f).heightIn(min = 58.dp)
                                .featureTourAnchor(tourAnchorForRoute(route), updateFeatureTourTarget),
                            shape = RoundedCornerShape(24.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else Color.Transparent,
                        ) {
                        Column(
                            Modifier.clickable {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            val itemColor = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp), tint = itemColor)
                            Text(label, style = MaterialTheme.typography.labelSmall, color = itemColor)
                        }
                        }
                    }
                }
                }
                }
            }
            if (showFeatureTour && !showWelcome && !showWelcomeQr) {
                val step = featureTourSteps[featureTourStep]
                FeatureTourOverlay(
                    stepIndex = featureTourStep,
                    step = step,
                    targetBounds = featureTourTargets[step.anchor],
                    onNext = {
                        if (featureTourStep == featureTourSteps.lastIndex) finishFeatureTour()
                        else featureTourStep += 1
                    },
                    onSkip = finishFeatureTour,
                )
            }
    }
    }
    if (showWelcome) SupportDialog(
        firstLaunch = true,
        onDismiss = {
            val editor = welcomePreferences.edit().putBoolean("shown", true)
            if (!welcomePreferences.getBoolean("feature_tour_shown", false)) {
                editor.putBoolean("feature_tour_pending", true)
            }
            editor.apply()
            showWelcome = false
        },
        onShowQr = { showWelcomeQr = true },
    )
    if (showWelcomeQr) SupportDialog(firstLaunch = false, onDismiss = { showWelcomeQr = false })
    }

private fun tourAnchorForRoute(route: String): FeatureTourAnchor = when (route) {
    Routes.HOME -> FeatureTourAnchor.HOME
    Routes.REMINDERS -> FeatureTourAnchor.REMINDERS
    Routes.RECORDINGS -> FeatureTourAnchor.RECORDINGS
    else -> FeatureTourAnchor.SETTINGS
}
