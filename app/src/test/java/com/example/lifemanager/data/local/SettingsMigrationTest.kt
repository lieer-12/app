package com.example.lifemanager.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SettingsMigrationTest {
    @Test fun newDatabaseContainsPersistedDefaultSettingsAndLocalGeneration() {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LifeManagerDatabase::class.java)
            .addCallback(LifeManagerDatabase.INITIALIZE).allowMainThreadQueries().build()
        try { assertSettings(database) } finally { database.close() }
    }

    @Test fun migrationFromV4PreservesEveryBusinessTableAndLongAmount(): Unit = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val name = "phase5-test-${System.nanoTime()}.db"
        val file = application.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val schema = JSONObject(File("schemas/com.example.lifemanager.data.local.LifeManagerDatabase/4.json").readText())
                    .getJSONObject("database").getJSONArray("entities")
                for (i in 0 until schema.length()) {
                    val entity = schema.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: JSONArray()
                    for (j in 0 until indices.length()) {
                        sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                fixtures.forEach(sqlite::execSQL)
                sqlite.version = 4
            }
            val database = Room.databaseBuilder(application, LifeManagerDatabase::class.java, name)
                .addMigrations(*LifeManagerDatabase.MIGRATIONS).allowMainThreadQueries().build()
            try {
                    assertSettings(database)
                    for (table in tables) database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                        assertTrue(it.moveToFirst())
                        assertEquals(1, it.getInt(0), "migration lost $table")
                    }
                    val todo = database.todoDao().getAll().single()
                    assertEquals(41L, todo.id)
                    assertEquals("旧待办", todo.title)
                    assertEquals(7L, todo.createdAt)
                    database.openHelper.readableDatabase.query("SELECT amountMinor FROM subscriptions WHERE id = 8").use {
                        assertTrue(it.moveToFirst())
                        assertEquals(1_234_567_890_123L, it.getLong(0))
                    }
            } finally { database.close() }
        } finally {
            application.deleteDatabase(name)
        }
    }

    private fun assertSettings(database: LifeManagerDatabase) {
        val sqlite = database.openHelper.readableDatabase
        assertEquals(5, sqlite.version, "settings require a non-destructive v5 schema")
        sqlite.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            assertTrue("app_settings" in names)
            assertTrue("app_maintenance" in names)
        }
        sqlite.query("SELECT theme, dateFormat, defaultCurrency, todoReminders, scheduleReminders, subscriptionReminders, defaultReminderMask FROM app_settings WHERE id=1").use {
            assertTrue(it.moveToFirst())
            assertEquals("SYSTEM", it.getString(0))
            assertEquals("YMD", it.getString(1))
            assertEquals("CNY", it.getString(2))
            assertEquals(listOf(1, 1, 1, 0), (3..6).map(it::getInt))
            assertTrue(!it.moveToNext())
        }
        sqlite.query("SELECT generation FROM app_maintenance WHERE id=1").use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
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
