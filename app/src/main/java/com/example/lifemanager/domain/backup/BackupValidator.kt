package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.usecase.SettingsRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency

object BackupValidator {
    fun validate(document: BackupDocument, limits: BackupLimits = BackupLimits()) {
        checkBackup(document.appVersion.isNotBlank(), "应用版本缺失")
        checkBackupUnicode(document.appVersion)
        checked("导出时间无效") { Instant.parse(document.exportedAt) }
        validate(document.payload, limits)
    }

    fun validate(payload: BackupPayload, limits: BackupLimits = BackupLimits()) {
        checkBackup(payload.tables.keys == BackupSchema.tables.map { it.name }.toSet(), "备份业务表缺失或包含未知表")
        checkBackup(payload.tables.values.sumOf { it.size.toLong() } <= limits.maxRows, "备份超过 ${limits.maxRows} 行限制")
        checkBackup(SettingsRules.validate(payload.settings) == null, "备份设置无效")
        for (table in BackupSchema.tables) {
            val rows = payload.tables.getValue(table.name)
            val fields = table.columns.map { it.name }.toSet()
            for (row in rows) {
                checkBackup(row.fields.keys == fields, "${table.name} 字段缺失或包含未知字段")
                table.columns.forEach { validateValue(row.fields.getValue(it.name), it, "${table.name}.${it.name}") }
            }
            for (key in listOf(table.primaryKey) + table.uniqueKeys) {
                val found = HashSet<List<BackupValue>>()
                rows.forEach { checkBackup(found.add(key.map(it.fields::getValue)), "${table.name} 存在重复主键或唯一键") }
            }
        }
        val ids = BackupSchema.tables.associate { table -> table.name to payload.tables.getValue(table.name).mapNotNull { it.fields["id"] }.toHashSet() }
        for (table in BackupSchema.tables) for (reference in table.references) {
            for (row in payload.tables.getValue(table.name)) {
                val value = row.fields.getValue(reference.column)
                checkBackup(value == BackupValue.Null || value in ids.getValue(reference.table), "${table.name}.${reference.column} 引用不存在的记录")
            }
        }
        payload.tables.getValue("subscription_reminders").forEach {
            checkBackup((it.fields.getValue("daysBefore") as BackupValue.Integer).value in setOf(1L, 3L, 7L), "订阅提醒天数无效")
        }
        validateParents(payload.tables.getValue("todos"))
    }

    private fun validateValue(value: BackupValue, column: BackupColumn, location: String) {
        if (value == BackupValue.Null) {
            checkBackup(column.nullable, "$location 不允许为空")
            return
        }
        when (column.kind) {
            BackupKind.LONG, BackupKind.INT, BackupKind.DAY, BackupKind.MILLIS -> {
                checkBackup(value is BackupValue.Integer, "$location 必须是整数")
                val number = (value as BackupValue.Integer).value
                when (column.kind) {
                    BackupKind.INT -> checkBackup(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "$location 超出 Int 范围")
                    BackupKind.DAY -> checked("$location 日期无效") { LocalDate.ofEpochDay(number) }
                    BackupKind.MILLIS -> checked("$location 时间无效") { Instant.ofEpochMilli(number) }
                    else -> Unit
                }
            }
            BackupKind.FLAG -> checkBackup(value is BackupValue.Flag, "$location 必须是布尔值")
            else -> {
                checkBackup(value is BackupValue.Text, "$location 必须是字符串")
                val text = (value as BackupValue.Text).value
                checkBackupUnicode(text)
                checkBackup(column.choices == null || text in column.choices, "$location 枚举值无效")
                when (column.kind) {
                    BackupKind.CURRENCY -> checked("$location 币种无效") { checkBackup(Currency.getInstance(text).defaultFractionDigits >= 0, "$location 币种无效") }
                    BackupKind.ZONE -> checked("$location 时区无效") { ZoneId.of(text) }
                    BackupKind.DAY_LIST -> checkBackup(text.isEmpty() || text.split(',').all { token -> token.toIntOrNull() in 1..7 }, "$location 星期列表无效")
                    else -> Unit
                }
            }
        }
    }

    // Iterative linear walk: a 100,000-row parent chain must not overflow the stack.
    private fun validateParents(rows: List<BackupRow>) {
        val parents = rows.associate { row -> (row.fields.getValue("id") as BackupValue.Integer).value to (row.fields.getValue("parentId") as? BackupValue.Integer)?.value }
        val done = HashSet<Long>()
        for (id in parents.keys) {
            val path = HashSet<Long>()
            var current: Long? = id
            while (current != null && current !in done) {
                checkBackup(path.add(current), "待办父子关系存在循环")
                current = parents[current]
            }
            done.addAll(path)
        }
    }

    private inline fun checked(message: String, block: () -> Any?) {
        try { block() } catch (error: IllegalArgumentException) { throw BackupValidationException(message, error) }
        catch (error: java.time.DateTimeException) { throw BackupValidationException(message, error) }
    }
}

internal fun checkBackup(condition: Boolean, message: String) { if (!condition) throw BackupValidationException(message) }

internal fun checkBackupUnicode(text: String) {
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (char.isHighSurrogate()) {
            checkBackup(index < text.length && text[index].isLowSurrogate(), "字符串包含无效 Unicode")
            index++
        } else checkBackup(!char.isLowSurrogate(), "字符串包含无效 Unicode")
    }
}
