package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.model.*

internal object BackupFixtures {
    val tableNames = listOf("todos", "tags", "todo_tag_cross_ref", "schedules", "schedule_exceptions", "habits", "habit_records", "subscriptions", "subscription_payments", "subscription_reminders")
    val emptyTables = tableNames.associateWith { emptyList<BackupRow>() }
    fun fullPayload() = BackupPayload(mapOf(
        "todos" to listOf(row("id" to 41L, "title" to "  旧待办\n\"🏠\"  ", "description" to null, "priority" to "HIGH", "dueAt" to -1000L, "isCompleted" to false, "completedAt" to null, "createdAt" to 7L, "updatedAt" to 8L, "parentId" to null, "sortOrder" to -3L)),
        "tags" to listOf(row("id" to 4L, "name" to "旧标签", "color" to -123L, "createdAt" to 7L)),
        "todo_tag_cross_ref" to listOf(row("todoId" to 41L, "tagId" to 4L)),
        "schedules" to listOf(row("id" to 6L, "title" to "旧日程", "startAt" to 1000L, "endAt" to 2000L, "isAllDay" to false, "allDayStartDate" to null, "allDayEndDate" to null, "location" to "", "participants" to "甲,乙", "note" to "备注", "color" to -123L, "reminderMinutes" to 15L, "repeatRule" to "NONE", "repeatInterval" to 1L, "repeatDaysOfWeek" to "7,1,1", "repeatEndDate" to -1L, "timeZone" to "UTC", "createdAt" to 7L, "updatedAt" to 8L)),
        "schedule_exceptions" to listOf(row("scheduleId" to 6L, "occurrenceDate" to 21000L, "isCancelled" to false)),
        "habits" to listOf(row("id" to 5L, "name" to "旧习惯", "iconKey" to "book", "color" to -123L, "frequencyType" to "CUSTOM", "frequencyValue" to 1L, "customDaysOfWeek" to "7,1,1", "startDate" to 20000L, "note" to null, "createdAt" to 7L, "updatedAt" to 8L)),
        "habit_records" to listOf(row("id" to 9L, "habitId" to 5L, "date" to 19999L, "createdAt" to 7L)),
        "subscriptions" to listOf(row("id" to 8L, "appName" to "旧订阅", "amountMinor" to Long.MAX_VALUE, "currency" to "CNY", "billingCycle" to "MONTHLY", "nextBillingDate" to 20000L, "startDate" to 19900L, "category" to null, "note" to "", "isActive" to false, "cancelDate" to 20001L, "createdAt" to 7L, "updatedAt" to 8L)),
        "subscription_payments" to listOf(row("id" to 3L, "subscriptionId" to 8L, "amountMinor" to 9007199254740993L, "currency" to "EUR", "paidAt" to 19999L, "note" to "历史扣费")),
        "subscription_reminders" to listOf(row("subscriptionId" to 8L, "daysBefore" to 3L)),
    ), AppSettings(theme = ThemeMode.DARK, dateFormat = DateFormat.DMY, defaultCurrency = "EUR", todoReminders = false, scheduleReminders = true, subscriptionReminders = false, defaultReminderDays = setOf(1, 7)))

    private fun row(vararg values: Pair<String, Any?>) = BackupRow(values.associate { (key, value) -> key to when (value) {
        null -> BackupValue.Null
        is Long -> BackupValue.Integer(value)
        is String -> BackupValue.Text(value)
        is Boolean -> BackupValue.Flag(value)
        else -> error("Bad test fixture")
    } })
}

internal fun BackupPayload.withTable(name: String, rows: List<BackupRow>) = copy(tables = tables + (name to rows))
internal fun BackupRow.with(name: String, value: BackupValue) = copy(fields = fields + (name to value))
