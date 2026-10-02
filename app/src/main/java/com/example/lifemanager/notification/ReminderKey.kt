package com.example.lifemanager.notification

import java.time.LocalDate

object ReminderKey {
    fun forTodo(todoId: Long): Int = (todoId xor (todoId ushr 32)).toInt()

    fun forSchedule(scheduleId: Long): Int = forTodo(scheduleId) xor Int.MIN_VALUE

    fun forSubscription(subscriptionId: Long, daysBefore: Int): Int =
        31 * forTodo(subscriptionId) + daysBefore

    // PendingIntent identity ignores extras. Preserve all bits of the ID and the occurrence in data.
    fun subscriptionData(subscriptionId: Long, daysBefore: Int, dueDate: LocalDate): String =
        "lifemanager://subscription-reminder/$subscriptionId/$daysBefore/$dueDate"
}
