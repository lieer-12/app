package com.example.lifemanager.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class HabitMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LifeManagerDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationFrom2To3PreservesTodosAndCreatesHabitTables() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """
                INSERT INTO todos (
                    id, title, description, priority, dueAt, isCompleted, completedAt,
                    createdAt, updatedAt, parentId, sortOrder
                ) VALUES (7, '迁移前待办', NULL, 'MEDIUM', NULL, 0, NULL, 1, 1, NULL, 0)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, *LifeManagerDatabase.MIGRATIONS).use { database ->
            database.query("SELECT title FROM todos WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移前待办", cursor.getString(0))
            }
            database.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('habits', 'habit_records')").use { cursor ->
                assertEquals(2, cursor.count)
            }
        }
    }

    private companion object {
        const val TEST_DB = "habit-migration-test"
    }
}
