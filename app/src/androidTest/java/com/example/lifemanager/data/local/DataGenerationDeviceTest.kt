package com.example.lifemanager.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class DataGenerationDeviceTest {
    @Test fun backgroundFirstInitializationAndGenerationPersistAcrossReopen(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "maintenance-device-${System.nanoTime()}.db"
        try {
            val first = LifeManagerDatabaseFactory.open(context, name)
            try {
                val generations = DataGenerationRepositoryImpl(first)
                assertEquals(DataGeneration(0), generations.current())
                assertEquals("CNY", first.settingsDao().get()!!.defaultCurrency)
                generations.commit(DataGeneration(0)) { insertTodo(first, "synthetic original") }
            } finally { first.close() }
            val reopened = LifeManagerDatabaseFactory.open(context, name)
            try {
                assertEquals(DataGeneration(1), DataGenerationRepositoryImpl(reopened).current())
                assertEquals("synthetic original", reopened.todoDao().getAll().single().title)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun cancellationRollsBackAndCommittedReplacementRejectsOldSameIdDraft(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LifeManagerDatabase::class.java)
            .addCallback(LifeManagerDatabase.INITIALIZE).build()
        try {
            val generations = DataGenerationRepositoryImpl(database)
            val coordinator = MaintenanceCoordinator(generations)
            val original = coordinator.capture()
            coordinator.run(original) { insertTodo(database, "synthetic original") }
            coordinator.withSession { session ->
                assertFailsWith<MaintenanceBusyException> { coordinator.run(original) { error("must not run") } }
                assertFailsWith<CancellationException> {
                    coordinator.withMaintenance(session) {
                        generations.commit(session.generation) {
                            database.todoDao().deleteById(41)
                            throw CancellationException("cancel before commit")
                        }
                    }
                }
                assertEquals(original, generations.current())
                assertEquals("synthetic original", database.todoDao().getAll().single().title)
                coordinator.withMaintenance(session) {
                    generations.commit(session.generation) {
                        database.todoDao().deleteById(41)
                        insertTodo(database, "synthetic restored same ID")
                    }
                }
            }
            assertFailsWith<StaleGenerationException> { coordinator.run(original) { database.todoDao().deleteById(41) } }
            assertEquals("synthetic restored same ID", database.todoDao().getAll().single().title)
            assertEquals(DataGeneration(1), generations.current())
        } finally { database.close() }
    }

    private fun insertTodo(database: LifeManagerDatabase, title: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO todos VALUES (41, ?, NULL, 'NONE', NULL, 0, NULL, 1, 1, NULL, 0)", arrayOf(title),
        )
    }
}
