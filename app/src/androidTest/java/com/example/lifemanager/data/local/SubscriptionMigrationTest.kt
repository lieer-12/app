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
class SubscriptionMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LifeManagerDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrationFrom3To4PreservesHabitsAndCreatesSubscriptionTables() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL("INSERT INTO habits (id,name,iconKey,color,frequencyType,frequencyValue,startDate,createdAt,updatedAt) VALUES (4,'读书','Book',0,'DAILY',1,0,1,1)")
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, *LifeManagerDatabase.MIGRATIONS).use { database ->
            database.query("SELECT name FROM habits WHERE id = 4").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("读书", cursor.getString(0))
            }
            database.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('subscriptions', 'subscription_payments', 'subscription_reminders')").use { cursor ->
                assertEquals(3, cursor.count)
            }
        }
    }

    private companion object { const val TEST_DB = "subscription-migration-test" }
}
