package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.lifemanager.domain.repository.TodoRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: TodoRepository
    @Inject lateinit var reminderScheduler: ReminderSchedulerContract

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.getAllTodos()
                    .filter { !it.isCompleted && it.dueAt != null }
                    .forEach { todo -> reminderScheduler.schedule(todo.id, todo.title, todo.dueAt ?: Instant.now()) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
