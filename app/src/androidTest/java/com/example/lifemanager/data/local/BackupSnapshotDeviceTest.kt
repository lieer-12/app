package com.example.lifemanager.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.backup.BackupJsonCodec
import com.example.lifemanager.data.backup.BackupRepositoryImpl
import com.example.lifemanager.data.repository.SettingsRepositoryImpl
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class BackupSnapshotDeviceTest {
    @Test fun tenTableSnapshotJsonRoundTripKeepsEveryStoredColumnAndDoesNotChangeGeneration(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LifeManagerDatabase::class.java)
            .addCallback(LifeManagerDatabase.INITIALIZE).build()
        try {
            val sqlite = database.openHelper.writableDatabase
            fixtures.forEach(sqlite::execSQL)
            sqlite.execSQL("UPDATE app_maintenance SET generation=73 WHERE id=1")
            val preferences = AppSettings(ThemeMode.DARK, DateFormat.DMY, "EUR", false, true, false, setOf(1, 3, 7))
            SettingsRepositoryImpl(database).updateSettings { preferences }
            val before = tables.associateWith { table -> sqlite.query("SELECT * FROM $table").use { cursor -> buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.associateWith { name -> val index = cursor.getColumnIndexOrThrow(name); if (cursor.isNull(index)) null else cursor.getString(index) })
            } } }
            val snapshot = BackupRepositoryImpl(database).snapshot()
            val codec = BackupJsonCodec()
            val result = codec.decode(codec.encode(BackupDocument("0.1.0", "2026-10-02T08:00:00Z", snapshot))).payload
            assertEquals(75, result.tables.values.sumOf { it.single().fields.size })
            assertEquals(preferences, result.settings)
            for (table in tables) assertEquals(before.getValue(table), result.tables.getValue(table).map { row -> row.fields.mapValues { (_, value) -> when (value) {
                BackupValue.Null -> null
                is BackupValue.Integer -> value.value.toString()
                is BackupValue.Text -> value.value
                is BackupValue.Flag -> if (value.value) "1" else "0"
            } } }, table)
            sqlite.query("SELECT generation FROM app_maintenance").use { assertTrue(it.moveToFirst()); assertEquals(73L, it.getLong(0)) }
        } finally { database.close() }
    }

    private val tables = listOf("todos", "tags", "todo_tag_cross_ref", "schedules", "schedule_exceptions", "habits", "habit_records", "subscriptions", "subscription_payments", "subscription_reminders")
    private val fixtures = listOf(
        "INSERT INTO todos VALUES (41, '  旧待办🏠  ', NULL, 'HIGH', -1000, 0, NULL, 7, 8, NULL, -3)",
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
