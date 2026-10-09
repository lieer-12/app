package com.example.lifemanager.ui.schedule

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleException
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.usecase.ScheduleOperationCoordinator
import com.example.lifemanager.notification.ScheduleReminderSchedulerContract
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.PausingMainDispatcher
import com.example.lifemanager.ui.common.TestGenerations
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleMaintenanceTest {
    @Test fun busySaveIsRejectedBeforeDispatchAndNeverReplayedAfterCancellation() = runTest {
        scenario { model, fixture ->
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("保留的草稿")
            advanceUntilIdle()
            fixture.coordinator.withSession {
                model.saveSchedule()
                // Release without pumping the queued VM work: admission must reject at arrival.
            }
            advanceUntilIdle()
            assertEquals(0, fixture.repo.saves)
            assertTrue(fixture.alarms.events.isEmpty())
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals("保留的草稿", model.uiState.value.editor.title)
            assertFalse(model.uiState.value.editor.isSaving)
            model.saveSchedule()
            advanceUntilIdle()
            assertEquals("保留的草稿", fixture.repo.rows.single().title)
            assertEquals(1, fixture.repo.saves)
        }
    }

    @Test fun busyDeleteIsNeverQueuedUntilAfterMaintenance() = runTest {
        scenario { model, fixture ->
            fixture.coordinator.withSession { model.deleteSchedule(42) }
            advanceUntilIdle()
            assertEquals(0, fixture.repo.deletes)
            assertEquals(42L, fixture.repo.rows.single().id)
            assertTrue(fixture.alarms.events.isEmpty())
        }
    }

    @Test fun cancellingMaintenancePreservesDraftAndPendingNotification() = runTest {
        scenario { model, fixture ->
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("未提交草稿")
            model.openNotificationDetail(43)
            advanceUntilIdle()
            val before = model.uiState.value.editor
            val freeze = launch { fixture.coordinator.withSession { awaitCancellation() } }
            runCurrent()
            try {
                assertEquals(MaintenanceState.READY, fixture.coordinator.state.value)
                assertEquals(before, model.uiState.value.editor)
            } finally { freeze.cancel() }
            freeze.join()
            advanceUntilIdle()
            assertEquals(DataGeneration(7), fixture.generations.current())
            assertEquals(before, model.uiState.value.editor)
        }
    }

    @Test fun replacementClearsDraftAndPendingNotificationEvenWhenIdIsReused() = runTest {
        scenario { model, fixture ->
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("旧草稿")
            model.openNotificationDetail(42)
            advanceUntilIdle()
            fixture.replace()
            advanceUntilIdle()
            assertFalse(model.uiState.value.editor.isOpen)
            assertNull(model.uiState.value.editor.pendingNotificationId)
            assertEquals("恢复的新记录", model.uiState.value.schedules.single().title)
            model.closeEditor()
            advanceUntilIdle()
            assertFalse(model.uiState.value.editor.isOpen)
        }
    }

    @Test fun queuedSaveRetainsOldGenerationAndCannotOverwriteReplacement() = runTest {
        scenario { model, fixture ->
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("延迟旧写入")
            model.saveSchedule()
            fixture.replace()
            advanceUntilIdle()
            assertEquals(0, fixture.repo.saves)
            assertEquals("恢复的新记录", fixture.repo.rows.single().title)
            assertTrue(fixture.alarms.events.isEmpty())
            assertFalse(model.uiState.value.editor.isOpen)
            assertNull(model.uiState.value.errorMessage)
        }
    }

    @Test fun queuedDeleteCannotDeleteReplacementOrCancelItsReminder() = runTest {
        scenario { model, fixture ->
            model.deleteSchedule(42)
            fixture.replace()
            advanceUntilIdle()
            assertEquals(0, fixture.repo.deletes)
            assertEquals("恢复的新记录", fixture.repo.rows.single().title)
            assertTrue(fixture.alarms.events.isEmpty())
        }
    }

    @Test fun writeQueuedBeforeFreezeIsRejectedAtAdmissionAndNeverRetried() = runTest {
        scenario { model, fixture ->
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("准入前已冻结")
            model.saveSchedule()
            fixture.coordinator.withSession { runCurrent() }
            advanceUntilIdle()
            assertEquals(0, fixture.repo.saves)
            assertTrue(fixture.alarms.events.isEmpty())
            assertTrue(model.uiState.value.editor.isOpen)
            assertFalse(model.uiState.value.editor.isSaving)
            assertEquals("准入前已冻结", model.uiState.value.editor.title)
        }
    }

    @Test fun oldSaveCallbackCannotSubmitANewEditorAfterReplacement() = runTest {
        scenario { model, fixture ->
            model.openEditor(fixture.repo.rows.single())
            val editorGeneration = model.uiState.value.editor.generation
            val oldSave = { model.saveSchedule(generation = editorGeneration) }
            fixture.replace()
            advanceUntilIdle()
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("新的草稿")
            oldSave()
            advanceUntilIdle()
            assertEquals(0, fixture.repo.saves)
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals("新的草稿", model.uiState.value.editor.title)
        }
    }

    @Test fun oldDeleteCallbackCannotDeleteReusedIdAfterFreshSnapshotIsPublished() = runTest {
        scenario { model, fixture ->
            val renderedGeneration = model.uiState.value.generation
            val oldDelete = { model.deleteSchedule(42, renderedGeneration) }
            fixture.replace()
            advanceUntilIdle()
            oldDelete()
            advanceUntilIdle()
            assertEquals(0, fixture.repo.deletes)
            assertEquals("恢复的新记录", fixture.repo.rows.single().title)
            assertTrue(fixture.alarms.events.isEmpty())
        }
    }

    @Test fun oldListOpenCallbackCannotEditCachedDtoAfterSameIdReplacement() = runTest {
        scenario { model, fixture ->
            val oldRow = fixture.repo.rows.single()
            val renderedGeneration = model.uiState.value.generation
            val oldOpen = { model.openEditor(oldRow, renderedGeneration) }
            fixture.replace()
            advanceUntilIdle()
            oldOpen()
            advanceUntilIdle()
            assertFalse(model.uiState.value.editor.isOpen)
            assertEquals("恢复的新记录", model.uiState.value.schedules.single().title)
        }
    }

    @Test fun everyOldInputAndDismissCallbackLeavesNewEditorUntouched() = runTest {
        val callbacks: List<(ScheduleViewModel, DataGeneration?) -> Unit> = listOf(
            { model, generation -> model.onTitleChanged("过期标题", generation) },
            { model, generation -> model.onAllDayChanged(true, generation) },
            { model, generation -> model.onStartChanged(Instant.EPOCH, generation) },
            { model, generation -> model.onEndChanged(Instant.EPOCH, generation) },
            { model, generation -> model.onAllDayDatesChanged(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2), generation) },
            { model, generation -> model.onLocationChanged("过期地点", generation) },
            { model, generation -> model.onParticipantsChanged("过期参与者", generation) },
            { model, generation -> model.onNoteChanged("过期备注", generation) },
            { model, generation -> model.onColorChanged(123, generation) },
            { model, generation -> model.onReminderChanged("99", generation) },
            { model, generation -> model.onRepeatRuleChanged(ScheduleRepeatRule.DAILY, generation) },
            { model, generation -> model.closeEditor(generation) },
        )
        callbacks.forEachIndexed { index, callback ->
            scenario { model, fixture ->
                model.openEditor(fixture.repo.rows.single())
                val editorGeneration = model.uiState.value.editor.generation
                val oldCallback = { callback(model, editorGeneration) }
                fixture.replace()
                advanceUntilIdle()
                model.openEditor(fixture.repo.rows.single())
                advanceUntilIdle()
                val fresh = model.uiState.value.editor
                oldCallback()
                advanceUntilIdle()
                assertEquals(fresh, model.uiState.value.editor, "Old editor callback $index changed the new draft")
            }
        }
    }

    @Test fun oldConflictConfirmationCannotConfirmANewConflictDialog() = runTest {
        scenario { model, fixture ->
            openConflictingDraft(model)
            model.saveSchedule()
            advanceUntilIdle()
            assertTrue(model.uiState.value.editor.awaitingConflictConfirmation)
            val editorGeneration = model.uiState.value.editor.generation
            val oldConfirm = { model.confirmSaveDespiteConflicts(editorGeneration) }
            fixture.replace()
            advanceUntilIdle()
            model.closeEditor()
            openConflictingDraft(model)
            model.saveSchedule()
            advanceUntilIdle()
            assertTrue(model.uiState.value.editor.awaitingConflictConfirmation)
            oldConfirm()
            advanceUntilIdle()
            assertEquals(0, fixture.repo.saves)
            assertTrue(model.uiState.value.editor.awaitingConflictConfirmation)
            assertEquals(1, fixture.repo.rows.size)
        }
    }

    @Test fun queuedNotificationCannotOpenSameIdFromReplacement() = runTest {
        scenario { model, fixture ->
            model.openNotificationDetail(42)
            fixture.replace()
            advanceUntilIdle()
            assertFalse(model.uiState.value.editor.isOpen)
            assertNull(model.uiState.value.editor.pendingNotificationId)
        }
    }

    @Test fun providedOldNotificationGenerationCannotOpenOrDeferReusedIdAfterUiRefresh() = runTest {
        scenario { model, fixture ->
            val originalDataGeneration = fixture.access.capture()
            assertEquals(DataGeneration(7), originalDataGeneration)
            fixture.replace()
            advanceUntilIdle()
            assertEquals(DataGeneration(8), model.uiState.value.generation)
            assertEquals("恢复的新记录", model.uiState.value.schedules.single().title)

            model.openNotificationDetail(42, originalDataGeneration)
            advanceUntilIdle()
            assertFalse(model.uiState.value.editor.isOpen)
            assertNull(model.uiState.value.editor.pendingNotificationId)

            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("新的未保存草稿")
            model.openNotificationDetail(42, originalDataGeneration)
            advanceUntilIdle()
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals("新的未保存草稿", model.uiState.value.editor.title)
            assertNull(model.uiState.value.editor.pendingNotificationId)
            model.closeEditor()
            advanceUntilIdle()
            assertFalse(model.uiState.value.editor.isOpen)
            assertNull(model.uiState.value.editor.pendingNotificationId)
            assertNull(model.uiState.value.errorMessage)
        }
    }

    @Test fun providedCurrentNotificationGenerationLoadsReplacementBeforeUiObservesCommit() = runTest {
        scenario { model, fixture ->
            assertEquals(DataGeneration(7), model.uiState.value.generation)
            fixture.replace()
            val originalDataGeneration = fixture.access.capture()
            assertEquals(DataGeneration(8), originalDataGeneration)
            // No dispatcher turn between commit and handoff: the UI still carries generation 7.
            assertEquals(DataGeneration(7), model.uiState.value.generation)
            fixture.notificationDispatcher.pauseNext = true
            model.openNotificationDetail(42, originalDataGeneration)
            assertTrue(fixture.notificationDispatcher.hasHeldPublication)
            advanceUntilIdle() // Observer and fresh snapshot finish before the held lookup starts.
            assertEquals(DataGeneration(8), model.uiState.value.generation)
            assertFalse(model.uiState.value.editor.isOpen)
            fixture.notificationDispatcher.resumeHeld()
            advanceUntilIdle()
            assertEquals(DataGeneration(8), model.uiState.value.generation)
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals(DataGeneration(8), model.uiState.value.editor.generation)
            assertEquals(42L, model.uiState.value.editor.editingId)
            assertEquals("恢复的新记录", model.uiState.value.editor.title)
            assertNull(model.uiState.value.editor.pendingNotificationId)
            assertNull(model.uiState.value.errorMessage)
        }
    }

    @Test fun providedCurrentNotificationSurvivesGenerationObserverArrivingAfterLookup() = runTest {
        val observations = MutableStateFlow(DataGeneration(7))
        scenario(generationObservations = observations) { model, fixture ->
            fixture.replace(notifyObservers = false)
            val originalDataGeneration = fixture.access.capture()
            assertEquals(DataGeneration(8), originalDataGeneration)
            assertEquals(DataGeneration(7), model.uiState.value.generation)
            model.openNotificationDetail(42, originalDataGeneration)
            advanceUntilIdle() // Lookup and Main delivery run while the observer still emits 7.
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals(DataGeneration(8), model.uiState.value.editor.generation)
            assertEquals(42L, model.uiState.value.editor.editingId)
            assertEquals("恢复的新记录", model.uiState.value.editor.title)

            observations.value = DataGeneration(8)
            advanceUntilIdle()
            assertEquals(DataGeneration(8), model.uiState.value.generation)
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals(DataGeneration(8), model.uiState.value.editor.generation)
            assertEquals(42L, model.uiState.value.editor.editingId)
            assertEquals("恢复的新记录", model.uiState.value.editor.title)
            assertNull(model.uiState.value.editor.pendingNotificationId)
            assertNull(model.uiState.value.errorMessage)
        }
    }

    @Test fun intermediateObservationCannotCancelAQueuedNewerGenerationNotification() = runTest {
        val observations = MutableStateFlow(DataGeneration(7))
        scenario(generationObservations = observations) { model, fixture ->
            fixture.replace(notifyObservers = false)
            fixture.replace(notifyObservers = false)
            val originalDataGeneration = fixture.access.capture()
            assertEquals(DataGeneration(9), originalDataGeneration)
            assertEquals(DataGeneration(7), model.uiState.value.generation)
            fixture.notificationDispatcher.pauseNext = true
            model.openNotificationDetail(42, originalDataGeneration)
            assertTrue(fixture.notificationDispatcher.hasHeldPublication)
            observations.value = DataGeneration(8)
            advanceUntilIdle()
            fixture.notificationDispatcher.resumeHeld()
            advanceUntilIdle()
            observations.value = DataGeneration(9)
            advanceUntilIdle()
            assertEquals(DataGeneration(9), model.uiState.value.generation)
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals(DataGeneration(9), model.uiState.value.editor.generation)
            assertEquals(42L, model.uiState.value.editor.editingId)
            assertNull(model.uiState.value.editor.pendingNotificationId)
        }
    }

    @Test fun notificationBeforeInitialSnapshotIsNotLost() = runTest {
        scenario(startLoaded = false) { model, _ ->
            model.openNotificationDetail(42)
            advanceUntilIdle()
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals(42L, model.uiState.value.editor.editingId)
            assertEquals("原记录", model.uiState.value.editor.title)
        }
    }

    @Test fun notificationBornBeforeInitialSnapshotCannotRetagReusedIdAfterReplacement() = runTest {
        scenario(startLoaded = false) { model, fixture ->
            assertNull(model.uiState.value.generation)
            model.openNotificationDetail(42)
            // Capture begins undispatched at arrival; the deferred lookup must keep generation 7.
            fixture.replace()
            advanceUntilIdle()
            assertEquals(DataGeneration(8), model.uiState.value.generation)
            assertEquals("恢复的新记录", model.uiState.value.schedules.single().title)
            assertFalse(model.uiState.value.editor.isOpen)
            assertNull(model.uiState.value.editor.pendingNotificationId)
        }
    }

    @Test fun saveDrainsPendingNotificationWithoutNestedGlobalAdmission() = runTest {
        scenario { model, fixture ->
            model.openEditor()
            model.onTitleChanged("保存后查看通知")
            model.onStartChanged(Instant.parse("2026-10-06T01:00:00Z"))
            model.onEndChanged(Instant.parse("2026-10-06T02:00:00Z"))
            model.openNotificationDetail(42)
            model.saveSchedule()
            advanceUntilIdle()
            assertEquals(1, fixture.repo.saves)
            assertEquals(2, fixture.repo.rows.size)
            assertEquals(42L, model.uiState.value.editor.editingId)
            assertNull(model.uiState.value.editor.pendingNotificationId)
            assertNull(model.uiState.value.errorMessage)
        }
    }

    @Test fun maintenanceDrainsAdmittedSaveIncludingMainPublicationAndReminders() = runTest {
        scenario { model, fixture ->
            val release = CompletableDeferred<Unit>()
            fixture.repo.saveRelease = release
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("已排空保存")
            model.saveSchedule()
            runCurrent()
            assertEquals(1, fixture.repo.saves)
            model.saveSchedule()
            model.closeEditor()
            val finish = CompletableDeferred<Unit>()
            var closedAtReady = false
            var remindersAtReady = emptyList<String>()
            val freeze = launch { fixture.coordinator.withSession {
                closedAtReady = !model.uiState.value.editor.isOpen
                remindersAtReady = fixture.alarms.events.toList()
                finish.await()
            } }
            try {
                runCurrent()
                assertEquals(MaintenanceState.DRAINING, fixture.coordinator.state.value)
                release.complete(Unit)
                runCurrent()
                assertEquals(MaintenanceState.READY, fixture.coordinator.state.value)
                assertTrue(closedAtReady)
                assertEquals(listOf("cancel:42", "schedule:42"), remindersAtReady)
                assertEquals(1, fixture.repo.saves)
            } finally {
                release.complete(Unit)
                finish.complete(Unit)
                freeze.cancel()
                freeze.join()
            }
        }
    }

    @Test fun globalAdmissionPrecedesModuleMutexWhileWaitingWriteDrains() = runTest {
        scenario { model, fixture ->
            val release = CompletableDeferred<Unit>()
            val moduleOwner = launch { ScheduleOperationCoordinator.run { release.await() } }
            runCurrent()
            model.deleteSchedule(42)
            runCurrent()
            val finish = CompletableDeferred<Unit>()
            val freeze = launch { fixture.coordinator.withSession { finish.await() } }
            try {
                runCurrent()
                assertEquals(MaintenanceState.DRAINING, fixture.coordinator.state.value)
                assertEquals(0, fixture.repo.deletes)
                release.complete(Unit)
                runCurrent()
                assertEquals(1, fixture.repo.deletes)
                assertEquals(MaintenanceState.READY, fixture.coordinator.state.value)
            } finally {
                release.complete(Unit)
                finish.complete(Unit)
                moduleOwner.cancel()
                freeze.cancel()
                moduleOwner.join()
                freeze.join()
            }
        }
    }

    @Test fun metadataReadFailureRejectsSaveAndReminderSideEffects() = runTest {
        scenario { model, fixture ->
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("不得写入")
            fixture.generations.currentFailure = IllegalStateException("synthetic missing metadata")
            model.saveSchedule()
            advanceUntilIdle()
            assertEquals(0, fixture.repo.saves)
            assertTrue(fixture.alarms.events.isEmpty())
            assertEquals("原记录", fixture.repo.rows.single().title)
            assertTrue(model.uiState.value.editor.isOpen)
            assertFalse(model.uiState.value.editor.isSaving)
        }
    }

    @Test fun metadataReadFailureRejectsDeleteAndReminderCancellation() = runTest {
        scenario { model, fixture ->
            fixture.generations.currentFailure = IllegalStateException("synthetic missing metadata")
            model.deleteSchedule(42)
            advanceUntilIdle()
            assertEquals(0, fixture.repo.deletes)
            assertEquals(42L, fixture.repo.rows.single().id)
            assertTrue(fixture.alarms.events.isEmpty())
        }
    }

    @Test fun unreadableInitialMetadataCannotPublishAnEditableSnapshotOrUseGenerationZero() = runTest {
        scenario(startLoaded = false) { model, fixture ->
            fixture.generations.currentFailure = IllegalStateException("synthetic missing metadata")
            advanceUntilIdle()
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("不能使用默认世代保存")
            model.saveSchedule()
            advanceUntilIdle()
            assertEquals(0, fixture.repo.reads)
            assertEquals(0, fixture.repo.saves)
            assertFalse(model.uiState.value.editor.isOpen)
            assertTrue(fixture.alarms.events.isEmpty())
        }
    }

    @Test fun oldMetadataFallbackCannotDisableFreshEditorAfterGenerationIsObserved() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val main = PausingMainDispatcher(dispatcher)
        Dispatchers.setMain(main)
        val fixture = Fixture()
        val model = ScheduleViewModel(fixture.repo, fixture.alarms, dispatcher, fixture.access)
        try {
            advanceUntilIdle()
            model.openEditor(fixture.repo.rows.single())
            fixture.generations.currentFailure = IllegalStateException("synthetic old metadata fault")
            main.pauseNext = true
            model.saveSchedule()
            advanceUntilIdle()
            assertTrue(main.hasHeldPublication)
            fixture.generations.currentFailure = null
            fixture.repo.readRelease = CompletableDeferred()
            fixture.replace()
            advanceUntilIdle()
            main.resumeHeld()
            advanceUntilIdle()
            fixture.repo.readRelease!!.complete(Unit)
            advanceUntilIdle()
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("新世代仍可保存")
            model.saveSchedule()
            advanceUntilIdle()
            assertEquals(1, fixture.repo.saves)
            assertEquals("新世代仍可保存", fixture.repo.rows.single().title)
        } finally {
            fixture.repo.readRelease?.complete(Unit)
            main.resumeHeld()
            model.viewModelScope.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test fun delayedGenerationObserverFaultCannotDisableFreshNotificationSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val main = PausingMainDispatcher(dispatcher)
        Dispatchers.setMain(main)
        val store = TestGenerations(7)
        val failObservation = CompletableDeferred<Unit>()
        val generations = object : DataGenerationRepository by store {
            override fun observe() = flow {
                emit(DataGeneration(7))
                failObservation.await()
                error("synthetic old generation observer fault")
            }
        }
        val coordinator = MaintenanceCoordinator(generations)
        val repository = Records().apply { readRelease = CompletableDeferred() }
        val model = ScheduleViewModel(repository, Alarms(), dispatcher, GenerationAccess(generations, coordinator))
        try {
            advanceUntilIdle() // Generation 7 observed; its first cold snapshot remains blocked.
            assertNull(model.uiState.value.generation)
            main.pauseNext = true
            failObservation.complete(Unit)
            advanceUntilIdle()
            assertTrue(main.hasHeldPublication)
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                store.commit(session.generation) { repository.rows = listOf(meeting().copy(title = "新通知目标")) }
            } }
            advanceUntilIdle()
            repository.readRelease!!.complete(Unit)
            model.openNotificationDetail(42) // Birth capture now legitimately obtains generation 8.
            advanceUntilIdle()
            assertEquals(DataGeneration(8), model.uiState.value.generation)
            assertTrue(model.uiState.value.isAvailable)
            assertEquals("新通知目标", model.uiState.value.editor.title)
            main.resumeHeld()
            advanceUntilIdle()
            assertTrue(model.uiState.value.isAvailable, "Old generation listener fault disabled generation 8")
            assertNull(model.uiState.value.errorMessage)
        } finally {
            failObservation.complete(Unit)
            repository.readRelease?.complete(Unit)
            main.resumeHeld()
            model.viewModelScope.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test fun observerPayloadIsOnlyInvalidationAndColdQuerySuppliesDisplayedData() = runTest {
        scenario { model, fixture ->
            fixture.repo.rows = listOf(fixture.repo.rows.single().copy(title = "冷查询中的真实记录"))
            fixture.repo.invalidation.value = listOf(fixture.repo.rows.single().copy(title = "长驻流中的旧缓存"))
            advanceUntilIdle()
            assertEquals("冷查询中的真实记录", model.uiState.value.schedules.single().title)
            assertTrue(fixture.repo.reads > 0)
        }
    }

    @Test fun briefBusyColdReadRecoversAndContinuesObserving() = runTest {
        scenario(startLoaded = false) { model, fixture ->
            fixture.generations.nextCurrentFailures.addLast(MaintenanceBusyException())
            advanceUntilIdle()
            assertEquals("原记录", model.uiState.value.schedules.single().title)
            fixture.repo.rows = listOf(fixture.repo.rows.single().copy(title = "后续冷查询"))
            fixture.repo.invalidate()
            advanceUntilIdle()
            assertEquals("后续冷查询", model.uiState.value.schedules.single().title)
            assertTrue(fixture.repo.reads >= 2)
        }
    }

    @Test fun cancelledFreezeResumesColdReadsWithoutReplayingOldObserverPayload() = runTest {
        scenario { model, fixture ->
            model.openEditor(fixture.repo.rows.single())
            model.onTitleChanged("冻结中保留的草稿")
            advanceUntilIdle()
            val readsBefore = fixture.repo.reads
            val freeze = launch { fixture.coordinator.withSession { awaitCancellation() } }
            runCurrent()
            fixture.repo.rows = listOf(fixture.repo.rows.single().copy(title = "解除冻结后真实数据"))
            fixture.repo.invalidation.value = listOf(fixture.repo.rows.single().copy(title = "被冻结的过期流数据"))
            runCurrent()
            assertEquals(readsBefore, fixture.repo.reads)
            freeze.cancel()
            freeze.join()
            advanceUntilIdle()
            assertEquals("解除冻结后真实数据", model.uiState.value.schedules.single().title)
            assertEquals("冻结中保留的草稿", model.uiState.value.editor.title)
            assertTrue(model.uiState.value.editor.isOpen)
            assertTrue(fixture.repo.reads > readsBefore)
        }
    }

    private suspend fun TestScope.scenario(
        startLoaded: Boolean = true,
        generationObservations: Flow<DataGeneration>? = null,
        body: suspend TestScope.(ScheduleViewModel, Fixture) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = Fixture(generationObservations)
        fixture.notificationDispatcher = PausingMainDispatcher(dispatcher)
        val model = ScheduleViewModel(fixture.repo, fixture.alarms, fixture.notificationDispatcher, fixture.access)
        try {
            if (startLoaded) advanceUntilIdle()
            body(model, fixture)
        } finally {
            model.viewModelScope.cancel()
            fixture.notificationDispatcher.resumeHeld()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(generationObservations: Flow<DataGeneration>? = null) {
        val generations = TestGenerations(7)
        private val generationRepository = object : DataGenerationRepository by generations {
            override fun observe() = generationObservations ?: generations.observe()
        }
        val coordinator = MaintenanceCoordinator(generationRepository)
        val access = GenerationAccess(generationRepository, coordinator)
        val repo = Records()
        val alarms = Alarms()
        lateinit var notificationDispatcher: PausingMainDispatcher

        suspend fun replace(notifyObservers: Boolean = true) {
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                generations.commit(session.generation) {
                    repo.rows = listOf(meeting().copy(title = "恢复的新记录"))
                    if (notifyObservers) repo.invalidate()
                }
            } }
        }
    }

    private class Records : ScheduleRepository {
        var rows = listOf(meeting())
        val invalidation = MutableStateFlow(rows)
        var reads = 0
        var saves = 0
        var deletes = 0
        var readRelease: CompletableDeferred<Unit>? = null
        var saveRelease: CompletableDeferred<Unit>? = null
        override fun observeSchedules() = invalidation
        override suspend fun getSchedules(): List<Schedule> {
            reads++
            readRelease?.await()
            return rows
        }
        override suspend fun getExceptions(scheduleId: Long) = emptyList<ScheduleException>()
        override suspend fun saveException(exception: ScheduleException): Unit = error("Unused")
        override suspend fun saveSchedule(schedule: Schedule): Long {
            saves++
            saveRelease?.await()
            val id = if (schedule.id == 0L) (rows.maxOfOrNull { it.id } ?: 0L) + 1 else schedule.id
            rows = rows.filterNot { it.id == id } + schedule.copy(id = id)
            invalidate()
            return id
        }
        override suspend fun deleteSchedule(id: Long) {
            deletes++
            rows = rows.filterNot { it.id == id }
            invalidate()
        }
        fun invalidate() { invalidation.value = rows }
    }

    private class Alarms : ScheduleReminderSchedulerContract {
        val events = mutableListOf<String>()
        override fun schedule(schedule: Schedule) { events += "schedule:${schedule.id}" }
        override fun cancel(scheduleId: Long) { events += "cancel:$scheduleId" }
    }

    private fun openConflictingDraft(model: ScheduleViewModel) {
        model.openEditor()
        model.onTitleChanged("重叠的新草稿")
        model.onStartChanged(Instant.parse("2026-10-05T01:30:00Z"))
        model.onEndChanged(Instant.parse("2026-10-05T02:30:00Z"))
    }

    companion object {
        private fun meeting() = Schedule(
            id = 42,
            title = "原记录",
            startAt = Instant.parse("2026-10-05T01:00:00Z"),
            endAt = Instant.parse("2026-10-05T02:00:00Z"),
            timeZone = "Asia/Shanghai",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
    }
}
