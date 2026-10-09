package com.example.lifemanager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifemanager.ui.settings.SettingsScreen
import com.example.lifemanager.ui.settings.SettingsViewModel
import com.example.lifemanager.ui.habit.HabitScreen
import com.example.lifemanager.ui.todo.TodoScreen
import com.example.lifemanager.ui.todo.TodoViewModel
import com.example.lifemanager.ui.schedule.ScheduleScreen
import com.example.lifemanager.ui.schedule.ScheduleViewModel
import com.example.lifemanager.ui.subscription.SubscriptionScreen
import com.example.lifemanager.ui.subscription.SubscriptionViewModel
import kotlinx.coroutines.flow.first

private const val TodoRoute = "todo"
private const val SettingsRoute = "settings"
private const val ScheduleRoute = "schedule"
private const val HabitRoute = "habit"
private const val SubscriptionRoute = "subscription"

@Composable
fun NavGraph(todoViewModel: TodoViewModel, scheduleViewModel: ScheduleViewModel, settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier, todoRequest: TodoNavigationRequest? = null,
    onTodoConsumed: (Long) -> Unit = {},
    scheduleRequest: ScheduleNavigationRequest? = null, onScheduleConsumed: (Long) -> Unit = {},
    subscriptionRequest: SubscriptionNavigationRequest? = null, onSubscriptionConsumed: (Long) -> Unit = {},
    todoNavigation: TodoNavigationViewModel? = null,
    scheduleNavigation: ScheduleNavigationViewModel? = null,
    subscriptionNavigation: SubscriptionNavigationViewModel? = null,
    backupViewModel: com.example.lifemanager.ui.settings.BackupSettingsViewModel? = null) {
    require(todoRequest == null || todoNavigation != null) { "待办通知必须由导航所有者交付" }
    require(scheduleRequest == null || scheduleNavigation != null) { "日程通知必须由导航所有者交付" }
    require(subscriptionRequest == null || subscriptionNavigation != null) { "订阅通知必须由导航所有者交付" }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val backupBusy = backupViewModel?.uiState?.collectAsStateWithLifecycle()?.value?.busy == true
    // Read the live owners at click delivery too, not only a potentially older rendered flag.
    fun navigationAllowed() = !settingsViewModel.uiState.value.isMaintaining &&
        backupViewModel?.uiState?.value?.busy != true
    val items = listOf(
        TodoRoute to ("待办" to Icons.Outlined.CheckCircle),
        ScheduleRoute to ("日程" to Icons.Outlined.CalendarMonth),
        HabitRoute to ("打卡" to Icons.Outlined.Repeat),
        SubscriptionRoute to ("订阅" to Icons.Outlined.Payments),
        SettingsRoute to ("设置" to Icons.Outlined.Settings),
    )

    LaunchedEffect(scheduleRequest?.token, backupBusy) {
        if (backupBusy) return@LaunchedEffect
        scheduleRequest?.let { request ->
            requireNotNull(scheduleNavigation).deliverIfAllowed(request,
                awaitGraph = { navController.currentBackStackEntryFlow.first(); Unit },
                awaitUiReady = { backupViewModel?.uiState?.first { !it.busy }; Unit },
                canNavigate = { backupViewModel?.uiState?.value?.busy != true }) {
                navController.navigateTopLevel(ScheduleRoute)
            }
        }
    }
    LaunchedEffect(todoRequest?.token, backupBusy) {
        if (backupBusy) return@LaunchedEffect
        todoRequest?.let { request ->
            requireNotNull(todoNavigation).deliverIfAllowed(request,
                awaitGraph = { navController.currentBackStackEntryFlow.first(); Unit },
                awaitUiReady = { backupViewModel?.uiState?.first { !it.busy }; Unit },
                canNavigate = { backupViewModel?.uiState?.value?.busy != true }) {
                navController.navigateTopLevel(TodoRoute)
            }
        }
    }
    LaunchedEffect(subscriptionRequest?.token, backupBusy) {
        if (backupBusy) return@LaunchedEffect
        subscriptionRequest?.let { request ->
            requireNotNull(subscriptionNavigation).deliverIfAllowed(request,
                awaitGraph = { navController.currentBackStackEntryFlow.first(); Unit },
                awaitUiReady = { backupViewModel?.uiState?.first { !it.busy }; Unit },
                canNavigate = { backupViewModel?.uiState?.value?.busy != true }) {
                navController.navigateTopLevel(SubscriptionRoute)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            Surface(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                shadowElevation = 3.dp,
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    items.forEach { (route, item) ->
                        NavigationBarItem(
                            selected = currentRoute == route,
                            enabled = !settingsState.isMaintaining && !backupBusy,
                            onClick = { if (navigationAllowed()) navController.navigateTopLevel(route) },
                            icon = { Icon(item.second, contentDescription = item.first) },
                            label = { Text(item.first, fontWeight = if (currentRoute == route) FontWeight.Bold else FontWeight.Medium) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = TodoRoute,
            modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues),
        ) {
            composable(TodoRoute) {
                TodoScreen(
                    viewModel = todoViewModel,
                    initialTodoId = todoRequest?.todoId,
                    notificationToken = todoRequest?.token,
                    initialNotificationGeneration = todoRequest?.originalDataGeneration,
                    onNotificationConsumed = onTodoConsumed,
                    onOpenSettings = { if (navigationAllowed()) navController.navigateTopLevel(SettingsRoute) },
                    settingsNavigationEnabled = !settingsState.isMaintaining && !backupBusy,
                )
                MaintenanceBackGuard(settingsState.isMaintaining && !backupBusy)
            }
            composable(ScheduleRoute) {
                ScheduleScreen(
                    viewModel = scheduleViewModel,
                    initialScheduleId = scheduleRequest?.scheduleId,
                    notificationToken = scheduleRequest?.token,
                    initialNotificationGeneration = scheduleRequest?.originalDataGeneration,
                    onNotificationConsumed = onScheduleConsumed,
                )
                MaintenanceBackGuard(settingsState.isMaintaining && !backupBusy)
            }
            composable(HabitRoute) {
                HabitScreen()
                MaintenanceBackGuard(settingsState.isMaintaining && !backupBusy)
            }
            composable(SubscriptionRoute) {
                val viewModel: SubscriptionViewModel = hiltViewModel()
                LaunchedEffect(subscriptionRequest?.token) {
                    subscriptionRequest?.let { request ->
                        viewModel.openNotificationDetail(request.subscriptionId, request.originalDataGeneration)
                        onSubscriptionConsumed(request.token)
                    }
                }
                SubscriptionScreen(viewModel = viewModel)
                MaintenanceBackGuard(settingsState.isMaintaining && !backupBusy)
            }
            composable(SettingsRoute) {
                SettingsScreen(onBack = { if (navigationAllowed()) navController.navigateTopLevel(TodoRoute) }, viewModel = settingsViewModel,
                    backupViewModel = backupViewModel)
                MaintenanceBackGuard(settingsState.isMaintaining && !backupBusy)
            }
        }
    }
}

@Composable
private fun MaintenanceBackGuard(enabled: Boolean) {
    // Register in the destination lifecycle, after NavHost's subcomposition. A root handler
    // outside Scaffold registers too early. Settings still owns cancellation of busy backups.
    androidx.activity.compose.BackHandler(enabled = enabled) {}
}
