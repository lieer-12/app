package com.example.lifemanager.data.backup

import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.toDomain
import com.example.lifemanager.domain.backup.*
import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import javax.inject.Inject

class BackupRepositoryImpl(private val database: LifeManagerDatabase, private val limits: BackupLimits) : BackupRepository {
    @Inject constructor(database: LifeManagerDatabase) : this(database, BackupLimits())
    override suspend fun snapshot(): BackupPayload = database.withTransaction {
        val dao = database.backupSnapshotDao()
        // Count first inside the same transaction. Never fetch a giant database
        // only to reject it after building an unbounded in-memory snapshot.
        var count = 0L
        for (table in BackupSchema.tables) dao.read(SimpleSQLiteQuery("SELECT COUNT(*) FROM `${table.name}`")).use {
            checkBackup(it.moveToFirst(), "无法读取备份记录数量")
            count += it.getLong(0)
            checkBackup(count <= limits.maxRows, "备份超过 ${limits.maxRows} 行限制")
        }
        // Preflight stored UTF-8 byte lengths in SQLite before getString loads
        // potentially huge cells into the application heap. JSON escaping and
        // syntax overhead are counted separately by the codec before output.
        var textBytes = 0L
        val textColumns = BackupSchema.tables.associate { table -> table.name to table.columns.filter { it.kind in setOf(BackupKind.TEXT, BackupKind.CURRENCY, BackupKind.ZONE, BackupKind.DAY_LIST) }.map { it.name } } +
            ("app_settings" to listOf("theme", "dateFormat", "defaultCurrency"))
        for ((table, columns) in textColumns) if (columns.isNotEmpty()) {
            val expression = columns.joinToString("+") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
            dao.read(SimpleSQLiteQuery("SELECT COALESCE(SUM($expression),0) FROM `$table`")).use {
                checkBackup(it.moveToFirst(), "无法读取备份文本大小")
                textBytes += it.getLong(0)
                checkBackup(textBytes <= limits.maxBytes, "备份文本超过 ${limits.maxBytes} 字节限制")
            }
        }
        dao.read(SimpleSQLiteQuery("SELECT id, todoReminders, scheduleReminders, subscriptionReminders, defaultReminderMask FROM app_settings")).use { cursor ->
            checkBackup(cursor.count == 1 && cursor.moveToFirst() && cursor.getLong(0) == 1L, "设置单行记录无效")
            (1..3).forEach { index -> checkBackup(cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(index) in 0L..1L, "设置布尔值无效") }
            checkBackup(cursor.getType(4) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(4) in 0L..7L, "默认提醒组合位掩码无效")
        }
        val settings = database.settingsDao().get()?.let {
            try { it.toDomain() } catch (error: IllegalArgumentException) { throw BackupValidationException("已保存设置无效", error) }
        } ?: throw BackupValidationException("设置记录不可用")
        val tables = BackupSchema.tables.associate { table ->
            val columns = table.columns.joinToString(",") { "`${it.name}`" }
            val order = table.primaryKey.joinToString(",") { "`$it`" }
            table.name to dao.read(SimpleSQLiteQuery("SELECT $columns FROM `${table.name}` ORDER BY $order")).use { cursor -> buildList {
                while (cursor.moveToNext()) add(BackupRow(table.columns.mapIndexed { index, column -> column.name to readValue(cursor, index, column) }.toMap()))
            } }
        }
        BackupPayload(tables, settings).also { BackupValidator.validate(it, limits) }
    }

    private fun readValue(cursor: Cursor, index: Int, column: BackupColumn): BackupValue {
        if (cursor.isNull(index)) return BackupValue.Null
        return when (column.kind) {
            BackupKind.FLAG -> {
                checkBackup(cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(index) in 0L..1L, "${column.name} 数据库布尔值无效")
                BackupValue.Flag(cursor.getLong(index) == 1L)
            }
            BackupKind.LONG, BackupKind.INT, BackupKind.DAY, BackupKind.MILLIS -> {
                checkBackup(cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER, "${column.name} 数据库整数类型无效")
                BackupValue.Integer(cursor.getLong(index))
            }
            else -> {
                checkBackup(cursor.getType(index) == Cursor.FIELD_TYPE_STRING, "${column.name} 数据库字符串类型无效")
                BackupValue.Text(cursor.getString(index))
            }
        }
    }
}
