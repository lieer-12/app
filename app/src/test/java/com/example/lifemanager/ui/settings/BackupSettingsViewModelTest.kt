package com.example.lifemanager.ui.settings

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.data.backup.BackupFixtures
import com.example.lifemanager.data.backup.BackupJsonCodec
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BackupSettingsViewModelTest {
    @Test fun restoreNeedsPreviewVerifiedProtectionAndSeparateFinalConsentAndCannotReplay() = scenario {
        preview()
        assertEquals(0, commits)
        model.answerFirst(id(), true)
        advanceUntilIdle()
        assertEquals(BackupStep.PICK_OUTPUT, model.uiState.value.step)
        assertEquals(MaintenanceState.READY, coordinator.state.value)
        assertFailsWith<MaintenanceBusyException> { coordinator.capture() }
        model.outputSelected(id(), "content://test/protection")
        advanceUntilIdle()
        assertEquals(BackupStep.FINAL_CONFIRM, model.uiState.value.step)
        assertEquals(1, writes)
        assertEquals(0, commits)
        val operation = id()
        model.answerFinal(operation, true)
        model.answerFinal(operation, true)
        advanceUntilIdle()
        assertEquals(1, commits)
        assertEquals(DataGeneration(1), generations.current())
        assertEquals(BackupStep.FINISHED, model.uiState.value.step)
        model.answerFirst(operation, true)
        model.answerFinal(operation, true)
        model.outputSelected(operation, "content://test/other")
        advanceUntilIdle()
        assertEquals(1, commits)
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    @Test fun cancellingFinalConfirmationKeepsCurrentPayloadAndGeneration() = scenario {
        preview()
        model.answerFirst(id(), true); advanceUntilIdle()
        model.outputSelected(id(), "content://test/protection"); advanceUntilIdle()
        model.answerFinal(id(), false); advanceUntilIdle()
        assertEquals(0, commits)
        assertEquals(original, payload)
        assertEquals(DataGeneration(0), generations.current())
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        assertTrue(model.uiState.value.message!!.contains("原数据和草稿已保留"))
        assertEquals(1, queued)
    }

    @Test fun outputPermissionFailureStopsBeforeFinalConfirmationAndMutation() = scenario {
        preview(); failWrite = true
        model.answerFirst(id(), true); advanceUntilIdle()
        model.outputSelected(id(), "content://test/protection"); advanceUntilIdle()
        assertEquals(BackupStep.ERROR, model.uiState.value.step)
        assertEquals(0, commits)
        assertEquals(original, payload)
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        assertEquals(1, queued)
    }

    @Test fun sameSourceCannotBeUsedAsProtectionOutput() = scenario {
        preview()
        model.answerFirst(id(), true); advanceUntilIdle()
        model.outputSelected(id(), "content://test/source"); advanceUntilIdle()
        assertEquals(BackupStep.ERROR, model.uiState.value.step)
        assertEquals(0, writes)
        assertEquals(0, commits)
    }

    @Test fun malformedSourceNeverShowsPreviewAndNeverFreezesOrMutates() = scenario {
        source = "{}".toByteArray()
        model.start(BackupAction.RESTORE, DataGeneration(0)); advanceUntilIdle()
        model.sourceSelected(id(), "content://test/source"); advanceUntilIdle()
        assertEquals(BackupStep.ERROR, model.uiState.value.step)
        assertEquals(0, writes)
        assertEquals(0, commits)
        assertEquals(0, queued)
    }

    @Test fun oldPickerCallbacksCannotCompleteAnotherOperation() = scenario {
        model.start(BackupAction.RESTORE, DataGeneration(0)); advanceUntilIdle()
        val old = id()
        model.cancel(old); advanceUntilIdle()
        model.start(BackupAction.RESTORE, DataGeneration(0)); advanceUntilIdle()
        assertNotEquals(old, id())
        model.sourceSelected(old, "content://test/source")
        model.answerFirst(old, true)
        model.answerFinal(old, true)
        advanceUntilIdle()
        assertEquals(BackupStep.PICK_SOURCE, model.uiState.value.step)
        assertEquals(0, commits)
    }

    @Test fun newProcessOwnerStartsIdleAndDoesNotRecreatePendingConfirmation() = scenario {
        preview()
        val old = id()
        model.viewModelScope.cancel(); advanceUntilIdle()
        model = newModel()
        model.sourceSelected(old, "content://test/source")
        model.answerFirst(old, true); model.answerFinal(old, true)
        advanceUntilIdle()
        assertEquals(BackupStep.IDLE, model.uiState.value.step)
        assertEquals(0, commits)
    }

    @Test fun destroyedOwnerReleasesFreezeWithoutReplacingData() = scenario {
        preview(); model.answerFirst(id(), true); advanceUntilIdle()
        assertEquals(MaintenanceState.READY, coordinator.state.value)
        model.viewModelScope.cancel(); advanceUntilIdle()
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        assertEquals(0, commits)
        assertEquals(1, queued)
    }

    @Test fun generationChangeWhilePreviewWaitsCannotBeRetaggedForRestore() = scenario {
        preview()
        generations.commit(DataGeneration(0)) {}
        model.answerFirst(id(), true); advanceUntilIdle()
        assertEquals(BackupStep.ERROR, model.uiState.value.step)
        assertEquals(0, commits)
        assertEquals(0, writes)
    }

    @Test fun clearWithoutBackupStillNeedsFinalConsentAndPreservesSettings() = scenario {
        model.start(BackupAction.CLEAR, DataGeneration(0)); advanceUntilIdle()
        model.answerFirst(id(), false); advanceUntilIdle()
        assertEquals(BackupStep.FINAL_CONFIRM, model.uiState.value.step)
        assertEquals(0, commits)
        model.answerFinal(id(), true); advanceUntilIdle()
        assertEquals(1, commits)
        assertEquals(original.settings, payload.settings)
        assertTrue(payload.tables.values.all { it.isEmpty() })
        assertEquals(0, writes)
    }

    @Test fun exportHasPrivacyConsentAndDoesNotChangeData() = scenario {
        model.start(BackupAction.EXPORT, DataGeneration(0)); advanceUntilIdle()
        assertEquals(BackupStep.EXPORT_NOTICE, model.uiState.value.step)
        assertEquals(0, writes)
        model.answerFirst(id(), true); advanceUntilIdle()
        model.outputSelected(id(), "content://test/export"); advanceUntilIdle()
        assertEquals(1, writes)
        assertEquals(BackupStep.FINISHED, model.uiState.value.step)
        assertEquals(0, commits)
        assertEquals(original, BackupJsonCodec().decode(saved).payload)
    }

    @Test fun reminderFailureReportsCommittedDataRatherThanOfferingMutationRetry() = scenario {
        failReconcile = true
        model.start(BackupAction.CLEAR, DataGeneration(0)); advanceUntilIdle()
        model.answerFirst(id(), false); advanceUntilIdle()
        model.answerFinal(id(), true); advanceUntilIdle()
        assertEquals(1, commits)
        assertEquals(BackupStep.FINISHED, model.uiState.value.step)
        assertTrue(model.uiState.value.message!!.contains("业务数据已清空"))
        assertTrue(model.uiState.value.message!!.contains("部分提醒处理失败"))
    }

    @Test fun cancellationAfterCommitReportsChangedDataNotCancelledMutation() = scenario {
        cancelAfterCommit = true
        model.start(BackupAction.CLEAR, DataGeneration(0)); advanceUntilIdle()
        model.answerFirst(id(), false); advanceUntilIdle()
        model.answerFinal(id(), true); advanceUntilIdle()
        assertEquals(1, commits)
        assertEquals(BackupStep.FINISHED, model.uiState.value.step)
        assertTrue(model.uiState.value.message!!.contains("业务数据已清空"))
        assertFalse(model.uiState.value.message!!.contains("已取消"))
        assertEquals(1, queued)
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    @Test fun cancellationAfterWorkflowReturnsButBeforeMainPublicationStillReportsCommit() = scenario {
        model.start(BackupAction.CLEAR, DataGeneration(0)); advanceUntilIdle()
        model.answerFirst(id(), false); advanceUntilIdle()
        val mainQueue = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(mainQueue))
        model.answerFinal(id(), true); advanceUntilIdle()
        assertEquals(1, commits)
        assertEquals(BackupStep.RECONCILING, model.uiState.value.step)
        model.viewModelScope.cancel()
        repeat(3) { mainQueue.advanceUntilIdle(); advanceUntilIdle() }
        assertEquals(BackupStep.FINISHED, model.uiState.value.step)
        assertTrue(model.uiState.value.message!!.contains("业务数据已清空"))
        assertFalse(model.uiState.value.message!!.contains("已取消"))
    }

    private fun scenario(block: suspend Fixture.() -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = Fixture(this, dispatcher)
        try { fixture.block() } finally { fixture.model.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    private class Fixture(private val scope: TestScope, private val dispatcher: CoroutineDispatcher) {
        val original = BackupFixtures.fullPayload()
        var payload = original
        var source = BackupJsonCodec().encode(BackupDocument("test", "2026-10-05T00:00:00Z", BackupPayload(BackupFixtures.emptyTables, AppSettings())))
        var saved = byteArrayOf()
        var writes = 0
        var commits = 0
        var queued = 0
        var failWrite = false
        var failReconcile = false
        var cancelAfterCommit = false
        val generations = TestGenerations()
        val coordinator = MaintenanceCoordinator(generations)
        private val snapshots = object : BackupRepository { override suspend fun snapshot() = payload }
        private val mutations = object : BackupMutationRepository {
            override suspend fun reminderIdentities() = ReminderIdentities(emptyList(), emptyList(), emptyList())
            override suspend fun replace(expected: DataGeneration, payload: BackupPayload) = generations.commit(expected) { this@Fixture.payload = payload; commits++ }
            override suspend fun clearBusinessData(expected: DataGeneration) = generations.commit(expected) { payload = BackupPayload(BackupFixtures.emptyTables, payload.settings); commits++ }.also {
                if (cancelAfterCommit) throw CancellationException("synthetic committed handoff")
            }
        }
        private val storage = object : BackupFileStore {
            override suspend fun write(location: BackupLocation, bytes: ByteArray) {
                if (failWrite) throw BackupFileException("无法访问文档，请重新选择文件")
                writes++; saved = bytes
            }
            override suspend fun read(location: BackupLocation, maxBytes: Int) = if (location.value.endsWith("source")) source else saved
            override suspend fun <T> withStagedInput(location: BackupLocation, maxBytes: Int, operation: suspend (ByteArray) -> T) = operation(read(location, maxBytes))
        }
        private val effects = object : MaintenanceReminderEffects {
            override suspend fun clearPrevious(identities: ReminderIdentities) = Unit
            override suspend fun reconcile() { if (failReconcile) error("synthetic failure") }
            override fun requestReconciliation() { queued++ }
        }
        private val files = BackupFileUseCases(snapshots, BackupJsonCodec(), storage, coordinator, dispatcher)
        private val workflow = BackupMaintenanceWorkflow(snapshots, mutations, files, generations, coordinator, effects, dispatcher)
        var model = newModel()
        fun newModel() = BackupSettingsViewModel(files, workflow, GenerationAccess(generations, coordinator), dispatcher)
        fun id() = requireNotNull(model.uiState.value.operationId)
        fun advanceUntilIdle() = scope.testScheduler.advanceUntilIdle()
        fun preview() {
            model.start(BackupAction.RESTORE, DataGeneration(0)); advanceUntilIdle()
            model.sourceSelected(id(), "content://test/source"); advanceUntilIdle()
            assertEquals(BackupStep.RESTORE_PREVIEW, model.uiState.value.step)
            assertEquals(10, model.uiState.value.rowCounts.size)
        }
    }
}
