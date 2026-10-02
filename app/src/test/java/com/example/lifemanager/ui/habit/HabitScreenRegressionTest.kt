package com.example.lifemanager.ui.habit

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.CompositionLocalProvider
import com.example.lifemanager.ui.settings.LocalDateFormat
import com.example.lifemanager.domain.model.DateFormat
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitFrequencyType
import com.example.lifemanager.domain.model.HabitRecord
import com.example.lifemanager.domain.repository.HabitRepository
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HabitScreenRegressionTest {
    @get:Rule val compose = createComposeRule()
    private var model: HabitViewModel? = null

    @After fun cleanup() { model?.viewModelScope?.cancel() }

    @Test fun futureHabitHasDisabledCheckInButton() {
        show(Habit(id = 1, name = "阅读", startDate = LocalDate.now().plusDays(2)))
        compose.onNodeWithContentDescription("打卡：阅读").assertIsNotEnabled()
    }

    @Test fun unselectedWeekdayHasDisabledCheckInButton() {
        show(Habit(id = 1, name = "阅读", startDate = LocalDate.now(), frequencyType = HabitFrequencyType.CUSTOM,
            customDaysOfWeek = setOf(LocalDate.now().plusDays(1).dayOfWeek)))
        compose.onNodeWithContentDescription("打卡：阅读").assertIsNotEnabled()
    }

    @Test fun preStartLegacyRecordStillHasEnabledUndoButton() {
        val legacy = HabitRecord(habitId = 1, date = LocalDate.now(), createdAt = Instant.EPOCH)
        val repository = show(Habit(id = 1, name = "阅读", startDate = LocalDate.now().plusDays(2)), listOf(legacy))
        compose.onNodeWithContentDescription("撤销打卡：阅读").assertIsEnabled().performClick()
        compose.waitUntil(5000) { repository.records.value.isEmpty() }
        compose.onNodeWithContentDescription("打卡：阅读").assertIsNotEnabled()
    }

    @Test fun unselectedWeekdayLegacyRecordCanBeUndoneButNotRecreated() {
        val today = LocalDate.now()
        val legacy = HabitRecord(habitId = 1, date = today, createdAt = Instant.EPOCH)
        val repository = show(Habit(id = 1, name = "阅读", startDate = today,
            frequencyType = HabitFrequencyType.CUSTOM,
            customDaysOfWeek = setOf(today.plusDays(1).dayOfWeek)), listOf(legacy))
        compose.onNodeWithContentDescription("撤销打卡：阅读").assertIsEnabled().performClick()
        compose.waitUntil(5000) { repository.records.value.isEmpty() }
        compose.onNodeWithContentDescription("打卡：阅读").assertIsNotEnabled()
    }

    @Test fun habitStartDateButtonUsesSelectedFormat() {
        val habit = Habit(id = 1, name = "阅读", startDate = LocalDate.of(2026, 10, 2))
        val viewModel = HabitViewModel(ScreenRepository(habit, emptyList()), Dispatchers.IO)
        model = viewModel
        viewModel.openEditor(habit)
        compose.setContent { LifeManagerTheme { CompositionLocalProvider(LocalDateFormat provides DateFormat.DMY) {
            HabitScreen(viewModel)
        } } }
        compose.onNodeWithText("开始日期：02-10-2026").assertExists()
    }

    @Test fun calendarCellAccessibilityDateUsesTheSelectedFormat() {
        val today = LocalDate.now()
        val repository = ScreenRepository(Habit(id = 1, name = "阅读", startDate = today), emptyList())
        val viewModel = HabitViewModel(repository, Dispatchers.IO)
        model = viewModel
        compose.setContent { LifeManagerTheme { CompositionLocalProvider(LocalDateFormat provides DateFormat.DMY) {
            HabitScreen(viewModel)
        } } }
        compose.waitUntil(5000) { viewModel.uiState.value.cards.size == 1 }
        compose.onNodeWithText("统计").performClick()
        val expected = today.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-uuuu"))
        compose.onNodeWithContentDescription("$expected，应打卡，未完成").assertExists()
    }

    private fun show(habit: Habit, records: List<HabitRecord> = emptyList()): ScreenRepository {
        val repository = ScreenRepository(habit, records)
        val viewModel = HabitViewModel(repository, Dispatchers.IO)
        model = viewModel
        compose.setContent { LifeManagerTheme { HabitScreen(viewModel) } }
        compose.waitUntil(5000) { viewModel.uiState.value.cards.size == 1 }
        compose.waitForIdle()
        return repository
    }

    private class ScreenRepository(habit: Habit, initialRecords: List<HabitRecord>) : HabitRepository {
        private val habits = MutableStateFlow(listOf(habit))
        val records = MutableStateFlow(initialRecords)
        override fun observeHabits() = habits
        override fun observeRecords(start: LocalDate, end: LocalDate) = records.map { it.filter { record -> record.date in start..end } }
        override suspend fun saveHabit(habit: Habit) = error("Not used by these check-in tests")
        override suspend fun deleteHabit(id: Long) = error("Not used by these check-in tests")
        override suspend fun toggleRecord(habitId: Long, date: LocalDate): Boolean {
            val existing = records.value.firstOrNull { it.habitId == habitId && it.date == date }
            records.value = if (existing != null) records.value - existing
                else records.value + HabitRecord(habitId = habitId, date = date, createdAt = Instant.EPOCH)
            return existing == null
        }
    }
}
