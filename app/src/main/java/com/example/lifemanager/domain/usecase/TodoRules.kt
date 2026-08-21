package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoStats
import java.time.LocalDate
import java.time.ZoneId

object TodoRules {
    fun validateTitle(title: String): String? =
        if (title.trim().isEmpty()) "标题不能为空" else null

    fun calculateStats(todos: List<Todo>, today: LocalDate): TodoStats {
        val todayTodos = todos.filter { todo ->
            val dueDate = todo.dueAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
            dueDate == null || dueDate == today
        }
        val completed = todayTodos.count { it.isCompleted }
        val pending = todayTodos.size - completed
        val rate = if (todayTodos.isEmpty()) 0.0 else completed.toDouble() / todayTodos.size
        return TodoStats(
            completedCount = completed,
            pendingCount = pending,
            completionRate = rate,
        )
    }
}
