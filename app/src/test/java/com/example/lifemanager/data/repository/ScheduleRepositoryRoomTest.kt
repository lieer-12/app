package com.example.lifemanager.data.repository

import android.app.Application
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ScheduleRepositoryRoomTest {
    private lateinit var database: LifeManagerDatabase
    private lateinit var repository: ScheduleRepositoryImpl
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LifeManagerDatabase::class.java).build()
        repository = ScheduleRepositoryImpl(database)
    }
    @After fun cleanup() { database.close() }

    @Test fun editingPreservesCancelledOccurrenceExceptions(): Unit = runBlocking {
        val id = repository.saveSchedule(schedule())
        val exception = ScheduleException(id, LocalDate.of(2026, 10, 6), true)
        repository.saveException(exception)
        val original = repository.getSchedules().single()
        repository.saveSchedule(original.copy(title = "改名"))
        assertEquals(listOf(exception), repository.getExceptions(id))
        assertEquals("改名", repository.getSchedules().single().title)
    }
    @Test fun editingCannotOverwriteImmutableCreationTime(): Unit = runBlocking {
        repository.saveSchedule(schedule())
        val original = repository.getSchedules().single()
        repository.saveSchedule(original.copy(title = "改名", createdAt = Instant.EPOCH))
        assertEquals(original.createdAt, repository.getSchedules().single().createdAt)
    }
    @Test fun deletedScheduleCannotBeRecreatedByStaleEditor(): Unit = runBlocking {
        val id = repository.saveSchedule(schedule())
        val original = repository.getSchedules().single()
        repository.deleteSchedule(id)
        assertFailsWith<IllegalStateException> { repository.saveSchedule(original.copy(title = "旧表单")) }
        assertTrue(repository.getSchedules().isEmpty())
    }
    @Test fun intentionalDeleteStillCascadesExceptions(): Unit = runBlocking {
        val id = repository.saveSchedule(schedule())
        repository.saveException(ScheduleException(id, LocalDate.of(2026, 10, 6), true))
        repository.deleteSchedule(id)
        assertTrue(repository.getExceptions(id).isEmpty())
    }
    private fun schedule() = Schedule(title = "会议", startAt = Instant.parse("2026-10-05T01:00:00Z"),
        endAt = Instant.parse("2026-10-05T02:00:00Z"), timeZone = "Asia/Shanghai")
}
