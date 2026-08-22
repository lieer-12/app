package com.example.lifemanager.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.local.entity.ScheduleEntity
import com.example.lifemanager.data.local.entity.ScheduleExceptionEntity
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ScheduleDaoTest {
    private lateinit var database: LifeManagerDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LifeManagerDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun scheduleCrudAndExceptionPersist() = runBlocking {
        val date = LocalDate.of(2026, 8, 22)
        val id = database.scheduleDao().upsert(
            ScheduleEntity(
                title = "晨会",
                startAt = 1_770_000_000_000L,
                endAt = 1_770_003_600_000L,
                isAllDay = false,
                allDayStartDate = null,
                allDayEndDate = null,
                location = "会议室",
                participants = null,
                note = null,
                color = 0xFF00695C.toInt(),
                reminderMinutes = 15,
                repeatRule = ScheduleRepeatRule.WEEKLY,
                repeatInterval = 1,
                repeatDaysOfWeek = "1",
                repeatEndDate = null,
                timeZone = "Asia/Shanghai",
                createdAt = 1_770_000_000_000L,
                updatedAt = 1_770_000_000_000L,
            ),
        )
        database.scheduleDao().upsertException(
            ScheduleExceptionEntity(id, date.toEpochDay(), isCancelled = true),
        )

        assertEquals("晨会", database.scheduleDao().observeAll().first().single().title)
        assertEquals(true, database.scheduleDao().getExceptions(id).single().isCancelled)

        database.scheduleDao().deleteById(id)
        assertEquals(0, database.scheduleDao().getAll().size)
    }
}
