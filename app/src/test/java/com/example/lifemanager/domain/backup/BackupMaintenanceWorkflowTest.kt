package com.example.lifemanager.domain.backup

import com.example.lifemanager.data.backup.BackupFixtures
import com.example.lifemanager.data.backup.BackupJsonCodec
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BackupMaintenanceWorkflowTest {
    private val source = BackupLocation("content://test/source")
    private val protection = BackupLocation("content://test/protection")
    private val replacement = BackupDocument("0.1.0", "2026-10-04T00:00:00Z", BackupPayload(BackupFixtures.emptyTables, AppSettings()))

    @Test fun verifiedProtectionAndIndependentFinalConfirmationPrecedeAtomicRestore() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        val result = fixture.restore()
        assertTrue(result.committed)
        assertFalse(result.remindersPending)
        assertEquals(replacement.payload, fixture.payload)
        assertEquals(DataGeneration(8), fixture.generations.current())
        assertEquals(BackupFixtures.fullPayload(), BackupJsonCodec().decode(fixture.saved).payload)
        assertEquals(listOf("snapshot", "choose", "write", "read", "confirm", "replace", "clear-reminders", "enqueue", "reconcile"), fixture.events)
        assertEquals(MaintenanceState.IDLE, fixture.coordinator.state.value)
    }

    @Test fun sameSourceAndProtectionUriIsRejectedBeforeOutputIsOpened() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.destination = source
        assertFailsWith<BackupFileException> { fixture.restore() }
        assertEquals(listOf("snapshot", "choose", "enqueue"), fixture.events)
        fixture.assertUnchanged()
    }

    @Test fun backupFailurePreventsFinalConfirmationAndRestore() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.writeFails = true
        assertFailsWith<BackupFileException> { fixture.restore() }
        assertEquals(listOf("snapshot", "choose", "write", "enqueue"), fixture.events)
        fixture.assertUnchanged()
    }

    @Test fun validButDifferentProtectionFilePreventsRestore() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.reopened = BackupJsonCodec().encode(replacement)
        assertFailsWith<BackupFileException> { fixture.restore() }
        assertFalse("confirm" in fixture.events)
        fixture.assertUnchanged()
    }

    @Test fun cancellingProtectionPickerReleasesFreezeAndRequestsFreshCalibration() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.destination = null
        assertFalse(fixture.restore().committed)
        assertEquals(listOf("snapshot", "choose", "enqueue", "reconcile"), fixture.events)
        fixture.assertUnchanged()
    }

    @Test fun cancellingFinalConfirmationKeepsDataAndVerifiedProtectionFile() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.confirmed = false
        assertFalse(fixture.restore().committed)
        assertTrue(fixture.saved.isNotEmpty())
        assertFalse("replace" in fixture.events)
        fixture.assertUnchanged()
    }

    @Test fun coroutineCancellationWhileWaitingDoesNotCommitOrLeakMaintenanceSession() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.waitForChoice = CompletableDeferred()
        val task = async { fixture.restore() }
        fixture.choiceEntered.await()
        assertEquals(MaintenanceState.READY, fixture.coordinator.state.value)
        assertFailsWith<MaintenanceBusyException> { fixture.coordinator.capture() }
        task.cancelAndJoin()
        fixture.assertUnchanged()
        assertEquals(1, fixture.events.count { it == "enqueue" })
    }

    @Test fun databaseFailureDoesNotClearRemindersAndStillReleasesFreeze() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.commitFails = true
        assertFailsWith<IllegalStateException> { fixture.restore() }
        assertFalse("clear-reminders" in fixture.events)
        fixture.assertUnchanged()
        assertTrue("enqueue" in fixture.events)
    }

    @Test fun reminderCleanupFailureReportsCommittedDataSeparately() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.cleanupFails = true
        assertEquals(BackupMaintenanceResult(true, true), fixture.restore())
        assertEquals(replacement.payload, fixture.payload)
        assertEquals(DataGeneration(8), fixture.generations.current())
        assertTrue("enqueue" in fixture.events)
    }

    @Test fun reconciliationFailureDoesNotMisreportDatabaseRollback() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.reconcileFails = true
        assertEquals(BackupMaintenanceResult(true, true), fixture.restore())
        assertEquals(replacement.payload, fixture.payload)
        assertEquals(MaintenanceState.IDLE, fixture.coordinator.state.value)
    }

    @Test fun cancellationAfterCommittedReturnStillRequestsCalibration() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.cancelAfterCommit = true
        assertFailsWith<BackupCommittedCancellationException> { fixture.restore() }
        assertEquals(replacement.payload, fixture.payload)
        assertEquals(DataGeneration(8), fixture.generations.current())
        assertEquals(MaintenanceState.IDLE, fixture.coordinator.state.value)
        assertTrue("enqueue" in fixture.events)
    }

    @Test fun clearWithoutBackupPreservesSettingsAndDoesNotRequireAnExportableSnapshot() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.snapshotFails = true
        val result = fixture.execute(BackupMaintenanceRequest.Clear(DataGeneration(7), false))
        assertTrue(result.committed)
        assertEquals(BackupPayload(BackupFixtures.emptyTables, BackupFixtures.fullPayload().settings), fixture.payload)
        assertFalse("snapshot" in fixture.events)
        assertFalse("choose" in fixture.events)
        assertEquals(listOf("confirm", "clear", "clear-reminders", "enqueue", "reconcile"), fixture.events)
    }

    @Test fun clearWithBackupRequiresVerifiedFileAndCanBeCancelledAtFinalConfirmation() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.confirmed = false
        assertFalse(fixture.execute(BackupMaintenanceRequest.Clear(DataGeneration(7), true)).committed)
        assertEquals(BackupFixtures.fullPayload(), BackupJsonCodec().decode(fixture.saved).payload)
        fixture.assertUnchanged()
    }

    @Test fun staleWorkflowRequestCannotReplaceNewerData() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        assertFailsWith<StaleGenerationException> {
            fixture.execute(BackupMaintenanceRequest.Restore(DataGeneration(6), source, replacement))
        }
        assertEquals(listOf("enqueue"), fixture.events)
        fixture.assertUnchanged()
    }

    private inner class Fixture(dispatcher: CoroutineDispatcher) : BackupMaintenanceInteraction {
        val events = mutableListOf<String>()
        var payload = BackupFixtures.fullPayload()
        var destination: BackupLocation? = protection
        var confirmed = true
        var writeFails = false
        var commitFails = false
        var cleanupFails = false
        var reconcileFails = false
        var snapshotFails = false
        var cancelAfterCommit = false
        var saved = byteArrayOf()
        var reopened: ByteArray? = null
        var waitForChoice: CompletableDeferred<Unit>? = null
        val choiceEntered = CompletableDeferred<Unit>()
        val generation = MutableStateFlow(DataGeneration(7))
        val generations = object : DataGenerationRepository {
            override fun observe() = generation
            override suspend fun current() = generation.value
            override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration {
                if (expected != generation.value) throw StaleGenerationException()
                mutation(); generation.value = DataGeneration(expected.value + 1); return generation.value
            }
        }
        val coordinator = MaintenanceCoordinator(generations)
        val snapshots = object : BackupRepository {
            override suspend fun snapshot(): BackupPayload {
                events += "snapshot"
                if (snapshotFails) throw BackupValidationException("too large to export")
                return payload
            }
        }
        val mutations = object : BackupMutationRepository {
            override suspend fun reminderIdentities() = ReminderIdentities(listOf(41), listOf(6), listOf(8))
            override suspend fun replace(expected: DataGeneration, payload: BackupPayload): DataGeneration {
                events += "replace"
                if (commitFails) error("rollback")
                return generations.commit(expected) { this@Fixture.payload = payload }.also {
                    if (cancelAfterCommit) throw CancellationException("committed return hand-off")
                }
            }
            override suspend fun clearBusinessData(expected: DataGeneration): DataGeneration {
                events += "clear"
                return generations.commit(expected) { payload = payload.copy(tables = BackupFixtures.emptyTables) }
            }
        }
        val fileStore = object : BackupFileStore {
            override suspend fun write(location: BackupLocation, bytes: ByteArray) {
                assertEquals(MaintenanceState.READY, coordinator.state.value)
                events += "write"
                if (writeFails) throw BackupFileException("output failure")
                saved = bytes.copyOf()
            }
            override suspend fun read(location: BackupLocation, maxBytes: Int): ByteArray { events += "read"; return reopened ?: saved }
            override suspend fun <T> withStagedInput(location: BackupLocation, maxBytes: Int, operation: suspend (ByteArray) -> T): T = error("preview already validated")
        }
        val reminders = object : MaintenanceReminderEffects {
            override suspend fun clearPrevious(identities: ReminderIdentities) {
                assertEquals(MaintenanceState.RUNNING, coordinator.state.value)
                assertEquals(ReminderIdentities(listOf(41), listOf(6), listOf(8)), identities)
                events += "clear-reminders"
                if (cleanupFails) error("alarm service unavailable")
            }
            override suspend fun reconcile() {
                assertEquals(MaintenanceState.IDLE, coordinator.state.value)
                events += "reconcile"
                if (reconcileFails) error("retry later")
            }
            override fun requestReconciliation() { assertEquals(MaintenanceState.IDLE, coordinator.state.value); events += "enqueue" }
        }
        private val workflow = BackupMaintenanceWorkflow(snapshots, mutations,
            BackupFileUseCases(snapshots, BackupJsonCodec(), fileStore, coordinator, dispatcher),
            generations, coordinator, reminders, dispatcher)
        suspend fun restore() = execute(BackupMaintenanceRequest.Restore(DataGeneration(7), source, replacement))
        suspend fun execute(request: BackupMaintenanceRequest) = workflow.execute(request, "0.1.0", "2026-10-04T00:00:00Z", this)
        override fun progress(stage: BackupMaintenanceStage) = Unit
        override suspend fun chooseProtection(): BackupLocation? {
            assertEquals(MaintenanceState.READY, coordinator.state.value)
            events += "choose"; choiceEntered.complete(Unit); waitForChoice?.await(); return destination
        }
        override suspend fun confirm(protection: BackupLocation?): Boolean {
            assertEquals(MaintenanceState.READY, coordinator.state.value)
            events += "confirm"; return confirmed
        }
        suspend fun assertUnchanged() {
            assertEquals(BackupFixtures.fullPayload(), payload)
            assertEquals(DataGeneration(7), generations.current())
            assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        }
    }
}
