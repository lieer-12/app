package com.example.lifemanager.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.example.lifemanager.data.local.dao.TagDao
import com.example.lifemanager.data.local.dao.TodoDao
import com.example.lifemanager.data.local.dao.TodoTagDao
import com.example.lifemanager.data.local.dao.ScheduleDao
import com.example.lifemanager.data.local.entity.ScheduleEntity
import com.example.lifemanager.data.local.entity.ScheduleExceptionEntity
import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.data.local.entity.TodoTagCrossRef

@Database(
    entities = [TodoEntity::class, TagEntity::class, TodoTagCrossRef::class, ScheduleEntity::class, ScheduleExceptionEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LifeManagerDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun tagDao(): TagDao
    abstract fun todoTagDao(): TodoTagDao
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        val MIGRATIONS: Array<Migration> = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS schedules (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT NOT NULL,
                            startAt INTEGER,
                            endAt INTEGER,
                            isAllDay INTEGER NOT NULL,
                            allDayStartDate INTEGER,
                            allDayEndDate INTEGER,
                            location TEXT,
                            participants TEXT,
                            note TEXT,
                            color INTEGER NOT NULL,
                            reminderMinutes INTEGER,
                            repeatRule TEXT NOT NULL,
                            repeatInterval INTEGER NOT NULL,
                            repeatDaysOfWeek TEXT,
                            repeatEndDate INTEGER,
                            timeZone TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                    """.trimIndent())
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_schedules_startAt ON schedules (startAt)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_schedules_allDayStartDate ON schedules (allDayStartDate)")
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS schedule_exceptions (
                            scheduleId INTEGER NOT NULL,
                            occurrenceDate INTEGER NOT NULL,
                            isCancelled INTEGER NOT NULL,
                            PRIMARY KEY (scheduleId, occurrenceDate),
                            FOREIGN KEY (scheduleId) REFERENCES schedules(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_exceptions_scheduleId ON schedule_exceptions (scheduleId)")
                }
            },
        )
    }
}
