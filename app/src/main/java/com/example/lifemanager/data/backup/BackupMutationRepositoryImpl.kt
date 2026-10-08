package com.example.lifemanager.data.backup

import androidx.room.withTransaction
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.toEntity
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.ReminderIdentities
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class BackupMutationRepositoryImpl @Inject constructor(
    private val database: LifeManagerDatabase,
    private val generations: DataGenerationRepository,
) : BackupMutationRepository {
    override suspend fun reminderIdentities(): ReminderIdentities = database.withTransaction {
        fun ids(table: String): List<Long> = database.openHelper.readableDatabase.query("SELECT id FROM `$table`").use {
            buildList { while (it.moveToNext()) add(it.getLong(0)) }
        }
        ReminderIdentities(ids("todos"), ids("schedules"), ids("subscriptions"))
    }

    override suspend fun replace(expected: DataGeneration, payload: BackupPayload): DataGeneration {
        BackupValidator.validate(payload)
        return database.withTransaction {
            // An ordinary restore must not silently repair missing/extra current settings rows.
            database.openHelper.writableDatabase.query("SELECT id FROM app_settings").use {
                check(it.count == 1 && it.moveToFirst() && it.getLong(0) == 1L) { "当前设置记录不可用" }
            }
            generations.commit(expected) {
                deleteBusinessRows()
                val sqlite = database.openHelper.writableDatabase
                for (table in BackupSchema.tables) {
                    val names = table.columns.joinToString(",") { "`${it.name}`" }
                    val placeholders = table.columns.joinToString(",") { "?" }
                    sqlite.compileStatement("INSERT INTO `${table.name}` ($names) VALUES ($placeholders)").use { statement ->
                        for (row in payload.tables.getValue(table.name)) {
                            currentCoroutineContext().ensureActive()
                            statement.clearBindings()
                            table.columns.forEachIndexed { index, column ->
                                when (val value = row.fields.getValue(column.name)) {
                                    BackupValue.Null -> statement.bindNull(index + 1)
                                    is BackupValue.Integer -> statement.bindLong(index + 1, value.value)
                                    is BackupValue.Text -> statement.bindString(index + 1, value.value)
                                    is BackupValue.Flag -> statement.bindLong(index + 1, if (value.value) 1 else 0)
                                }
                            }
                            statement.executeInsert()
                        }
                    }
                }
                database.settingsDao().save(payload.settings.toEntity())
                currentCoroutineContext().ensureActive()
            }
        }
    }

    override suspend fun clearBusinessData(expected: DataGeneration): DataGeneration = database.withTransaction {
        generations.commit(expected) { deleteBusinessRows() }
    }

    private suspend fun deleteBusinessRows() {
        val sqlite = database.openHelper.writableDatabase
        // Fixed versioned schema only: identifiers never come from the imported file.
        for (table in BackupSchema.tables.asReversed()) {
            currentCoroutineContext().ensureActive()
            sqlite.execSQL("DELETE FROM `${table.name}`")
        }
        currentCoroutineContext().ensureActive()
    }
}
