package com.example.lifemanager.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SettingsMigrationDeviceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        LifeManagerDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun migrationFrom1To5RetainsTodoAndTagData() = checkOlderChain(1)
    @Test fun migrationFrom2To5RetainsTodoAndTagData() = checkOlderChain(2)
    @Test fun migrationFrom3To5RetainsTodoAndTagData() = checkOlderChain(3)

    @Test fun migrationFrom4To5PreservesAllColumnsInAllTenBusinessTables() {
        val name = "settings-migration-v4-${System.nanoTime()}"
        val original = helper.createDatabase(name, 4)
        fixtures.forEach(original::execSQL)
        val before = tables.associateWith { readRows(original, it) }
        original.close()
        helper.runMigrationsAndValidate(name, 5, true, *LifeManagerDatabase.MIGRATIONS).use { upgraded ->
            assertDefaults(upgraded)
            tables.forEach { table -> assertEquals(before[table], readRows(upgraded, table), table) }
        }
    }

    private fun checkOlderChain(version: Int) {
        val name = "settings-migration-v$version-${System.nanoTime()}"
        // No exported v1 asset exists in this repository. The three v1 entities
        // are unchanged in git history and schema v2. Build that exact table set
        // from v2, then remove only v2's two schedule tables and set its old version.
        helper.createDatabase(name, if (version == 1) 2 else version).use { old ->
            if (version == 1) {
                old.execSQL("DROP TABLE schedule_exceptions")
                old.execSQL("DROP TABLE schedules")
                old.execSQL("PRAGMA user_version=1")
            }
            fixtures.take(3).forEach(old::execSQL)
        }
        helper.runMigrationsAndValidate(name, 5, true, *LifeManagerDatabase.MIGRATIONS).use { upgraded ->
            assertDefaults(upgraded)
            assertEquals(listOf(listOf("41", "旧待办", "备注", "HIGH", "1000", "0", null, "7", "8", null, "3")), readRows(upgraded, "todos"))
            assertEquals(listOf(listOf("41", "4")), readRows(upgraded, "todo_tag_cross_ref"))
        }
    }

    private fun readRows(database: SupportSQLiteDatabase, table: String): List<List<String?>> =
        database.query("SELECT * FROM $table").use { cursor -> buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
        } }

    private fun assertDefaults(database: SupportSQLiteDatabase) {
        database.query("SELECT theme, defaultCurrency FROM app_settings").use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst())
            assertEquals("SYSTEM", it.getString(0)); assertEquals("CNY", it.getString(1))
        }
        database.query("SELECT generation FROM app_maintenance").use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
        }
    }

    private val tables = listOf("todos", "tags", "todo_tag_cross_ref", "schedules", "schedule_exceptions", "habits", "habit_records", "subscriptions", "subscription_payments", "subscription_reminders")
    private val fixtures = listOf(
        "INSERT INTO todos VALUES (41, '旧待办', '备注', 'HIGH', 1000, 0, NULL, 7, 8, NULL, 3)",
        "INSERT INTO tags VALUES (4, '旧标签', 123, 7)",
        "INSERT INTO todo_tag_cross_ref VALUES (41, 4)",
        "INSERT INTO schedules VALUES (6, '旧日程', 1000, 2000, 0, NULL, NULL, '地点', '参与者', '备注', 123, 15, 'NONE', 1, NULL, NULL, 'UTC', 7, 8)",
        "INSERT INTO schedule_exceptions VALUES (6, 20000, 1)",
        "INSERT INTO habits VALUES (5, '旧习惯', 'book', 123, 'DAILY', 1, NULL, 20000, '备注', 7, 8)",
        "INSERT INTO habit_records VALUES (9, 5, 19999, 7)",
        "INSERT INTO subscriptions VALUES (8, '旧订阅', 1234567890123, 'CNY', 'MONTHLY', 20000, 19900, '工具', '备注', 1, NULL, 7, 8)",
        "INSERT INTO subscription_payments VALUES (3, 8, 9876543210, 'CNY', 19999, '历史扣费')",
        "INSERT INTO subscription_reminders VALUES (8, 3)",
    )
}
