package com.example.lifemanager.data.backup

import android.app.Application
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.AppSettings
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackupMutationRepositoryRoomTest {
    @Test fun replacesEveryStoredFieldAndSettingsAndAdvancesOnlyLocalGeneration(): Unit = withDatabase { database ->
        val generations = DataGenerationRepositoryImpl(database)
        val writes = BackupMutationRepositoryImpl(database, generations)
        val full = BackupFixtures.fullPayload()
        database.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=41 WHERE id=1")
        assertEquals(DataGeneration(42), writes.replace(DataGeneration(41), full))
        assertEquals(full, BackupRepositoryImpl(database).snapshot())
        assertEquals(75, full.tables.values.sumOf { it.single().fields.size })
        val changed = full.withTable("todos", listOf(full.tables.getValue("todos").single().with("title", BackupValue.Text("另一份同 ID 数据"))))
        assertEquals(DataGeneration(43), writes.replace(DataGeneration(42), changed))
        assertEquals(changed, BackupRepositoryImpl(database).snapshot())
        assertFailsWith<StaleGenerationException> { writes.replace(DataGeneration(41), full) }
        assertEquals(changed, BackupRepositoryImpl(database).snapshot())
    }

    @Test fun clearDeletesAllBusinessRowsButPreservesSettings(): Unit = withDatabase { database ->
        val generations = DataGenerationRepositoryImpl(database)
        val writes = BackupMutationRepositoryImpl(database, generations)
        writes.replace(DataGeneration(0), BackupFixtures.fullPayload())
        assertEquals(DataGeneration(2), writes.clearBusinessData(DataGeneration(1)))
        assertEquals(BackupPayload(BackupFixtures.emptyTables, BackupFixtures.fullPayload().settings), BackupRepositoryImpl(database).snapshot())
    }

    @Test fun emptyRestoreClearsBusinessRowsAndRestoresBackupPreferences(): Unit = withDatabase { database ->
        val writes = BackupMutationRepositoryImpl(database, DataGenerationRepositoryImpl(database))
        writes.replace(DataGeneration(0), BackupFixtures.fullPayload())
        val empty = BackupPayload(BackupFixtures.emptyTables, AppSettings())
        writes.replace(DataGeneration(1), empty)
        assertEquals(empty, BackupRepositoryImpl(database).snapshot())
    }

    @Test fun failedInsertRollsBackDeletedRowsSettingsAndGeneration(): Unit = withDatabase { database ->
        val generations = DataGenerationRepositoryImpl(database)
        val writes = BackupMutationRepositoryImpl(database, generations)
        val before = BackupFixtures.fullPayload()
        writes.replace(DataGeneration(0), before)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON habit_records BEGIN SELECT RAISE(ABORT, 'test insert failure'); END")
        assertFailsWith<Exception> { writes.replace(DataGeneration(1), before.copy(settings = AppSettings())) }
        assertEquals(before, BackupRepositoryImpl(database).snapshot())
        assertEquals(DataGeneration(1), generations.current())
    }

    @Test fun failedDeleteRollsBackEarlierChildDeletes(): Unit = withDatabase { database ->
        val generations = DataGenerationRepositoryImpl(database)
        val writes = BackupMutationRepositoryImpl(database, generations)
        val before = BackupFixtures.fullPayload()
        writes.replace(DataGeneration(0), before)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_clear BEFORE DELETE ON habits BEGIN SELECT RAISE(ABORT, 'test delete failure'); END")
        assertFailsWith<Exception> { writes.clearBusinessData(DataGeneration(1)) }
        assertEquals(before, BackupRepositoryImpl(database).snapshot())
        assertEquals(DataGeneration(1), generations.current())
    }

    @Test fun invalidInputDoesNotDeleteCurrentRows(): Unit = withDatabase { database ->
        val generations = DataGenerationRepositoryImpl(database)
        val writes = BackupMutationRepositoryImpl(database, generations)
        val before = BackupFixtures.fullPayload()
        writes.replace(DataGeneration(0), before)
        assertFailsWith<BackupValidationException> { writes.replace(DataGeneration(1), before.copy(tables = emptyMap())) }
        assertEquals(before, BackupRepositoryImpl(database).snapshot())
        assertEquals(DataGeneration(1), generations.current())
    }

    @Test fun cancellationAfterMutationBeforeGenerationAdvanceRollsEverythingBack(): Unit = withDatabase { database ->
        val real = DataGenerationRepositoryImpl(database)
        BackupMutationRepositoryImpl(database, real).replace(DataGeneration(0), BackupFixtures.fullPayload())
        val cancelling = object : DataGenerationRepository by real {
            override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration =
                real.commit(expected) { mutation(); throw CancellationException("before commit") }
        }
        val writes = BackupMutationRepositoryImpl(database, cancelling)
        assertFailsWith<CancellationException> { writes.clearBusinessData(DataGeneration(1)) }
        assertEquals(BackupFixtures.fullPayload(), BackupRepositoryImpl(database).snapshot())
        assertEquals(DataGeneration(1), real.current())
        assertFailsWith<CancellationException> { writes.replace(DataGeneration(1), BackupPayload(BackupFixtures.emptyTables, AppSettings())) }
        assertEquals(BackupFixtures.fullPayload(), BackupRepositoryImpl(database).snapshot())
    }

    @Test fun maximumOrMissingGenerationAndMissingSettingsRejectBeforeMutation(): Unit = withDatabase { database ->
        val generations = DataGenerationRepositoryImpl(database)
        val writes = BackupMutationRepositoryImpl(database, generations)
        writes.replace(DataGeneration(0), BackupFixtures.fullPayload())
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("UPDATE app_maintenance SET generation=9223372036854775807 WHERE id=1")
        assertFailsWith<IllegalStateException> { writes.clearBusinessData(DataGeneration(Long.MAX_VALUE)) }
        assertEquals(BackupFixtures.fullPayload(), BackupRepositoryImpl(database).snapshot())
        sqlite.execSQL("DELETE FROM app_maintenance")
        assertFailsWith<IllegalStateException> { writes.clearBusinessData(DataGeneration(0)) }
        sqlite.execSQL("DELETE FROM app_settings")
        assertFailsWith<IllegalStateException> { writes.replace(DataGeneration(0), BackupFixtures.fullPayload()) }
        sqlite.query("SELECT COUNT(*) FROM todos").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
    }

    private fun withDatabase(block: suspend (LifeManagerDatabase) -> Unit): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LifeManagerDatabase::class.java)
            .addCallback(LifeManagerDatabase.INITIALIZE).allowMainThreadQueries().build()
        try { block(database) } finally { database.close() }
    }
}
