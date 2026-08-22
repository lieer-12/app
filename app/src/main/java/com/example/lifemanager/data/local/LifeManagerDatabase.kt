package com.example.lifemanager.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.example.lifemanager.data.local.dao.HabitDao
import com.example.lifemanager.data.local.dao.ScheduleDao
import com.example.lifemanager.data.local.dao.TagDao
import com.example.lifemanager.data.local.dao.TodoDao
import com.example.lifemanager.data.local.dao.TodoTagDao
import com.example.lifemanager.data.local.entity.HabitEntity
import com.example.lifemanager.data.local.entity.HabitRecordEntity
import com.example.lifemanager.data.local.entity.ScheduleEntity
import com.example.lifemanager.data.local.entity.ScheduleExceptionEntity
import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.data.local.entity.TodoTagCrossRef

@Database(
    entities = [
        TodoEntity::class,
        TagEntity::class,
        TodoTagCrossRef::class,
        ScheduleEntity::class,
        ScheduleExceptionEntity::class,
        HabitEntity::class,
        HabitRecordEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LifeManagerDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun tagDao(): TagDao
    abstract fun todoTagDao(): TodoTagDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun habitDao(): HabitDao

    companion object {
        val MIGRATIONS: Array<Migration> = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("""
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
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_schedules_startAt ON schedules (startAt)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_schedules_allDayStartDate ON schedules (allDayStartDate)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS schedule_exceptions (
                            scheduleId INTEGER NOT NULL,
                            occurrenceDate INTEGER NOT NULL,
                            isCancelled INTEGER NOT NULL,
                            PRIMARY KEY (scheduleId, occurrenceDate),
                            FOREIGN KEY (scheduleId) REFERENCES schedules(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_exceptions_scheduleId ON schedule_exceptions (scheduleId)")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS habits (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            iconKey TEXT NOT NULL,
                            color INTEGER NOT NULL,
                            frequencyType TEXT NOT NULL,
                            frequencyValue INTEGER NOT NULL,
                            customDaysOfWeek TEXT,
                            startDate INTEGER NOT NULL,
                            note TEXT,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS habit_records (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            habitId INTEGER NOT NULL,
                            date INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL,
                            FOREIGN KEY(habitId) REFERENCES habits(id) ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_habit_records_habitId ON habit_records (habitId)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_habit_records_habitId_date ON habit_records (habitId, date)")
                }
            },
        )
    }
}
