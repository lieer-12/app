package com.example.lifemanager.data.local

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.local.entity.HabitEntity
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.data.repository.HabitRepositoryImpl
import com.example.lifemanager.data.repository.SettingsRepositoryImpl
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.habit.HabitViewModel
import com.example.lifemanager.ui.settings.SettingsViewModel
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Android Room + real consumers, isolated synthetic data only; not a SAF restore test. */
@RunWith(AndroidJUnit4::class)
class MaintenanceConsumerDeviceTest {
    @Test fun habitDraftSurvivesCancelAndOldRenderedActionsCannotTouchRestoredSameId(): Unit = runBlocking {
        val database = database()
        val generations = DataGenerationRepositoryImpl(database)
        val coordinator = MaintenanceCoordinator(generations)
        val repository = HabitRepositoryImpl(database)
        val old = Habit(id = 42, name = "synthetic original", startDate = LocalDate.now())
        insertHabit(database, old)
        val model = HabitViewModel(repository, Dispatchers.IO, GenerationAccess(generations, coordinator))
        try {
            val loaded = withTimeout(10_000) { model.uiState.first { it.isAvailable } }
            withContext(Dispatchers.Main.immediate) { model.openEditor(loaded.habits.single(), loaded.generation); model.onNameChanged("synthetic draft") }
            coordinator.withSession {
                withContext(Dispatchers.Main.immediate) { model.saveHabit() }
            }
            assertEquals("synthetic original", repository.observeHabits().first().single().name)
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals("synthetic draft", model.uiState.value.editor.name)
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                generations.commit(session.generation) {
                    repository.deleteHabit(42)
                    insertHabit(database, old.copy(name = "synthetic restored"))
                }
            } }
            withTimeout(10_000) { model.uiState.first { it.generation == DataGeneration(1) && it.isAvailable && !it.isMaintaining } }
            withContext(Dispatchers.Main.immediate) {
                model.openEditor(old, loaded.generation)
                model.deleteHabit(42, loaded.generation)
                model.toggleToday(42, loaded.generation)
            }
            assertFalse(model.uiState.value.editor.isOpen)
            assertEquals("synthetic restored", repository.observeHabits().first().single().name)
            assertTrue(repository.observeRecords(LocalDate.now(), LocalDate.now()).first().isEmpty())
        } finally {
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            database.close()
        }
    }

    @Test fun settingsRestorePublishesFreshPreferencesAndRejectsOldRenderedTheme(): Unit = runBlocking {
        val database = database()
        val generations = DataGenerationRepositoryImpl(database)
        val coordinator = MaintenanceCoordinator(generations)
        val repository = SettingsRepositoryImpl(database)
        val model = SettingsViewModel(repository, Dispatchers.IO, GenerationAccess(generations, coordinator))
        try {
            val loaded = withTimeout(10_000) { model.uiState.first { it.isAvailable } }
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                generations.commit(session.generation) { repository.updateSettings { it.copy(theme = ThemeMode.LIGHT) } }
            } }
            withTimeout(10_000) { model.uiState.first { it.generation == DataGeneration(1) && it.isAvailable && !it.isMaintaining } }
            withContext(Dispatchers.Main.immediate) { model.setTheme(ThemeMode.DARK, loaded.generation) }
            assertEquals(ThemeMode.LIGHT, repository.getSettings().theme)
            assertEquals(ThemeMode.LIGHT, model.uiState.value.settings!!.theme)
            assertFalse(model.uiState.value.isSaving)
        } finally {
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            database.close()
        }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), LifeManagerDatabase::class.java,
    ).addCallback(LifeManagerDatabase.INITIALIZE).build()

    // Fixed IDs belong to synthetic fixture/replacement insertion, not the ordinary edit API.
    private suspend fun insertHabit(database: LifeManagerDatabase, habit: Habit) {
        database.habitDao().insertHabit(HabitEntity(
            habit.id, habit.name, habit.iconKey, habit.color, habit.frequencyType, habit.frequencyValue,
            null, habit.startDate.toEpochDay(), habit.note, habit.createdAt.toEpochMilli(), habit.updatedAt.toEpochMilli(),
        ))
    }
}
