package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import com.example.lifemanager.domain.repository.TodoRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant

class TodoReminderReceiver(
    private val repositoryProvider: (Context) -> TodoRepository = {
        EntryPointAccessors.fromApplication(it.applicationContext, TodoReminderEntryPoint::class.java).todoRepository()
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val todoId = intent.getLongExtra(EXTRA_TODO_ID, 0L)
        if (todoId <= 0L || !intent.hasExtra(EXTRA_DUE_AT) ||
            intent.dataString != "lifemanager://todo-reminder/$todoId") return
        val dueMillis = intent.getLongExtra(EXTRA_DUE_AT, Long.MIN_VALUE)
        val pendingResult = goAsync()
        CoroutineScope(dispatcher).launch {
            try {
                withTimeout(8_000) {
                    TodoOperationCoordinator.run {
                        val todo = repositoryProvider(context).getAllTodos().firstOrNull { it.id == todoId }
                            ?: return@run
                        val dueAt = todo.dueAt ?: return@run
                        val now = Instant.now()
                        if (todo.isCompleted || dueAt.toEpochMilli() != dueMillis || !dueAt.isAfter(now) ||
                            now.isBefore(dueAt.minusSeconds(15 * 60)) || todo.title.isBlank()) return@run
                        NotificationHelper.showTodoReminder(context, todo.id, todo.title)
                    }
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w("TodoReminder", "Todo reminder timed out", error)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                Log.w("TodoReminder", "Unable to validate or deliver todo reminder", error)
            } finally { pendingResult?.finish() }
        }
    }

    companion object {
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_TITLE = "todo_title"
        const val EXTRA_DUE_AT = "todo_due_at"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TodoReminderEntryPoint {
    fun todoRepository(): TodoRepository
}
