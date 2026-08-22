package com.example.lifemanager.ui.schedule

import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.notification.ScheduleReminderSchedulerContract
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleViewModelTest {
    @Test
    fun `save rejects a blank title`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var viewModel: ScheduleViewModel? = null
        try {
            val model = ScheduleViewModel(FakeScheduleRepository(), NoOpScheduleReminderScheduler, dispatcher)
            viewModel = model
            advanceUntilIdle()
            model.openEditor()
            model.onTitleChanged("  ")
            model.saveSchedule()
            advanceUntilIdle()

            assertEquals("标题不能为空", model.uiState.value.editor.validationMessage)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `conflict requires confirmation before a schedule is persisted`() = runTest {
        val repository = FakeScheduleRepository().apply {
            schedules.value = listOf(schedule(id = 1, title = "已有会议"))
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var viewModel: ScheduleViewModel? = null
        try {
            val model = ScheduleViewModel(repository, NoOpScheduleReminderScheduler, dispatcher)
            viewModel = model
            advanceUntilIdle()
            model.openEditor()
            model.onTitleChanged("重叠会议")
            model.onStartChanged(Instant.parse("2026-08-22T01:30:00Z"))
            model.onEndChanged(Instant.parse("2026-08-22T02:30:00Z"))
            model.saveSchedule()
            advanceUntilIdle()

            assertTrue(model.uiState.value.editor.awaitingConflictConfirmation)
            assertEquals(1, repository.schedules.value.size)

            model.confirmSaveDespiteConflicts()
            advanceUntilIdle()
            assertEquals(2, repository.schedules.value.size)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    private class FakeScheduleRepository : ScheduleRepository {
        val schedules = MutableStateFlow<List<Schedule>>(emptyList())

        override fun observeSchedules(): Flow<List<Schedule>> = schedules
        override suspend fun getSchedules(): List<Schedule> = schedules.value
        override suspend fun getExceptions(scheduleId: Long) = emptyList<com.example.lifemanager.domain.model.ScheduleException>()
        override suspend fun saveSchedule(schedule: Schedule): Long {
            val saved = schedule.copy(id = if (schedule.id == 0L) schedules.value.size + 1L else schedule.id)
            schedules.value = schedules.value.filterNot { it.id == saved.id } + saved
            return saved.id
        }
        override suspend fun saveException(exception: com.example.lifemanager.domain.model.ScheduleException) = Unit
        override suspend fun deleteSchedule(id: Long) { schedules.value = schedules.value.filterNot { it.id == id } }
    }

    private object NoOpScheduleReminderScheduler : ScheduleReminderSchedulerContract {
        override fun schedule(schedule: Schedule) = Unit
        override fun cancel(scheduleId: Long) = Unit
    }

    private fun schedule(id: Long, title: String) = Schedule(
        id = id,
        title = title,
        startAt = Instant.parse("2026-08-22T01:00:00Z"),
        endAt = Instant.parse("2026-08-22T02:00:00Z"),
        timeZone = "Asia/Shanghai",
    )
}
