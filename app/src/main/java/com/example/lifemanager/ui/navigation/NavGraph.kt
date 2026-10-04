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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
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
    subscriptionNavigation: SubscriptionNavigationViewModel? = null) {
    require(todoRequest == null || todoNavigation != null) { "待办通知必须由导航所有者交付" }
    require(scheduleRequest == null || scheduleNavigation != null) { "日程通知必须由导航所有者交付" }
    require(subscriptionRequest == null || subscriptionNavigation != null) { "订阅通知必须由导航所有者交付" }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val items = listOf(
        TodoRoute to ("待办" to Icons.Outlined.CheckCircle),
        ScheduleRoute to ("日程" to Icons.Outlined.CalendarMonth),
        HabitRoute to ("打卡" to Icons.Outlined.Repeat),
        SubscriptionRoute to ("订阅" to Icons.Outlined.Payments),
        SettingsRoute to ("设置" to Icons.Outlined.Settings),
    )

    LaunchedEffect(scheduleRequest?.token) {
        scheduleRequest?.let { request ->
            requireNotNull(scheduleNavigation).deliver(request, { navController.currentBackStackEntryFlow.first(); Unit }) {
                navController.navigate(ScheduleRoute) { launchSingleTop = true }
            }
        }
    }
    LaunchedEffect(todoRequest?.token) {
        todoRequest?.let { request ->
            requireNotNull(todoNavigation).deliver(request, { navController.currentBackStackEntryFlow.first(); Unit }) {
                navController.navigate(TodoRoute) { launchSingleTop = true }
            }
        }
    }
    LaunchedEffect(subscriptionRequest?.token) {
        subscriptionRequest?.let { request ->
            requireNotNull(subscriptionNavigation).deliver(request, { navController.currentBackStackEntryFlow.first(); Unit }) {
                navController.navigate(SubscriptionRoute) { launchSingleTop = true }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                items.forEach { (route, item) ->
                    NavigationBarItem(
                        selected = currentRoute == route,
                        onClick = { navController.navigate(route) { launchSingleTop = true } },
                        icon = { Icon(item.second, contentDescription = item.first) },
                        label = { Text(item.first) },
                    )
                }
            }
        },
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = TodoRoute,
            modifier = Modifier.padding(paddingValues),
        ) {
            composable(TodoRoute) {
                TodoScreen(
                    viewModel = todoViewModel,
                    initialTodoId = todoRequest?.todoId,
                    notificationToken = todoRequest?.token,
                    initialNotificationGeneration = todoRequest?.originalDataGeneration,
                    onNotificationConsumed = onTodoConsumed,
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable(ScheduleRoute) {
                ScheduleScreen(
                    viewModel = scheduleViewModel,
                    initialScheduleId = scheduleRequest?.scheduleId,
                    notificationToken = scheduleRequest?.token,
                    initialNotificationGeneration = scheduleRequest?.originalDataGeneration,
                    onNotificationConsumed = onScheduleConsumed,
                )
            }
            composable(HabitRoute) {
                HabitScreen()
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
            }
            composable(SettingsRoute) {
                SettingsScreen(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
            }
        }
    }
}
