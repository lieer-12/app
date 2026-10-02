package com.example.lifemanager.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.local.entity.HabitEntity
import com.example.lifemanager.data.local.entity.HabitRecordEntity
import com.example.lifemanager.domain.model.HabitFrequencyType
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class HabitDaoTest {
    private lateinit var database: LifeManagerDatabase

    @Before fun setUp() { database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LifeManagerDatabase::class.java).build() }
    @After fun tearDown() = database.close()

    @Test fun duplicateDayIsStoredOnceAndDeletingHabitCascades() = runBlocking {
        val day = LocalDate.of(2026, 8, 22)
        val habitId = database.habitDao().upsert(HabitEntity(name = "阅读", iconKey = "Check", color = 0xFF00695C.toInt(), frequencyType = HabitFrequencyType.DAILY, frequencyValue = 1, customDaysOfWeek = null, startDate = day.toEpochDay(), note = null, createdAt = 1L, updatedAt = 1L))
        database.habitDao().insertRecord(HabitRecordEntity(habitId = habitId, date = day.toEpochDay(), createdAt = 1L))
        database.habitDao().insertRecord(HabitRecordEntity(habitId = habitId, date = day.toEpochDay(), createdAt = 2L))
        assertEquals(1, database.habitDao().recordsBetween(day.toEpochDay(), day.toEpochDay()).first().size)
        database.habitDao().deleteHabit(habitId)
        assertEquals(0, database.habitDao().recordsBetween(day.toEpochDay(), day.toEpochDay()).first().size)
    }

    @Test fun editingHabitPreservesHistoricalRecord() = runBlocking {
        val day = LocalDate.of(2026, 9, 28)
        val dao = database.habitDao()
        val id = dao.upsert(HabitEntity(name = "阅读", iconKey = "Check", color = 0xFF00695C.toInt(),
            frequencyType = HabitFrequencyType.DAILY, frequencyValue = 1, customDaysOfWeek = null,
            startDate = day.toEpochDay(), note = null, createdAt = 1, updatedAt = 1))
        dao.insertRecord(HabitRecordEntity(habitId = id, date = day.toEpochDay(), createdAt = 1))
        val before = dao.recordsBetween(day.toEpochDay(), day.toEpochDay()).first()
        val habit = dao.observeAll().first().single()
        dao.upsert(habit.copy(name = "新的名称", updatedAt = 2))
        assertEquals(before, dao.recordsBetween(day.toEpochDay(), day.toEpochDay()).first())
        assertEquals("新的名称", dao.observeAll().first().single().name)
    }
}
