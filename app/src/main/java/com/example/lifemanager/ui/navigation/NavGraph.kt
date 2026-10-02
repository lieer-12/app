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
import com.example.lifemanager.ui.habit.HabitScreen
import com.example.lifemanager.ui.todo.TodoScreen
import com.example.lifemanager.ui.schedule.ScheduleScreen
import com.example.lifemanager.ui.subscription.SubscriptionScreen
import com.example.lifemanager.ui.subscription.SubscriptionViewModel

private const val TodoRoute = "todo"
private const val SettingsRoute = "settings"
private const val ScheduleRoute = "schedule"
private const val HabitRoute = "habit"
private const val SubscriptionRoute = "subscription"

@Composable
fun NavGraph(modifier: Modifier = Modifier, initialTodoId: Long? = null, initialScheduleId: Long? = null,
    subscriptionRequest: SubscriptionNavigationRequest? = null, onSubscriptionConsumed: (Long) -> Unit = {}) {
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

    LaunchedEffect(initialTodoId, initialScheduleId) {
        val destination = when {
            initialScheduleId != null -> ScheduleRoute
            initialTodoId != null -> TodoRoute
            else -> null
        }
        destination?.let { navController.navigate(it) { launchSingleTop = true } }
    }
    LaunchedEffect(subscriptionRequest?.token) {
        if (subscriptionRequest != null) navController.navigate(SubscriptionRoute) { launchSingleTop = true }
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
            startDestination = if (initialScheduleId != null) ScheduleRoute else TodoRoute,
            modifier = Modifier.padding(paddingValues),
        ) {
            composable(TodoRoute) {
                TodoScreen(
                    initialTodoId = initialTodoId,
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable(ScheduleRoute) {
                ScheduleScreen(initialScheduleId = initialScheduleId)
            }
            composable(HabitRoute) {
                HabitScreen()
            }
            composable(SubscriptionRoute) {
                val viewModel: SubscriptionViewModel = hiltViewModel()
                LaunchedEffect(subscriptionRequest?.token) {
                    subscriptionRequest?.let { request ->
                        viewModel.openNotificationDetail(request.subscriptionId)
                        onSubscriptionConsumed(request.token)
                    }
                }
                SubscriptionScreen(viewModel = viewModel)
            }
            composable(SettingsRoute) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
