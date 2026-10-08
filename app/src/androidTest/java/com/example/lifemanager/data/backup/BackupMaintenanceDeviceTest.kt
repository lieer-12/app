package com.example.lifemanager.data.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.data.repository.SettingsRepositoryImpl
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.ThemeMode
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Native SQLite and Downloads IO, isolated in-memory business DB; no fake mutation implementation. */
@RunWith(AndroidJUnit4::class)
class BackupMaintenanceDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun nativeRestoreRequiresVerifiedProtectionAndCancellationKeepsDatabase(): Unit = runBlocking {
        scenario { database, snapshots, generations, coordinator, files, workflow, protection ->
            val before = snapshots.snapshot()
            val restored = before.copy(tables = before.tables.mapValues { emptyList() })
            var confirmations = 0
            suspend fun restore(confirmed: Boolean) = workflow.execute(
                BackupMaintenanceRequest.Restore(DataGeneration(73), BackupLocation("content://test/source"), BackupDocument("test", "2026-10-05T00:00:00Z", restored)),
                "test", "2026-10-05T00:00:00Z", object : BackupMaintenanceInteraction {
                    override fun progress(stage: BackupMaintenanceStage) = Unit
                    override suspend fun chooseProtection() = protection
                    override suspend fun confirm(protection: BackupLocation?): Boolean {
                        assertEquals(MaintenanceState.READY, coordinator.state.value)
                        assertEquals(before, files.preview(requireNotNull(protection)).document.payload)
                        assertEquals(before, snapshots.snapshot())
                        assertEquals(DataGeneration(73), generations.current())
                        confirmations++
                        return confirmed
                    }
                },
            )
            assertFalse(restore(false).committed)
            assertEquals(before, snapshots.snapshot())
            assertEquals(DataGeneration(73), generations.current())
            assertTrue(restore(true).committed)
            assertEquals(restored, snapshots.snapshot())
            assertEquals(DataGeneration(74), generations.current())
            assertEquals(2, confirmations)
            assertEquals(MaintenanceState.IDLE, coordinator.state.value)
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        }
    }

    @Test fun nativeInsertFailureRollsBackRowsPreferencesAndGeneration(): Unit = runBlocking {
        scenario { database, snapshots, generations, coordinator, _, workflow, protection ->
            val before = snapshots.snapshot()
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON todos BEGIN SELECT RAISE(ABORT, 'synthetic insert failure'); END")
            assertFailsWith<Exception> {
                workflow.execute(BackupMaintenanceRequest.Restore(DataGeneration(73), BackupLocation("content://test/source"),
                    BackupDocument("test", "2026-10-05T00:00:00Z", before.copy(settings = before.settings.copy(theme = ThemeMode.LIGHT)))),
                    "test", "2026-10-05T00:00:00Z", object : BackupMaintenanceInteraction {
                        override fun progress(stage: BackupMaintenanceStage) = Unit
                        override suspend fun chooseProtection() = protection
                        override suspend fun confirm(protection: BackupLocation?) = true
                    })
            }
            assertEquals(before, snapshots.snapshot())
            assertEquals(DataGeneration(73), generations.current())
            assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        }
    }

    @Test fun nativeClearPreservesSettingsAndAdvancesLocalGenerationOnce(): Unit = runBlocking {
        scenario { _, snapshots, generations, coordinator, _, workflow, _ ->
            val before = snapshots.snapshot()
            val result = workflow.execute(BackupMaintenanceRequest.Clear(DataGeneration(73), false), "test", "2026-10-05T00:00:00Z",
                object : BackupMaintenanceInteraction {
                    override fun progress(stage: BackupMaintenanceStage) = Unit
                    override suspend fun chooseProtection(): BackupLocation = error("Clear without backup must not pick output")
                    override suspend fun confirm(protection: BackupLocation?): Boolean {
                        assertNull(protection)
                        assertEquals(before, snapshots.snapshot())
                        return true
                    }
                })
            assertTrue(result.committed)
            val after = snapshots.snapshot()
            assertEquals(before.settings, after.settings)
            assertTrue(after.tables.values.all { it.isEmpty() })
            assertEquals(DataGeneration(74), generations.current())
            assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        }
    }

    private suspend fun scenario(block: suspend (LifeManagerDatabase, BackupRepository, DataGenerationRepository, MaintenanceCoordinator,
        BackupFileUseCases, BackupMaintenanceWorkflow, BackupLocation) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, LifeManagerDatabase::class.java).addCallback(LifeManagerDatabase.INITIALIZE).build()
        val uri = newDocument()
        try {
            database.openHelper.writableDatabase.execSQL("INSERT INTO todos VALUES (41, 'synthetic native test', NULL, 'HIGH', NULL, 0, NULL, 7, 8, NULL, -3)")
            database.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=73 WHERE id=1")
            SettingsRepositoryImpl(database).updateSettings { it.copy(theme = ThemeMode.DARK, defaultCurrency = "EUR", todoReminders = false) }
            val generations = DataGenerationRepositoryImpl(database)
            val coordinator = MaintenanceCoordinator(generations)
            val snapshots = BackupRepositoryImpl(database)
            val files = BackupFileUseCases(snapshots, BackupJsonCodec(), AndroidBackupFileStore(context, Dispatchers.IO), coordinator, Dispatchers.IO)
            val effects = object : MaintenanceReminderEffects {
                override suspend fun clearPrevious(identities: ReminderIdentities) = Unit
                override suspend fun reconcile() { assertEquals(MaintenanceState.IDLE, coordinator.state.value) }
                override fun requestReconciliation() = Unit
            }
            val workflow = BackupMaintenanceWorkflow(snapshots, BackupMutationRepositoryImpl(database, generations), files, generations, coordinator, effects, Dispatchers.IO)
            block(database, snapshots, generations, coordinator, files, workflow, BackupLocation(uri.toString()))
        } finally {
            database.close()
            assertEquals(1, context.contentResolver.delete(uri, null, null)) // Only this test's exact synthetic URI.
        }
    }

    private fun newDocument(): Uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, "phase5-native-${UUID.randomUUID()}.json")
        put(MediaStore.Downloads.MIME_TYPE, "application/json")
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Phase5-tests")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }))
}
