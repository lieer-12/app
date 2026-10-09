package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.HabitFrequencyType
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority

enum class BackupKind { LONG, INT, TEXT, FLAG, DAY, MILLIS, CURRENCY, ZONE, DAY_LIST }
data class BackupColumn(val name: String, val kind: BackupKind, val nullable: Boolean = false, val choices: Set<String>? = null)
data class BackupReference(val column: String, val table: String, val target: String = "id")
data class BackupTable(val name: String, val columns: List<BackupColumn>, val primaryKey: List<String>, val uniqueKeys: List<List<String>> = emptyList(), val references: List<BackupReference> = emptyList())

/** Format v1 / schema v5 contract. A change requires an explicit format migration. */
object BackupSchema {
    private fun column(name: String, kind: BackupKind, nullable: Boolean = false) = BackupColumn(name, kind, nullable)
    private fun enum(name: String, choices: Set<String>) = BackupColumn(name, BackupKind.TEXT, choices = choices)
    private val id = column("id", BackupKind.LONG)
    private val created = column("createdAt", BackupKind.MILLIS)
    private val updated = column("updatedAt", BackupKind.MILLIS)
    val tables = listOf(
        BackupTable("todos", listOf(id, column("title", BackupKind.TEXT), column("description", BackupKind.TEXT, true), enum("priority", TodoPriority.entries.map { it.name }.toSet()), column("dueAt", BackupKind.MILLIS, true), column("isCompleted", BackupKind.FLAG), column("completedAt", BackupKind.MILLIS, true), created, updated, column("parentId", BackupKind.LONG, true), column("sortOrder", BackupKind.INT)), listOf("id"), references = listOf(BackupReference("parentId", "todos"))),
        BackupTable("tags", listOf(id, column("name", BackupKind.TEXT), column("color", BackupKind.INT), created), listOf("id"), listOf(listOf("name"))),
        BackupTable("todo_tag_cross_ref", listOf(column("todoId", BackupKind.LONG), column("tagId", BackupKind.LONG)), listOf("todoId", "tagId"), references = listOf(BackupReference("todoId", "todos"), BackupReference("tagId", "tags"))),
        BackupTable("schedules", listOf(id, column("title", BackupKind.TEXT), column("startAt", BackupKind.MILLIS, true), column("endAt", BackupKind.MILLIS, true), column("isAllDay", BackupKind.FLAG), column("allDayStartDate", BackupKind.DAY, true), column("allDayEndDate", BackupKind.DAY, true), column("location", BackupKind.TEXT, true), column("participants", BackupKind.TEXT, true), column("note", BackupKind.TEXT, true), column("color", BackupKind.INT), column("reminderMinutes", BackupKind.INT, true), enum("repeatRule", ScheduleRepeatRule.entries.map { it.name }.toSet()), column("repeatInterval", BackupKind.INT), column("repeatDaysOfWeek", BackupKind.DAY_LIST, true), column("repeatEndDate", BackupKind.DAY, true), column("timeZone", BackupKind.ZONE), created, updated), listOf("id")),
        BackupTable("schedule_exceptions", listOf(column("scheduleId", BackupKind.LONG), column("occurrenceDate", BackupKind.DAY), column("isCancelled", BackupKind.FLAG)), listOf("scheduleId", "occurrenceDate"), references = listOf(BackupReference("scheduleId", "schedules"))),
        BackupTable("habits", listOf(id, column("name", BackupKind.TEXT), column("iconKey", BackupKind.TEXT), column("color", BackupKind.INT), enum("frequencyType", HabitFrequencyType.entries.map { it.name }.toSet()), column("frequencyValue", BackupKind.INT), column("customDaysOfWeek", BackupKind.DAY_LIST, true), column("startDate", BackupKind.DAY), column("note", BackupKind.TEXT, true), created, updated), listOf("id")),
        BackupTable("habit_records", listOf(id, column("habitId", BackupKind.LONG), column("date", BackupKind.DAY), created), listOf("id"), listOf(listOf("habitId", "date")), listOf(BackupReference("habitId", "habits"))),
        BackupTable("subscriptions", listOf(id, column("appName", BackupKind.TEXT), column("amountMinor", BackupKind.LONG), column("currency", BackupKind.CURRENCY), enum("billingCycle", BillingCycle.entries.map { it.name }.toSet()), column("nextBillingDate", BackupKind.DAY), column("startDate", BackupKind.DAY), column("category", BackupKind.TEXT, true), column("note", BackupKind.TEXT, true), column("isActive", BackupKind.FLAG), column("cancelDate", BackupKind.DAY, true), created, updated), listOf("id")),
        BackupTable("subscription_payments", listOf(id, column("subscriptionId", BackupKind.LONG), column("amountMinor", BackupKind.LONG), column("currency", BackupKind.CURRENCY), column("paidAt", BackupKind.DAY), column("note", BackupKind.TEXT, true)), listOf("id"), references = listOf(BackupReference("subscriptionId", "subscriptions"))),
        BackupTable("subscription_reminders", listOf(column("subscriptionId", BackupKind.LONG), column("daysBefore", BackupKind.INT)), listOf("subscriptionId", "daysBefore"), references = listOf(BackupReference("subscriptionId", "subscriptions"))),
    )
}
