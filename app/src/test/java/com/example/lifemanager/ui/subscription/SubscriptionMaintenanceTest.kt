package com.example.lifemanager.ui.subscription

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import com.example.lifemanager.domain.repository.SettingsRepository
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import java.time.Instant
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the production consumer with the same generation and maintenance instances as its fixture. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionMaintenanceTest {
    @Test fun busySubscriptionSaveIsRejectedWithoutReplayingAfterMaintenanceCancellation() = scenario {
        subscriptionDraft()
        val draft = model.uiState.value.editor
        freeze {
            model.saveSubscription()
            runCurrent()
        }
        advanceUntilIdle()
        assertEquals("original", repository.subscriptions.first { it.id == 1L }.appName)
        assertEquals(0, repository.subscriptionWrites)
        assertEquals(draft.name, model.uiState.value.editor.name)
        assertTrue(model.uiState.value.editor.isOpen)
        assertFalse(model.uiState.value.editor.isSaving)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test fun busyPaymentSaveIsRejectedWithoutReplayingAfterMaintenanceCancellation() = scenario {
        paymentDraft()
        freeze {
            model.savePayment()
            runCurrent()
        }
        advanceUntilIdle()
        assertEquals(1200L, repository.payments.single().amountMinor)
        assertEquals(0, repository.paymentWrites)
        assertEquals("23.45", model.uiState.value.paymentEditor.amount)
        assertTrue(model.uiState.value.paymentEditor.isOpen)
        assertFalse(model.uiState.value.paymentEditor.isSaving)
    }

    @Test fun busyCancellationNeverWritesOrReconcilesReminders() = busyMutation { cancelSubscription(1) }
    @Test fun busyReactivationNeverWritesOrReconcilesReminders() = busyMutation(active = false) { restoreSubscription(1) }
    @Test fun busySubscriptionDeletionNeverWritesOrCancelsReminders() = busyMutation { deleteSubscription(1) }
    @Test fun busyPaymentDeletionNeverWrites() = busyMutation { deletePayment(1) }

    private fun busyMutation(active: Boolean = true, mutate: SubscriptionViewModel.() -> Unit) = scenario(
        configure = { repository.subscriptions = repository.subscriptions.map { it.copy(isActive = active, cancelDate = if (active) null else LocalDate.now()) } },
    ) {
        val subscriptions = repository.subscriptions
        val payments = repository.payments
        freeze { model.mutate(); runCurrent() }
        advanceUntilIdle()
        assertEquals(subscriptions, repository.subscriptions)
        assertEquals(payments, repository.payments)
        assertEquals(0, repository.totalWrites)
        assertTrue(scheduler.cancelled.isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test fun cancelledMaintenancePreservesBothDraftsAndLatestDeferredNotification() = scenario {
        subscriptionDraft()
        paymentDraft()
        model.openNotificationDetail(2)
        advanceUntilIdle()
        val editor = model.uiState.value.editor
        val payment = model.uiState.value.paymentEditor
        freeze {
            assertEquals(editor, model.uiState.value.editor)
            assertEquals(payment, model.uiState.value.paymentEditor)
            assertEquals(2L, model.uiState.value.pendingNotificationId)
        }
        advanceUntilIdle()
        assertEquals(editor, model.uiState.value.editor)
        assertEquals(payment, model.uiState.value.paymentEditor)
        model.closeEditor()
        model.closePaymentEditor()
        advanceUntilIdle()
        assertEquals(2L, model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
    }

    @Test fun generationReplacementClearsBothDraftsAndDeferredNotificationEvenWhenIdsAreReused() = scenario {
        subscriptionDraft()
        paymentDraft()
        model.openNotificationDetail(2)
        advanceUntilIdle()
        replace()
        advanceUntilIdle()
        assertEquals("replacement", model.uiState.value.subscriptions.first { it.id == 1L }.appName)
        assertFalse(model.uiState.value.editor.isOpen)
        assertFalse(model.uiState.value.paymentEditor.isOpen)
        assertNull(model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
        model.closeEditor()
        model.closePaymentEditor()
        advanceUntilIdle()
        assertNull(model.uiState.value.detailId)
    }

    @Test fun reusedIdCannotReopenAnEditorFromAnOldSubscriptionObject() = scenario {
        val oldRow = repository.subscriptions.first()
        replace()
        advanceUntilIdle()
        model.openEditor(oldRow)
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
    }

    @Test fun queuedSubscriptionSaveKeepsArrivalGenerationInsteadOfWritingIntoReplacement() = scenario {
        subscriptionDraft()
        model.saveSubscription() // IO dispatcher has not run yet.
        replace()
        advanceUntilIdle()
        assertEquals("replacement", repository.subscriptions.first { it.id == 1L }.appName)
        assertEquals(0, repository.subscriptionWrites)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test fun queuedPaymentSaveKeepsDraftGenerationInsteadOfUpdatingReusedPaymentId() = scenario {
        paymentDraft()
        model.savePayment()
        replace()
        advanceUntilIdle()
        assertEquals(2700L, repository.payments.single().amountMinor)
        assertEquals(0, repository.paymentWrites)
    }

    @Test fun queuedCancellationCannotChangeReplacementLifecycle() = queuedMutation { cancelSubscription(1) }
    @Test fun queuedReactivationCannotChangeReplacementLifecycle() = queuedMutation(active = false) { restoreSubscription(1) }
    @Test fun queuedSubscriptionDeletionCannotDeleteReusedId() = queuedMutation { deleteSubscription(1) }
    @Test fun queuedPaymentDeletionCannotDeleteReusedId() = queuedMutation { deletePayment(1) }

    private fun queuedMutation(active: Boolean = true, mutate: SubscriptionViewModel.() -> Unit) = scenario {
        model.mutate()
        replace(active)
        advanceUntilIdle()
        assertEquals(2, repository.subscriptions.size)
        assertEquals(active, repository.subscriptions.first { it.id == 1L }.isActive)
        // RED correction: an unguarded delete must fail this assertion rather than single() throwing.
        assertEquals(listOf(1L), repository.payments.map { it.id })
        assertEquals(2700L, repository.payments.single().amountMinor)
        assertEquals(0, repository.totalWrites)
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test fun admittedSubscriptionWriteAndReminderWorkDrainBeforeMaintenanceBecomesReady() = scenario {
        subscriptionDraft()
        val finishWrite = CompletableDeferred<Unit>()
        repository.beforeSubscriptionWrite = { finishWrite.await() }
        model.saveSubscription()
        runCurrent()
        val ready = CompletableDeferred<Unit>()
        val finishSession = CompletableDeferred<Unit>()
        val owner = trackedLaunch {
            coordinator.withSession {
                ready.complete(Unit)
                finishSession.await()
            }
        }
        runCurrent()
        try {
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
            assertFalse(ready.isCompleted)
            finishWrite.complete(Unit)
            runCurrent()
            assertTrue(ready.isCompleted)
            assertEquals("draft", repository.subscriptions.first { it.id == 1L }.appName)
            assertEquals(setOf(1, 7), scheduler.scheduled.single().second)
            assertFalse(model.uiState.value.editor.isOpen)
        } finally {
            finishWrite.complete(Unit)
            finishSession.complete(Unit)
            owner.cancel()
        }
    }

    @Test fun globalAdmissionPrecedesWaitingForSubscriptionModuleMutex() = scenario {
        val releaseModule = CompletableDeferred<Unit>()
        val holder = trackedLaunch { SubscriptionOperationCoordinator.run { releaseModule.await() } }
        runCurrent()
        model.deletePayment(1)
        runCurrent() // A correct consumer holds global admission while waiting for the module lock.
        val ready = CompletableDeferred<Unit>()
        val finishSession = CompletableDeferred<Unit>()
        val owner = trackedLaunch { coordinator.withSession { ready.complete(Unit); finishSession.await() } }
        runCurrent()
        try {
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
            assertFalse(ready.isCompleted)
            releaseModule.complete(Unit)
            runCurrent()
            assertTrue(ready.isCompleted)
            assertTrue(repository.payments.isEmpty())
        } finally {
            releaseModule.complete(Unit)
            finishSession.complete(Unit)
            holder.cancel()
            owner.cancel()
        }
    }

    @Test fun unreadGenerationDisablesInitialSnapshotAndCsvInsteadOfAssumingGenerationZero() = scenario(
        configure = { generations.currentFailure = IllegalStateException("missing metadata") },
    ) {
        assertFalse(model.uiState.value.hasSubscriptionData)
        assertFalse(model.uiState.value.hasPaymentData)
        assertNull(model.prepareCsvExport())
        assertNotNull(model.uiState.value.errorMessage)
    }

    @Test fun metadataFailureBeforeMutationDoesNotWriteAndDisablesCsvUntilFreshRead() = scenario {
        generations.currentFailure = IllegalStateException("metadata unavailable")
        model.deleteSubscription(1)
        advanceUntilIdle()
        assertEquals(2, repository.subscriptions.size)
        assertEquals(0, repository.totalWrites)
        assertNull(model.prepareCsvExport())
        assertNotNull(model.uiState.value.errorMessage)
        generations.currentFailure = null
        model.retry()
        advanceUntilIdle()
        assertNotNull(model.prepareCsvExport())
    }

    @Test fun metadataFailureAtStartupCanRecoverOnExplicitRetry() = scenario(
        configure = { generations.currentFailure = IllegalStateException("missing metadata") },
    ) {
        assertNull(model.prepareCsvExport())
        generations.currentFailure = null
        model.retry()
        advanceUntilIdle()
        assertEquals("original", model.uiState.value.subscriptions.first { it.id == 1L }.appName)
        assertNotNull(model.prepareCsvExport())
    }

    @Test fun briefBusyReadResumesEvenWhenMaintenanceHasAlreadyReturnedToIdle() = scenario {
        // No new maintenance StateFlow emission follows this rejected admission.
        generations.nextCurrentFailures.addLast(MaintenanceBusyException())
        repository.subscriptions = repository.subscriptions.map { if (it.id == 1L) it.copy(appName = "fresh after busy") else it }
        repository.observerSubscriptions = listOf(repository.subscriptions.first().copy(appName = "cached observer"), repository.subscriptions.last())
        repository.invalidate()
        advanceUntilIdle()
        assertEquals("fresh after busy", model.uiState.value.subscriptions.first { it.id == 1L }.appName)
        assertNotNull(model.prepareCsvExport())
    }

    @Test fun cancelledMaintenanceRestartsColdReadsWithoutAnotherRepositoryInvalidation() = scenario {
        repository.observerSubscriptions = repository.subscriptions
        val readsBefore = repository.subscriptionColdReads
        freeze { /* The existing observer is frozen without changing its invalidation counter. */ }
        advanceUntilIdle()
        assertTrue(repository.subscriptionColdReads > readsBefore)
        assertEquals("original", model.uiState.value.subscriptions.first { it.id == 1L }.appName)
        assertNotNull(model.prepareCsvExport())
    }

    @Test fun oldErrorMetadataFallbackCannotDisableAReplacementSnapshot() = scenario {
        val publicationStarted = CompletableDeferred<Unit>()
        repository.reminderRead = {
            generationRead = {
                generationRead = null // Only the old error publication fails; fresh readers use persisted metadata.
                publicationStarted.complete(Unit)
                // Admission ends on the exception. Hold only the later Main fallback, not a live permit.
                mainDispatcher.holdNext = true
                error("old metadata lookup failed")
            }
            error("old reminder lookup failed")
        }
        model.openDetail(1)
        runCurrent()
        try {
            assertTrue(publicationStarted.isCompleted, "Reminder error publication must validate its original generation")
            repository.reminderRead = null
            replace()
            advanceUntilIdle()
            assertEquals("replacement", model.uiState.value.subscriptions.first { it.id == 1L }.appName)
            mainDispatcher.release()
            advanceUntilIdle()
            assertNotNull(model.prepareCsvExport())
            assertNull(model.uiState.value.errorMessage)
            assertNull(model.uiState.value.detailId)
        } finally { mainDispatcher.release() }
    }

    @Test fun observerValuesOnlyInvalidateAndFreshColdQueriesSupplySubscriptionsAndPayments() = scenario {
        repository.observerSubscriptions = repository.subscriptions
        repository.observerPayments = repository.payments
        repository.replaceRows()
        repository.invalidate() // Long-lived observers emit old DTOs; new cold queries read replacement rows.
        advanceUntilIdle()
        assertEquals("replacement", model.uiState.value.subscriptions.first { it.id == 1L }.appName)
        assertEquals(2700L, model.uiState.value.payments.single().amountMinor)
        assertTrue(repository.subscriptionColdReads > 1)
        assertTrue(repository.paymentColdReads > 1)
    }

    @Test fun finiteDefaultsReadDrainsBeforeMaintenanceAndCannotInitializeSettingsOutsidePermit() = scenario {
        val defaults = CompletableDeferred<AppSettings>()
        preferences.read = { defaults.await() }
        model.openEditor()
        runCurrent()
        val ready = CompletableDeferred<Unit>()
        val finishSession = CompletableDeferred<Unit>()
        val owner = trackedLaunch { coordinator.withSession { ready.complete(Unit); finishSession.await() } }
        runCurrent()
        try {
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
            assertFalse(ready.isCompleted)
            defaults.complete(AppSettings(defaultCurrency = "EUR", defaultReminderDays = setOf(7)))
            runCurrent()
            assertTrue(ready.isCompleted)
            assertEquals("EUR", model.uiState.value.editor.currency)
            assertEquals(setOf(7), model.uiState.value.editor.reminderDays)
        } finally {
            defaults.complete(AppSettings())
            finishSession.complete(Unit)
            owner.cancel()
        }
    }

    @Test fun busyNewEditorDoesNotRunSideEffectfulDefaultRead() = scenario {
        freeze { model.openEditor(); runCurrent() }
        advanceUntilIdle()
        assertEquals(0, preferences.reads)
        assertFalse(model.uiState.value.editor.isOpen)
    }

    @Test fun coldStartNotificationArrivingBeforeSnapshotStillOpensRequestedDetail() = scenario(startLoaded = false) {
        model.openNotificationDetail(2)
        advanceUntilIdle()
        assertEquals(2L, model.uiState.value.detailId)
        assertEquals(setOf(7), model.uiState.value.detailReminderDays)
        assertNull(model.uiState.value.pendingNotificationId)
    }

    @Test fun coldStartNotificationCannotRecaptureReplacementGenerationBeforeFirstSnapshotLoads() = scenario(startLoaded = false) {
        assertNull(model.uiState.value.generation)
        model.openNotificationDetail(2)
        replace() // No dispatcher pump between callback birth and replacement.
        advanceUntilIdle()
        assertEquals("replacement target", model.uiState.value.subscriptions.first { it.id == 2L }.appName)
        assertNull(model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
    }

    @Test fun oldRenderedCallbacksCannotChangeFreshDraftOrReusedRows() = scenario {
        subscriptionDraft()
        paymentDraft()
        val pageGeneration = model.uiState.value.generation
        val editorGeneration = model.uiState.value.editor.generation
        val paymentGeneration = model.uiState.value.paymentEditor.generation
        replace()
        advanceUntilIdle()
        subscriptionDraft()
        paymentDraft()
        model.updateEditor(editorGeneration) { it.copy(name = "old callback") }
        model.updatePaymentEditor(paymentGeneration) { it.copy(amount = "99.99") }
        model.closeEditor(editorGeneration)
        model.closePaymentEditor(paymentGeneration)
        model.saveSubscription(editorGeneration)
        model.savePayment(paymentGeneration)
        model.deleteSubscription(1, pageGeneration)
        model.deletePayment(1, pageGeneration)
        model.cancelSubscription(1, pageGeneration)
        model.restoreSubscription(1, pageGeneration)
        model.openDetail(1, pageGeneration)
        advanceUntilIdle()
        assertEquals("draft", model.uiState.value.editor.name)
        assertEquals("23.45", model.uiState.value.paymentEditor.amount)
        assertTrue(model.uiState.value.editor.isOpen)
        assertTrue(model.uiState.value.paymentEditor.isOpen)
        assertNull(model.uiState.value.detailId)
        assertEquals(0, repository.totalWrites)
        assertEquals(listOf(1L), repository.payments.map { it.id })
        assertEquals("replacement", repository.subscriptions.first { it.id == 1L }.appName)
    }

    @Test fun queuedNotificationKeepsArrivalGenerationAndCannotTargetReplacementWithSameId() = scenario {
        model.openNotificationDetail(2)
        replace()
        advanceUntilIdle()
        assertNull(model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
        assertTrue(model.uiState.value.detailReminderDays.isEmpty())
    }

    @Test fun notificationLookupDrainsWithItsPublicationBeforeReplacementIsAllowed() = scenario {
        val days = CompletableDeferred<Set<Int>>()
        repository.reminderRead = { days.await() }
        model.openNotificationDetail(2)
        runCurrent()
        val ready = CompletableDeferred<Unit>()
        val finishSession = CompletableDeferred<Unit>()
        val owner = trackedLaunch { coordinator.withSession { ready.complete(Unit); finishSession.await() } }
        runCurrent()
        try {
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
            assertFalse(ready.isCompleted)
            days.complete(setOf(1, 7))
            runCurrent()
            assertTrue(ready.isCompleted)
            assertEquals(2L, model.uiState.value.detailId)
            assertEquals(setOf(1, 7), model.uiState.value.detailReminderDays)
        } finally {
            days.complete(emptySet())
            finishSession.complete(Unit)
            owner.cancel()
        }
    }

    @Test fun latestNotificationRequestWinsWhenEarlierLookupCompletesLater() = scenario {
        val earlier = CompletableDeferred<Set<Int>>()
        repository.reminderRead = { id -> if (id == 1L) earlier.await() else setOf(7) }
        model.openNotificationDetail(1)
        runCurrent()
        model.openNotificationDetail(2)
        runCurrent()
        earlier.complete(setOf(1))
        advanceUntilIdle()
        assertEquals(2L, model.uiState.value.detailId)
        assertEquals(setOf(7), model.uiState.value.detailReminderDays)
    }

    @Test fun deferredNotificationAfterSaveDoesNotReenterGlobalPermit() = scenario {
        subscriptionDraft()
        model.openNotificationDetail(2)
        advanceUntilIdle()
        model.saveSubscription()
        advanceUntilIdle()
        assertEquals("draft", repository.subscriptions.first { it.id == 1L }.appName)
        assertEquals(2L, model.uiState.value.detailId)
        assertEquals(setOf(7), model.uiState.value.detailReminderDays)
        assertNull(model.uiState.value.pendingNotificationId)
        assertNull(model.uiState.value.errorMessage)
    }

    @Test fun preparedCsvAndLatePickerCallbackCannotExportOldTextAfterReplacement() = scenario {
        val oldCsv = assertNotNull(model.prepareCsvExport())
        assertTrue(oldCsv.contains("original"))
        replace()
        advanceUntilIdle()
        var written: String? = null
        model.completeCsvExport { written = it }
        advanceUntilIdle()
        assertNull(written)
        assertFalse(model.uiState.value.isExportPending)
        assertNull(model.uiState.value.exportMessage)
        val fresh = assertNotNull(model.prepareCsvExport())
        assertTrue(fresh.contains("replacement"))
        assertFalse(fresh.contains("original"))
    }

    @Test fun oldPickerResultAndCancellationCannotConsumeANewPreparedCsvRequest() = scenario {
        assertNotNull(model.prepareCsvExport())
        val oldGeneration = model.uiState.value.generation
        val oldRequest = model.uiState.value.exportRequestId
        replace()
        advanceUntilIdle()
        assertTrue(assertNotNull(model.prepareCsvExport()).contains("replacement"))
        val freshGeneration = model.uiState.value.generation
        val freshRequest = model.uiState.value.exportRequestId
        var written: String? = null
        model.cancelCsvExport(oldGeneration, oldRequest)
        model.completeCsvExport(oldGeneration, oldRequest) { written = it }
        advanceUntilIdle()
        assertNull(written)
        assertEquals(freshRequest, model.uiState.value.exportRequestId)
        assertTrue(model.uiState.value.isExportPending)
        model.completeCsvExport(freshGeneration, freshRequest) { written = it }
        advanceUntilIdle()
        assertTrue(assertNotNull(written).contains("replacement"))
    }

    @Test fun oldCsvWriterResultCannotPublishSuccessIntoReplacementPage() = scenario {
        assertNotNull(model.prepareCsvExport())
        val finish = CompletableDeferred<Unit>()
        model.completeCsvExport { finish.await() }
        runCurrent()
        assertTrue(model.uiState.value.isExporting)
        replace()
        advanceUntilIdle()
        finish.complete(Unit)
        advanceUntilIdle()
        assertNull(model.uiState.value.exportMessage)
        assertFalse(model.uiState.value.isExporting)
        assertTrue(assertNotNull(model.prepareCsvExport()).contains("replacement"))
    }

    @Test fun oldCsvWriterFailureCannotPublishErrorIntoReplacementPage() = scenario {
        assertNotNull(model.prepareCsvExport())
        val finish = CompletableDeferred<Unit>()
        model.completeCsvExport { finish.await(); error("old provider failed") }
        runCurrent()
        replace()
        advanceUntilIdle()
        finish.complete(Unit)
        advanceUntilIdle()
        assertNull(model.uiState.value.exportMessage)
        assertFalse(model.uiState.value.isExporting)
    }

    private fun scenario(
        startLoaded: Boolean = true,
        configure: Fixture.() -> Unit = {},
        block: suspend Fixture.() -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mainDispatcher = PublicationDispatcher(dispatcher)
        Dispatchers.setMain(mainDispatcher)
        val fixture = Fixture(this, mainDispatcher)
        fixture.configure()
        fixture.model = SubscriptionViewModel(fixture.repository, fixture.scheduler, fixture.preferences, dispatcher, fixture.access)
        try {
            if (startLoaded) advanceUntilIdle()
            fixture.block()
        } finally {
            fixture.model.viewModelScope.cancel()
            fixture.jobs.forEach { it.cancel() }
            mainDispatcher.release()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private class PublicationDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var holdNext = false
        private var held: Pair<CoroutineContext, Runnable>? = null
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holdNext) {
                holdNext = false
                check(held == null)
                held = context to block
            } else delegate.dispatch(context, block)
        }
        fun release() {
            val publication = held ?: return
            held = null
            delegate.dispatch(publication.first, publication.second)
        }
    }

    private class Fixture(val scope: TestScope, val mainDispatcher: PublicationDispatcher) {
        val generations = TestGenerations(11)
        var generationRead: (suspend () -> DataGeneration)? = null
        private val generationRepository = object : DataGenerationRepository by generations {
            override suspend fun current(): DataGeneration = generationRead?.invoke() ?: generations.current()
        }
        val coordinator = MaintenanceCoordinator(generationRepository)
        val access = GenerationAccess(generationRepository, coordinator)
        val repository = Repository()
        val preferences = Preferences()
        val scheduler = Scheduler()
        val jobs = mutableListOf<Job>()
        lateinit var model: SubscriptionViewModel
        fun runCurrent() = scope.runCurrent()
        fun advanceUntilIdle() = scope.advanceUntilIdle()
        fun trackedLaunch(block: suspend () -> Unit): Job = scope.launch { block() }.also { jobs += it }

        suspend fun subscriptionDraft() {
            model.openEditor(repository.subscriptions.first())
            advanceUntilIdle()
            model.updateEditor { it.copy(name = "draft", amount = "23.45", reminderDays = setOf(1, 7)) }
            advanceUntilIdle()
        }

        fun paymentDraft() {
            model.openPaymentEditor(1, repository.payments.single())
            model.updatePaymentEditor { it.copy(amount = "23.45", note = "payment draft") }
        }

        suspend fun freeze(block: suspend () -> Unit) {
            coordinator.withSession { runCurrent(); block() }
        }

        suspend fun replace(active: Boolean = true) {
            coordinator.withSession { session ->
                coordinator.withMaintenance(session) {
                    generations.commit(session.generation) { repository.replaceRows(active); repository.invalidate() }
                }
            }
        }
    }

    private class Preferences : SettingsRepository {
        private val settings = MutableStateFlow(AppSettings())
        var reads = 0
        var read: (suspend () -> AppSettings)? = null
        override fun observeSettings() = settings
        override suspend fun getSettings(): AppSettings { reads++; return read?.invoke() ?: settings.value }
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
    }

    private class Scheduler : SubscriptionReminderSchedulerContract {
        val cancelled = mutableListOf<Long>()
        val scheduled = mutableListOf<Pair<Subscription, Set<Int>>>()
        override fun cancelAll(subscriptionId: Long) { cancelled += subscriptionId }
        override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) { cancelled += subscriptionId }
        override fun schedule(subscription: Subscription, reminderDays: Set<Int>) { scheduled += subscription to reminderDays }
    }

    private class Repository : SubscriptionRepository {
        private val row = Subscription(id = 1, appName = "original", amountMinor = 1500, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.now().plusDays(10), startDate = LocalDate.now().minusMonths(1),
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
        var subscriptions = listOf(row, row.copy(id = 2, appName = "notification"))
        var payments = listOf(SubscriptionPayment(1, 1, 1200, "CNY", LocalDate.now()))
        private var reminders = mapOf(1L to setOf(3), 2L to setOf(7))
        private val invalidations = MutableStateFlow(0)
        var observerSubscriptions: List<Subscription>? = null
        var observerPayments: List<SubscriptionPayment>? = null
        var subscriptionColdReads = 0
        var paymentColdReads = 0
        var subscriptionWrites = 0
        var paymentWrites = 0
        var deletions = 0
        val totalWrites get() = subscriptionWrites + paymentWrites + deletions
        var beforeSubscriptionWrite: (suspend () -> Unit)? = null
        var reminderRead: (suspend (Long) -> Set<Int>)? = null

        fun replaceRows(active: Boolean = true) {
            subscriptions = listOf(row.copy(appName = "replacement", amountMinor = 2900, isActive = active,
                cancelDate = if (active) null else LocalDate.now()), row.copy(id = 2, appName = "replacement target"))
            payments = listOf(SubscriptionPayment(1, 1, 2700, "CNY", LocalDate.now(), "replacement payment"))
            reminders = mapOf(1L to setOf(1), 2L to setOf(3))
        }

        fun invalidate() { invalidations.value++ }
        override fun observeSubscriptions(): Flow<List<Subscription>> = flow {
            subscriptionColdReads++
            emit(subscriptions.toList())
            invalidations.drop(1).collect { emit(observerSubscriptions ?: subscriptions.toList()) }
        }
        override fun observeAllPayments(): Flow<List<SubscriptionPayment>> = flow {
            paymentColdReads++
            emit(payments.toList())
            invalidations.drop(1).collect { emit(observerPayments ?: payments.toList()) }
        }
        override fun observePayments(subscriptionId: Long): Flow<List<SubscriptionPayment>> = flow {
            emit(payments.filter { it.subscriptionId == subscriptionId })
        }
        override fun observeReminders(subscriptionId: Long): Flow<Set<Int>> = flow { emit(reminders[subscriptionId].orEmpty()) }
        override suspend fun getSubscriptions(): List<Subscription> { subscriptionColdReads++; return subscriptions.toList() }
        override suspend fun getSubscription(id: Long) = subscriptions.firstOrNull { it.id == id }
        override suspend fun getReminderDays(subscriptionId: Long) = reminderRead?.invoke(subscriptionId) ?: reminders[subscriptionId].orEmpty()
        override suspend fun saveSubscription(subscription: Subscription, reminderDays: Set<Int>): Long {
            beforeSubscriptionWrite?.invoke()
            subscriptionWrites++
            val id = subscription.id.takeIf { it != 0L } ?: 3L
            subscriptions = subscriptions.filterNot { it.id == id } + subscription.copy(id = id)
            reminders = reminders + (id to reminderDays)
            invalidate()
            return id
        }
        override suspend fun savePayment(payment: SubscriptionPayment): Long {
            paymentWrites++
            val id = payment.id.takeIf { it != 0L } ?: 2L
            payments = payments.filterNot { it.id == id } + payment.copy(id = id)
            invalidate()
            return id
        }
        override suspend fun deletePayment(id: Long) {
            deletions++
            payments = payments.filterNot { it.id == id }
            invalidate()
        }
        override suspend fun deleteSubscription(id: Long) {
            deletions++
            subscriptions = subscriptions.filterNot { it.id == id }
            payments = payments.filterNot { it.subscriptionId == id }
            reminders = reminders - id
            invalidate()
        }
    }
}
