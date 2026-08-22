package com.example.lifemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import com.example.lifemanager.ui.navigation.NavGraph
import com.example.lifemanager.ui.theme.LifeManagerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTodoId = intent.getLongExtra(EXTRA_TODO_ID, 0L).takeIf { it != 0L }
        val initialScheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, 0L).takeIf { it != 0L }
        setContent {
            LifeManagerTheme {
                NavGraph(initialTodoId = initialTodoId, initialScheduleId = initialScheduleId)
            }
        }
    }

    companion object {
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }
}
