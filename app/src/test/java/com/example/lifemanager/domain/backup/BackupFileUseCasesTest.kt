package com.example.lifemanager.domain.backup

import com.example.lifemanager.data.backup.BackupFixtures
import com.example.lifemanager.data.backup.BackupJsonCodec
import com.example.lifemanager.domain.maintenance.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BackupFileUseCasesTest {
    private val location = BackupLocation("content://backup.test/output")
    private val document = BackupDocument("0.1.0", "2026-10-04T00:00:00Z", BackupFixtures.fullPayload())
    private val codec = BackupJsonCodec()

    @Test fun exportClosesOutputThenReopensAndComparesAllFieldsWithoutWritingDatabase() = runTest {
        val fixture = Fixture()
        val result = fixture.useCases(UnconfinedTestDispatcher(testScheduler)).export(location, document.appVersion, document.exportedAt)
        assertEquals(document, result)
        assertEquals(listOf("snapshot", "write-closed", "read"), fixture.events)
        assertEquals(document, codec.decode(fixture.files.bytes))
        assertEquals(DataGeneration(12), fixture.generations.current())
        assertEquals(0, fixture.generations.commits)
    }

    @Test fun anotherValidBackupIsNotAcceptedAsVerifiedOutput() = runTest {
        val fixture = Fixture()
        fixture.files.reopened = codec.encode(document.copy(appVersion = "other"))
        assertFailsWith<BackupFileException> {
            fixture.useCases(UnconfinedTestDispatcher(testScheduler)).export(location, document.appVersion, document.exportedAt)
        }
        assertEquals(listOf("snapshot", "write-closed", "read"), fixture.events)
        assertEquals(0, fixture.generations.commits)
    }

    @Test fun truncatedOutputDoesNotProduceSuccess() = runTest {
        val fixture = Fixture()
        fixture.files.reopened = byteArrayOf('{'.code.toByte())
        assertFailsWith<BackupValidationException> {
            fixture.useCases(UnconfinedTestDispatcher(testScheduler)).writeVerified(location, document)
        }
        assertEquals(listOf("write-closed", "read"), fixture.events)
    }

    @Test fun outputCloseFailureDoesNotAttemptReadOrClaimSuccess() = runTest {
        val fixture = Fixture()
        val error = BackupFileException("close failed")
        fixture.files.writeFailure = error
        assertSame(error, assertFailsWith<BackupFileException> {
            fixture.useCases(UnconfinedTestDispatcher(testScheduler)).writeVerified(location, document)
        })
        assertEquals(listOf("write-closed"), fixture.events)
    }

    @Test fun readPermissionFailureIsPropagatedWithoutDatabaseChanges() = runTest {
        val fixture = Fixture()
        val error = BackupFileException("permission denied")
        fixture.files.readFailure = error
        val result = assertFailsWith<BackupFileException> {
            fixture.useCases(UnconfinedTestDispatcher(testScheduler)).export(location, document.appVersion, document.exportedAt)
        }
        // Coroutine stack recovery may copy this exception across withContext boundaries.
        assertEquals(error.message, result.message)
        assertTrue(result === error || result.cause === error)
        assertEquals(0, fixture.generations.commits)
    }

    @Test fun invalidSnapshotIsRejectedBeforeOpeningDestination() = runTest {
        val fixture = Fixture(document.payload.copy(tables = emptyMap()))
        assertFailsWith<BackupValidationException> {
            fixture.useCases(UnconfinedTestDispatcher(testScheduler)).export(location, document.appVersion, document.exportedAt)
        }
        assertEquals(listOf("snapshot"), fixture.events)
    }

    @Test fun previewUsesPrivateStageAndDoesNotReadOrWriteCurrentDatabase() = runTest {
        val fixture = Fixture()
        fixture.files.bytes = codec.encode(document)
        val preview = fixture.useCases(UnconfinedTestDispatcher(testScheduler)).preview(location)
        assertEquals(document, preview.document)
        assertEquals(10, preview.totalRows)
        assertEquals(BackupFixtures.tableNames.associateWith { 1 }, preview.rowCounts)
        assertEquals(listOf("stage", "stage-cleanup"), fixture.events)
        assertEquals(0, fixture.generations.commits)
    }

    @Test fun invalidPreviewCleansStageWithoutRepairingDatabase() = runTest {
        val fixture = Fixture()
        fixture.files.bytes = "not JSON".toByteArray()
        assertFailsWith<BackupValidationException> {
            fixture.useCases(UnconfinedTestDispatcher(testScheduler)).preview(location)
        }
        assertEquals(listOf("stage", "stage-cleanup"), fixture.events)
        assertEquals(0, fixture.generations.commits)
    }

    @Test fun ordinaryPermitIsReleasedBeforeSlowExternalFileIO() = runTest {
        val fixture = Fixture()
        fixture.files.pauseWrite = CompletableDeferred()
        val export = async { fixture.useCases(UnconfinedTestDispatcher(testScheduler)).export(location, document.appVersion, document.exportedAt) }
        fixture.files.writeEntered.await()
        fixture.coordinator.withSession { session ->
            assertEquals(MaintenanceState.READY, fixture.coordinator.state.value)
            assertEquals(DataGeneration(12), session.generation)
        }
        fixture.files.pauseWrite!!.complete(Unit)
        assertEquals(document, export.await())
    }

    @Test fun maintenanceAlreadyActiveRejectsExportBeforeSnapshotOrFileIO() = runTest {
        val fixture = Fixture()
        fixture.coordinator.withSession {
            assertFailsWith<MaintenanceBusyException> {
                fixture.useCases(UnconfinedTestDispatcher(testScheduler)).export(location, document.appVersion, document.exportedAt)
            }
        }
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun previewIsReadOnlyAndAvailableWhileMaintenanceReady() = runTest {
        val fixture = Fixture()
        fixture.files.bytes = codec.encode(document)
        fixture.coordinator.withSession {
            assertEquals(document, fixture.useCases(UnconfinedTestDispatcher(testScheduler)).preview(location).document)
            assertEquals(MaintenanceState.READY, fixture.coordinator.state.value)
        }
    }

    @Test fun cancellationAtOutputDoesNotReopenOrPublishSuccess() = runTest {
        val fixture = Fixture()
        fixture.files.pauseWrite = CompletableDeferred()
        val export = async { fixture.useCases(UnconfinedTestDispatcher(testScheduler)).export(location, document.appVersion, document.exportedAt) }
        fixture.files.writeEntered.await()
        export.cancelAndJoin()
        assertEquals(listOf("snapshot", "write-closed"), fixture.events)
        assertEquals(MaintenanceState.IDLE, fixture.coordinator.state.value)
        assertEquals(0, fixture.generations.commits)
    }

    @Test fun cancellationIsNotWrappedAsFileFailure() = runTest {
        val fixture = Fixture()
        val error = CancellationException("cancel")
        fixture.files.writeFailure = error
        assertSame(error, assertFailsWith<CancellationException> {
            fixture.useCases(UnconfinedTestDispatcher(testScheduler)).writeVerified(location, document)
        })
    }

    private inner class Fixture(payload: BackupPayload = document.payload) {
        val events = mutableListOf<String>()
        val generations = Generations()
        val coordinator = MaintenanceCoordinator(generations)
        val files = Files(events)
        val repository = object : BackupRepository {
            override suspend fun snapshot(): BackupPayload { events += "snapshot"; return payload }
        }
        fun useCases(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = BackupFileUseCases(repository, codec, files, coordinator, dispatcher)
    }

    private class Generations : DataGenerationRepository {
        val state = MutableStateFlow(DataGeneration(12))
        var commits = 0
        override fun observe() = state
        override suspend fun current() = state.value
        override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration {
            commits++; mutation(); state.value = DataGeneration(expected.value + 1); return state.value
        }
    }

    private class Files(private val events: MutableList<String>) : BackupFileStore {
        var bytes = byteArrayOf()
        var reopened: ByteArray? = null
        var writeFailure: Exception? = null
        var readFailure: Exception? = null
        val writeEntered = CompletableDeferred<Unit>()
        var pauseWrite: CompletableDeferred<Unit>? = null
        override suspend fun write(location: BackupLocation, bytes: ByteArray) {
            events += "write-closed"
            writeFailure?.let { throw it }
            this.bytes = bytes.copyOf()
            writeEntered.complete(Unit)
            pauseWrite?.await()
        }
        override suspend fun read(location: BackupLocation, maxBytes: Int): ByteArray {
            events += "read"
            readFailure?.let { throw it }
            assertEquals(BackupLimits().maxBytes, maxBytes)
            return reopened ?: bytes
        }
        override suspend fun <T> withStagedInput(location: BackupLocation, maxBytes: Int, operation: suspend (ByteArray) -> T): T {
            events += "stage"
            assertEquals(BackupLimits().maxBytes, maxBytes)
            return try { operation(bytes.copyOf()) } finally { events += "stage-cleanup" }
        }
    }
}
