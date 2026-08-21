package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TodoReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val todoId = intent.getLongExtra(EXTRA_TODO_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (todoId != 0L && title.isNotBlank()) {
            NotificationHelper.showTodoReminder(context, todoId, title)
        }
    }

    companion object {
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_TITLE = "todo_title"
    }
}
