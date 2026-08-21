package com.example.lifemanager.notification

object ReminderKey {
    fun forTodo(todoId: Long): Int = (todoId xor (todoId ushr 32)).toInt()
}
