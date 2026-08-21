package com.example.lifemanager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Settings
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
import com.example.lifemanager.ui.settings.SettingsScreen
import com.example.lifemanager.ui.todo.TodoScreen

private const val TodoRoute = "todo"
private const val SettingsRoute = "settings"

@Composable
fun NavGraph(modifier: Modifier = Modifier, initialTodoId: Long? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val items = listOf(
        TodoRoute to ("待办" to Icons.Outlined.CheckCircle),
        SettingsRoute to ("设置" to Icons.Outlined.Settings),
    )

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
                    initialTodoId = initialTodoId,
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable(SettingsRoute) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
