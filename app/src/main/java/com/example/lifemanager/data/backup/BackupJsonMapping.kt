package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.model.*
import kotlinx.serialization.json.*

/** Wire DTO tree is separate from Room entities and editable UI models. */
internal object BackupJsonMapping {
    private val settingKeys = setOf("theme", "dateFormat", "defaultCurrency", "todoReminders", "scheduleReminders", "subscriptionReminders", "defaultReminderDays")

    fun toJson(payload: BackupPayload): JsonObject = buildJsonObject {
        for (table in BackupSchema.tables) put(table.name, JsonArray(payload.tables.getValue(table.name).map { row ->
            JsonObject(row.fields.mapValues { (_, value) -> when (value) {
                is BackupValue.Integer -> JsonPrimitive(value.value)
                is BackupValue.Text -> JsonPrimitive(value.value)
                is BackupValue.Flag -> JsonPrimitive(value.value)
                BackupValue.Null -> JsonNull
            } })
        }))
        put("settings", buildJsonObject {
            put("theme", payload.settings.theme.name)
            put("dateFormat", payload.settings.dateFormat.name)
            put("defaultCurrency", payload.settings.defaultCurrency)
            put("todoReminders", payload.settings.todoReminders)
            put("scheduleReminders", payload.settings.scheduleReminders)
            put("subscriptionReminders", payload.settings.subscriptionReminders)
            put("defaultReminderDays", JsonArray(payload.settings.defaultReminderDays.sorted().map(::JsonPrimitive)))
        })
    }

    fun fromJson(value: JsonElement): BackupPayload {
        val payload = value.objectWithKeys(BackupSchema.tables.map { it.name }.toSet() + "settings")
        val tables = BackupSchema.tables.associate { table -> table.name to payload.getValue(table.name).arrayValue().map { entry ->
            val row = entry.objectWithKeys(table.columns.map { it.name }.toSet())
            BackupRow(table.columns.associate { column -> column.name to row.getValue(column.name).let { item ->
                if (item == JsonNull) BackupValue.Null else when (column.kind) {
                    BackupKind.FLAG -> BackupValue.Flag(item.flagValue())
                    BackupKind.LONG, BackupKind.INT, BackupKind.DAY, BackupKind.MILLIS -> BackupValue.Integer(item.integerValue())
                    else -> BackupValue.Text(item.textValue())
                }
            } })
        } }
        val settings = payload.getValue("settings").objectWithKeys(settingKeys)
        val days = settings.getValue("defaultReminderDays").arrayValue().map { item ->
            val number = item.integerValue()
            checkBackup(number in setOf(1L, 3L, 7L), "默认提醒天数无效")
            number.toInt()
        }
        checkBackup(days.size == days.toSet().size, "默认提醒天数重复")
        val theme = ThemeMode.entries.singleOrNull { it.name == settings.getValue("theme").textValue() }
            ?: throw BackupValidationException("主题枚举无效")
        val dateFormat = DateFormat.entries.singleOrNull { it.name == settings.getValue("dateFormat").textValue() }
            ?: throw BackupValidationException("日期格式枚举无效")
        return BackupPayload(tables, AppSettings(theme, dateFormat, settings.getValue("defaultCurrency").textValue(),
            settings.getValue("todoReminders").flagValue(), settings.getValue("scheduleReminders").flagValue(),
            settings.getValue("subscriptionReminders").flagValue(), days.toSet()))
    }
}

internal fun JsonElement.objectWithKeys(keys: Set<String>): JsonObject {
    checkBackup(this is JsonObject, "备份字段必须是对象")
    return (this as JsonObject).also { checkBackup(it.keys == keys, "备份字段缺失或包含未知字段") }
}
internal fun JsonElement.arrayValue(): JsonArray {
    checkBackup(this is JsonArray, "备份字段必须是数组")
    return this as JsonArray
}
internal fun JsonElement.textValue(): String {
    checkBackup(this is JsonPrimitive && this != JsonNull && isString, "备份字段必须是字符串")
    return (this as JsonPrimitive).content
}
internal fun JsonElement.integerValue(): Long {
    checkBackup(this is JsonPrimitive && this != JsonNull && !isString && longOrNull != null, "备份字段必须是 64 位整数")
    return (this as JsonPrimitive).long
}
internal fun JsonElement.flagValue(): Boolean {
    checkBackup(this is JsonPrimitive && this != JsonNull && !isString && booleanOrNull != null, "备份字段必须是布尔值")
    return (this as JsonPrimitive).boolean
}
