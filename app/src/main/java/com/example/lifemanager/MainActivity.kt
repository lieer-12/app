package com.example.lifemanager

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import com.example.lifemanager.ui.settings.LocalDateFormat
import com.example.lifemanager.domain.model.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import com.example.lifemanager.ui.navigation.NavGraph
import com.example.lifemanager.ui.navigation.SubscriptionNavigationViewModel
import com.example.lifemanager.ui.navigation.TodoNavigationViewModel
import com.example.lifemanager.ui.navigation.ScheduleNavigationViewModel
import com.example.lifemanager.ui.schedule.ScheduleViewModel
import com.example.lifemanager.ui.todo.TodoViewModel
import com.example.lifemanager.ui.theme.LifeManagerTheme
import com.example.lifemanager.ui.settings.SettingsViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val subscriptionNavigation: SubscriptionNavigationViewModel by viewModels()
    private val todoNavigation: TodoNavigationViewModel by viewModels()
    private val scheduleNavigation: ScheduleNavigationViewModel by viewModels()
    // One module owner, retained across navigation entries and configuration changes.
    private val todoViewModel: TodoViewModel by viewModels()
    private val scheduleViewModel: ScheduleViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val backupViewModel: com.example.lifemanager.ui.settings.BackupSettingsViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveNotificationIntent(intent)
        setContent {
            val subscriptionRequest by subscriptionNavigation.pending.collectAsStateWithLifecycle()
            val todoRequest by todoNavigation.pending.collectAsStateWithLifecycle()
            val scheduleRequest by scheduleNavigation.pending.collectAsStateWithLifecycle()
            val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
            LifeManagerTheme(themeMode = settings.settings?.theme) {
                CompositionLocalProvider(LocalDateFormat provides (settings.settings?.dateFormat ?: DateFormat.YMD)) {
                    NavGraph(todoViewModel = todoViewModel, settingsViewModel = settingsViewModel, backupViewModel = backupViewModel,
                        todoNavigation = todoNavigation, scheduleNavigation = scheduleNavigation,
                        subscriptionNavigation = subscriptionNavigation,
                        todoRequest = todoRequest, onTodoConsumed = todoNavigation::consume,
                        scheduleViewModel = scheduleViewModel, scheduleRequest = scheduleRequest, onScheduleConsumed = scheduleNavigation::consume,
                        subscriptionRequest = subscriptionRequest, onSubscriptionConsumed = subscriptionNavigation::consume)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveNotificationIntent(intent)
    }

    private fun receiveNotificationIntent(intent: Intent) {
        val sourceGeneration = com.example.lifemanager.notification.ReminderGeneration.read(intent)
        todoNavigation.openFromNotification(intent.getLongExtra(EXTRA_TODO_ID, 0L), sourceGeneration)
        scheduleNavigation.openFromNotification(intent.getLongExtra(EXTRA_SCHEDULE_ID, 0L), sourceGeneration)
        subscriptionNavigation.openFromNotification(intent.getLongExtra(EXTRA_SUBSCRIPTION_ID, 0L), sourceGeneration)
        // The event is retained by the Activity ViewModel, never replayed from a stale Intent.
        intent.removeExtra(EXTRA_SUBSCRIPTION_ID)
        intent.removeExtra(EXTRA_TODO_ID)
        intent.removeExtra(EXTRA_SCHEDULE_ID)
        intent.removeExtra(com.example.lifemanager.notification.ReminderGeneration.EXTRA)
    }

    companion object {
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
    }
}
