package com.example.lifemanager.data.repository

import android.app.Application
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.entity.HabitRecordEntity
import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitFrequencyType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HabitRepositoryRoomTest {
    private lateinit var database: LifeManagerDatabase
    private lateinit var repository: HabitRepositoryImpl
    private val monday = LocalDate.of(2026, 9, 28)

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LifeManagerDatabase::class.java).build()
        repository = HabitRepositoryImpl(database)
    }

    @After fun tearDown() = database.close()

    @Test fun editingHabitPreservesAllRecordIdsDatesAndCreationTimes(): Unit = runBlocking {
        val id = repository.saveHabit(habit())
        repository.toggleRecord(id, monday)
        repository.toggleRecord(id, monday.plusDays(1))
        val before = repository.observeRecords(monday, monday.plusDays(1)).first()
        val original = repository.observeHabits().first().single()

        assertEquals(id, repository.saveHabit(original.copy(name = "改名", note = "新备注")))

        assertEquals(before, repository.observeRecords(monday, monday.plusDays(1)).first())
        val edited = repository.observeHabits().first().single()
        assertEquals("改名", edited.name)
        assertEquals("新备注", edited.note)
        assertEquals(original.createdAt, edited.createdAt)
        assertEquals(id, edited.id)
    }

    @Test fun changingFrequencyPreservesHistoricalRecords(): Unit = runBlocking {
        val id = repository.saveHabit(habit())
        repository.toggleRecord(id, monday.plusDays(1))
        val before = repository.observeRecords(monday, monday.plusDays(7)).first()
        val saved = repository.observeHabits().first().single()
        repository.saveHabit(saved.copy(frequencyType = HabitFrequencyType.CUSTOM, customDaysOfWeek = setOf(DayOfWeek.MONDAY)))
        assertEquals(before, repository.observeRecords(monday, monday.plusDays(7)).first())
    }

    @Test fun cannotCreateRecordBeforeStartDate(): Unit = runBlocking {
        val id = repository.saveHabit(habit())
        assertFailsWith<IllegalArgumentException> { repository.toggleRecord(id, monday.minusDays(1)) }
        assertTrue(repository.observeRecords(monday.minusDays(1), monday).first().isEmpty())
    }

    @Test fun cannotCreateRecordOnUnselectedWeekday(): Unit = runBlocking {
        val id = repository.saveHabit(habit().copy(frequencyType = HabitFrequencyType.CUSTOM, customDaysOfWeek = setOf(DayOfWeek.MONDAY)))
        assertFailsWith<IllegalArgumentException> { repository.toggleRecord(id, monday.plusDays(1)) }
        assertTrue(repository.observeRecords(monday, monday.plusDays(1)).first().isEmpty())
    }

    @Test fun canUndoPreStartLegacyRecordButCannotRecreateIt(): Unit = runBlocking {
        val id = repository.saveHabit(habit())
        val earlier = monday.minusDays(1)
        database.habitDao().insertRecord(HabitRecordEntity(habitId = id, date = earlier.toEpochDay(), createdAt = 1))
        assertFalse(repository.toggleRecord(id, earlier))
        assertTrue(repository.observeRecords(earlier, monday).first().isEmpty())
        assertFailsWith<IllegalArgumentException> { repository.toggleRecord(id, earlier) }
    }

    @Test fun canUndoUnselectedLegacyRecordButCannotRecreateIt(): Unit = runBlocking {
        val id = repository.saveHabit(habit().copy(frequencyType = HabitFrequencyType.CUSTOM, customDaysOfWeek = setOf(DayOfWeek.MONDAY)))
        val tuesday = monday.plusDays(1)
        database.habitDao().insertRecord(HabitRecordEntity(habitId = id, date = tuesday.toEpochDay(), createdAt = 1))
        assertFalse(repository.toggleRecord(id, tuesday))
        assertTrue(repository.observeRecords(monday, tuesday).first().isEmpty())
        assertFailsWith<IllegalArgumentException> { repository.toggleRecord(id, tuesday) }
    }

    @Test fun selectedWeekdayCanBeRecordedAndUndone(): Unit = runBlocking {
        val id = repository.saveHabit(habit().copy(frequencyType = HabitFrequencyType.CUSTOM, customDaysOfWeek = setOf(DayOfWeek.MONDAY)))
        assertTrue(repository.toggleRecord(id, monday))
        assertEquals(1, repository.observeRecords(monday, monday).first().size)
        assertFalse(repository.toggleRecord(id, monday))
        assertTrue(repository.observeRecords(monday, monday).first().isEmpty())
    }

    @Test fun deletingHabitStillCascadesToItsRecords(): Unit = runBlocking {
        val id = repository.saveHabit(habit())
        repository.toggleRecord(id, monday)
        repository.deleteHabit(id)
        assertTrue(repository.observeRecords(monday, monday).first().isEmpty())
    }

    @Test fun staleEditorCannotRecreateDeletedHabit(): Unit = runBlocking {
        val id = repository.saveHabit(habit())
        val saved = repository.observeHabits().first().single()
        repository.deleteHabit(id)
        assertFailsWith<IllegalStateException> { repository.saveHabit(saved.copy(name = "旧表单")) }
        assertTrue(repository.observeHabits().first().isEmpty())
    }

    @Test fun unknownHabitIsRejectedWithoutWritingRecords(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { repository.toggleRecord(123, monday) }
        assertTrue(repository.observeRecords(monday, monday).first().isEmpty())
    }

    @Test fun eligibilityUsesLatestPersistedFrequency(): Unit = runBlocking {
        val id = repository.saveHabit(habit())
        val saved = repository.observeHabits().first().single()
        repository.saveHabit(saved.copy(frequencyType = HabitFrequencyType.CUSTOM, customDaysOfWeek = setOf(DayOfWeek.TUESDAY)))
        assertFailsWith<IllegalArgumentException> { repository.toggleRecord(id, monday) }
        assertTrue(repository.observeRecords(monday, monday).first().isEmpty())
    }

    private fun habit() = Habit(name = "阅读", startDate = monday, createdAt = Instant.EPOCH)
}
