package com.example.lifemanager

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import com.example.lifemanager.ui.navigation.NavGraph
import com.example.lifemanager.ui.navigation.SubscriptionNavigationViewModel
import com.example.lifemanager.ui.navigation.TodoNavigationViewModel
import com.example.lifemanager.ui.todo.TodoViewModel
import com.example.lifemanager.ui.theme.LifeManagerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var launchIntent by mutableStateOf<Intent?>(null)
    private val subscriptionNavigation: SubscriptionNavigationViewModel by viewModels()
    private val todoNavigation: TodoNavigationViewModel by viewModels()
    // One module owner, retained across navigation entries and configuration changes.
    private val todoViewModel: TodoViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchIntent = intent
        if (savedInstanceState == null) receiveNotificationIntent(intent)
        setContent {
            val currentIntent = launchIntent
            val initialScheduleId = currentIntent?.getLongExtra(EXTRA_SCHEDULE_ID, 0L)?.takeIf { it > 0L }
            val subscriptionRequest by subscriptionNavigation.pending.collectAsStateWithLifecycle()
            val todoRequest by todoNavigation.pending.collectAsStateWithLifecycle()
            LifeManagerTheme {
                NavGraph(todoViewModel = todoViewModel, todoRequest = todoRequest, onTodoConsumed = todoNavigation::consume, initialScheduleId = initialScheduleId,
                    subscriptionRequest = subscriptionRequest, onSubscriptionConsumed = subscriptionNavigation::consume)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchIntent = intent
        receiveNotificationIntent(intent)
    }

    private fun receiveNotificationIntent(intent: Intent) {
        todoNavigation.open(intent.getLongExtra(EXTRA_TODO_ID, 0L))
        subscriptionNavigation.open(intent.getLongExtra(EXTRA_SUBSCRIPTION_ID, 0L))
        // The event is retained by the Activity ViewModel, never replayed from a stale Intent.
        intent.removeExtra(EXTRA_SUBSCRIPTION_ID)
        intent.removeExtra(EXTRA_TODO_ID)
    }

    companion object {
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
    }
}
