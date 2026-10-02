package com.example.lifemanager.ui.habit

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitFrequencyType
import com.example.lifemanager.domain.model.HabitRecord
import com.example.lifemanager.domain.repository.HabitRepository
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HabitViewModelTest {
    @Test
    fun `save rejects blank habit name`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var viewModel: HabitViewModel? = null
        try {
            val model = HabitViewModel(FakeHabitRepository(), dispatcher)
            viewModel = model
            advanceUntilIdle()

            model.openEditor()
            model.onNameChanged("  ")
            model.saveHabit()
            advanceUntilIdle()

            assertEquals("习惯名称不能为空", model.uiState.value.editor.validationMessage)
        } finally {
            viewModel?.viewModelScope?.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `toggle updates completed state from repository flow`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var viewModel: HabitViewModel? = null
        try {
            val model = HabitViewModel(
                FakeHabitRepository(habits = listOf(habit(id = 1))),
                dispatcher,
            )
            viewModel = model
            advanceUntilIdle()

            model.toggleToday(1)
            advanceUntilIdle()

            assertTrue(model.uiState.value.cards.single().completedToday)
        } finally {
            viewModel?.viewModelScope?.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `today toggle rejects habit whose start date is in the future`() = runTest {
        withModel(FakeHabitRepository(listOf(habit(1).copy(startDate = LocalDate.now().plusDays(2))))) { model ->
            model.toggleToday(1)
            advanceUntilIdle()
            assertFalse(model.uiState.value.cards.single().completedToday)
            assertNotNull(model.uiState.value.errorMessage)
        }
    }

    @Test
    fun `today toggle rejects an unselected weekday`() = runTest {
        val otherDay = LocalDate.now().plusDays(1).dayOfWeek
        val custom = habit(1).copy(frequencyType = HabitFrequencyType.CUSTOM, customDaysOfWeek = setOf(otherDay))
        withModel(FakeHabitRepository(listOf(custom))) { model ->
            model.toggleToday(1)
            advanceUntilIdle()
            assertFalse(model.uiState.value.cards.single().completedToday)
            assertNotNull(model.uiState.value.errorMessage)
        }
    }

    @Test
    fun `existing pre start record remains visible and can be undone`() = runTest {
        val futureHabit = habit(1).copy(startDate = LocalDate.now().plusDays(2))
        val legacy = HabitRecord(habitId = 1, date = LocalDate.now(), createdAt = Instant.EPOCH)
        withModel(FakeHabitRepository(listOf(futureHabit), listOf(legacy))) { model ->
            assertTrue(model.uiState.value.cards.single().completedToday)
            model.toggleToday(1)
            advanceUntilIdle()
            assertFalse(model.uiState.value.cards.single().completedToday)
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.withModel(
        repository: HabitRepository,
        body: suspend (HabitViewModel) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val model = HabitViewModel(repository, dispatcher)
        try {
            advanceUntilIdle()
            body(model)
        } finally {
            model.viewModelScope.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private class FakeHabitRepository(habits: List<Habit> = emptyList(), records: List<HabitRecord> = emptyList()) : HabitRepository {
        private val habitState = MutableStateFlow(habits)
        private val recordState = MutableStateFlow(records)

        override fun observeHabits(): Flow<List<Habit>> = habitState

        override fun observeRecords(start: LocalDate, end: LocalDate): Flow<List<HabitRecord>> =
            recordState.map { records -> records.filter { it.date in start..end } }

        override suspend fun saveHabit(habit: Habit): Long {
            val saved = habit.copy(id = if (habit.id == 0L) habitState.value.size + 1L else habit.id)
            habitState.value = habitState.value.filterNot { it.id == saved.id } + saved
            return saved.id
        }

        override suspend fun deleteHabit(id: Long) {
            habitState.value = habitState.value.filterNot { it.id == id }
            recordState.value = recordState.value.filterNot { it.habitId == id }
        }

        override suspend fun toggleRecord(habitId: Long, date: LocalDate): Boolean {
            val current = recordState.value
            val existing = current.firstOrNull { it.habitId == habitId && it.date == date }
            recordState.value = if (existing == null) {
                current + HabitRecord(habitId = habitId, date = date, createdAt = Instant.EPOCH)
            } else {
                current - existing
            }
            return existing == null
        }
    }

    private fun habit(id: Long) = Habit(
        id = id,
        name = "阅读",
        startDate = LocalDate.now().minusDays(7),
    )
}
