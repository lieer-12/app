package com.example.lifemanager.data.backup

import android.app.Application
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.SettingsRepositoryImpl
import com.example.lifemanager.domain.backup.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackupRepositoryRoomTest {
    @Test fun snapshotsAllTenTablesAll75ColumnsAndPreferencesWithoutChangingSource(): Unit = runBlocking {
        val database = newDatabase()
        try {
            fixtures.forEach(database.openHelper.writableDatabase::execSQL)
            val settings = BackupFixtures.fullPayload().settings
            SettingsRepositoryImpl(database).updateSettings { settings }
            database.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=99 WHERE id=1")
            val before = readRows(database)
            val repository = BackupRepositoryImpl(database)
            val snapshot = repository.snapshot()
            assertEquals(10, snapshot.tables.size)
            assertEquals(75, snapshot.tables.values.sumOf { it.single().fields.size })
            assertEquals(settings, snapshot.settings)
            val restored = BackupJsonCodec().decode(BackupJsonCodec().encode(BackupDocument("0.1.0", Instant.parse("2026-10-02T08:00:00Z").toString(), snapshot))).payload
            val expected = readRows(database)
            for (table in BackupFixtures.tableNames) {
                val actual = restored.tables.getValue(table).map { row -> row.fields.mapValues { (_, value) -> when (value) {
                    BackupValue.Null -> null
                    is BackupValue.Integer -> value.value.toString()
                    is BackupValue.Text -> value.value
                    is BackupValue.Flag -> if (value.value) "1" else "0"
                } } }
                assertEquals(expected.getValue(table), actual, table)
            }
            assertEquals(before, expected, "snapshot/export must not mutate business tables")
            database.openHelper.readableDatabase.query("SELECT generation FROM app_maintenance").use { assertTrue(it.moveToFirst()); assertEquals(99L, it.getLong(0)) }
        } finally { database.close() }
    }

    @Test fun emptyDatabaseSnapshotContainsPersistedDefaultsAndAllEmptyTables(): Unit = runBlocking {
        val database = newDatabase()
        try {
            val result = BackupRepositoryImpl(database).snapshot()
            assertEquals(BackupFixtures.emptyTables, result.tables)
            assertEquals("CNY", result.settings.defaultCurrency)
        } finally { database.close() }
    }

    @Test fun rejectsOrphanParentsAndNeverRepairsTheSource(): Unit = runBlocking {
        val database = newDatabase()
        try {
            database.openHelper.writableDatabase.execSQL(fixtures.first())
            database.openHelper.writableDatabase.execSQL("UPDATE todos SET parentId=999")
            val before = readRows(database)
            assertFailsWith<BackupValidationException> { BackupRepositoryImpl(database).snapshot() }
            assertEquals(before, readRows(database))
        } finally { database.close() }
    }

    @Test fun rejectsOversizedAggregateSnapshotInsteadOfDroppingRows(): Unit = runBlocking {
        val database = newDatabase()
        try {
            fixtures.forEach(database.openHelper.writableDatabase::execSQL)
            assertFailsWith<BackupValidationException> { BackupRepositoryImpl(database, BackupLimits(maxRows = 9)).snapshot() }
            assertEquals(10, readRows(database).values.sumOf { it.size })
        } finally { database.close() }
    }

    @Test fun rejectsExtraSettingsRowsInsteadOfSilentlyDroppingThem(): Unit = runBlocking {
        val database = newDatabase()
        try {
            database.openHelper.writableDatabase.execSQL("INSERT INTO app_settings SELECT 2, theme, dateFormat, defaultCurrency, todoReminders, scheduleReminders, subscriptionReminders, defaultReminderMask FROM app_settings WHERE id=1")
            assertFailsWith<BackupValidationException> { BackupRepositoryImpl(database).snapshot() }
        } finally { database.close() }
    }

    @Test fun rejectsRawReminderMaskBeforeRoomCanNarrowLongToInt(): Unit = runBlocking {
        val database = newDatabase()
        try {
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL("UPDATE app_settings SET defaultReminderMask=4294967297 WHERE id=1")
            assertFailsWith<BackupValidationException> { BackupRepositoryImpl(database).snapshot() }
            sqlite.query("SELECT defaultReminderMask FROM app_settings").use { assertTrue(it.moveToFirst()); assertEquals(4294967297L, it.getLong(0)) }
        } finally { database.close() }
    }

    @Test fun rejectsOversizedStoredUtf8TextDuringSnapshotEvenBelowRowLimit(): Unit = runBlocking {
        val database = newDatabase()
        try {
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(fixtures.first())
            sqlite.execSQL("UPDATE todos SET title=? WHERE id=41", arrayOf("🏠".repeat(200)))
            assertFailsWith<BackupValidationException> { BackupRepositoryImpl(database, BackupLimits(maxBytes = 512)).snapshot() }
            sqlite.query("SELECT length(CAST(title AS BLOB)) FROM todos").use { assertTrue(it.moveToFirst()); assertEquals(800, it.getInt(0)) }
        } finally { database.close() }
    }

    private fun newDatabase() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LifeManagerDatabase::class.java)
        .addCallback(LifeManagerDatabase.INITIALIZE).allowMainThreadQueries().build()

    private fun readRows(database: LifeManagerDatabase) = BackupFixtures.tableNames.associateWith { table ->
        database.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor -> buildList {
            while (cursor.moveToNext()) add(cursor.columnNames.associateWith { column -> val index = cursor.getColumnIndexOrThrow(column); if (cursor.isNull(index)) null else cursor.getString(index) })
        } }
    }

    // Independent SQL fixtures: none of the expected columns is derived from BackupSchema.
    private val fixtures = listOf(
        "INSERT INTO todos VALUES (41, '  旧待办  ', NULL, 'HIGH', -1000, 0, NULL, 7, 8, NULL, -3)",
        "INSERT INTO tags VALUES (4, '旧标签', -123, 7)",
        "INSERT INTO todo_tag_cross_ref VALUES (41, 4)",
        "INSERT INTO schedules VALUES (6, '旧日程', 1000, 2000, 0, NULL, NULL, '', '甲,乙', '备注', -123, 15, 'NONE', 1, '7,1,1', -1, 'UTC', 7, 8)",
        "INSERT INTO schedule_exceptions VALUES (6, 21000, 0)",
        "INSERT INTO habits VALUES (5, '旧习惯', 'book', -123, 'CUSTOM', 1, '7,1,1', 20000, NULL, 7, 8)",
        "INSERT INTO habit_records VALUES (9, 5, 19999, 7)",
        "INSERT INTO subscriptions VALUES (8, '旧订阅', 9223372036854775807, 'CNY', 'MONTHLY', 20000, 19900, NULL, '', 0, 20001, 7, 8)",
        "INSERT INTO subscription_payments VALUES (3, 8, 9007199254740993, 'EUR', 19999, '历史扣费')",
        "INSERT INTO subscription_reminders VALUES (8, 3)",
    )
}
