package com.example.lifemanager.ui.subscription

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.repository.SettingsRepository
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionViewModelTest {
    @Test fun newSubscriptionUsesSavedCurrencyAndReminderCombination() {
        val preferences = Preferences(AppSettings(defaultCurrency = "EUR", defaultReminderDays = setOf(1, 7)))
        scenario(preferences = preferences) { model, repository, _ ->
            model.openEditor()
            advanceUntilIdle()
            assertEquals("EUR", model.uiState.value.editor.currency)
            assertEquals(setOf(1, 7), model.uiState.value.editor.reminderDays)
            model.updateEditor { it.copy(name = "新订阅", amount = "12.50") }
            model.saveSubscription()
            advanceUntilIdle()
            assertEquals("EUR", repository.subscriptions.value.single().currency)
            assertEquals(1250L, repository.subscriptions.value.single().amountMinor)
        }
    }

    @Test fun editingExistingSubscriptionIgnoresChangedDefaults() {
        val preferences = Preferences(AppSettings(defaultCurrency = "EUR", defaultReminderDays = setOf(1, 7)))
        scenario(preferences = preferences) { model, repository, _ ->
            repository.seed()
            advanceUntilIdle()
            model.openEditor(repository.subscriptions.value.single())
            advanceUntilIdle()
            assertEquals("CNY", model.uiState.value.editor.currency)
            assertEquals(setOf(3), model.uiState.value.editor.reminderDays)
        }
    }

    @Test fun unreadDefaultsDisableSavingAndPreserveTheNewSubscriptionDraft() {
        val preferences = Preferences().apply { read = { error("读取偏好失败") } }
        scenario(preferences = preferences) { model, repository, _ ->
            model.openEditor()
            model.updateEditor { it.copy(name = "保留草稿", amount = "12.50") }
            advanceUntilIdle()
            model.saveSubscription()
            advanceUntilIdle()
            assertTrue(repository.subscriptions.value.isEmpty())
            assertEquals("保留草稿", model.uiState.value.editor.name)
            assertFalse(model.uiState.value.editor.isLoadingReminders)
            assertNotNull(model.uiState.value.editor.preferencesError)
        }
    }

    @Test fun editingAfterAReadFailureKeepsTheRecoveryMessageAndDoesNotWrite() {
        val preferences = Preferences().apply { read = { error("读取偏好失败") } }
        scenario(preferences = preferences) { model, repository, _ ->
            model.openEditor()
            advanceUntilIdle()
            model.updateEditor { it.copy(name = "失败后继续输入", amount = "12.50") }
            model.saveSubscription()
            advanceUntilIdle()
            assertTrue(repository.subscriptions.value.isEmpty())
            assertFalse(model.uiState.value.editor.isLoadingReminders)
            assertNotNull(model.uiState.value.editor.preferencesError)
        }
    }

    @Test fun lateDefaultsCannotOverrideAnotherEditorSession() {
        val gate = CompletableDeferred<AppSettings>()
        val preferences = Preferences().apply { read = { gate.await() } }
        scenario(preferences = preferences) { model, repository, _ ->
            repository.seed()
            advanceUntilIdle()
            model.openEditor()
            runCurrent()
            model.closeEditor()
            model.openEditor(repository.subscriptions.value.single())
            advanceUntilIdle()
            gate.complete(AppSettings(defaultCurrency = "EUR", defaultReminderDays = setOf(7)))
            advanceUntilIdle()
            assertEquals(1L, model.uiState.value.editor.original!!.id)
            assertEquals("CNY", model.uiState.value.editor.currency)
            assertEquals(setOf(3), model.uiState.value.editor.reminderDays)
        }
    }
    @Test fun savingConvertsMajorUnitsAndReconcilesSelectedReminderDays() = scenario { model, repository, scheduler ->
        model.openEditor()
        advanceUntilIdle()
        model.updateEditor { it.copy(name = "音乐", amount = "15.00", reminderDays = setOf(1, 3, 7)) }
        model.saveSubscription()
        advanceUntilIdle()
        assertEquals(1500L, repository.subscriptions.value.single().amountMinor)
        assertEquals(setOf(1, 3, 7), scheduler.days)
        assertFalse(model.uiState.value.editor.isOpen)
    }
    @Test fun cancellingPreservesPaymentHistoryAndRestoringUsesStoredReminderDays() = scenario { model, repository, scheduler ->
        repository.seed()
        advanceUntilIdle()
        model.cancelSubscription(1)
        advanceUntilIdle()
        assertFalse(repository.subscriptions.value.single().isActive)
        assertEquals(1200L, repository.payments.value.single().amountMinor)
        assertTrue(1L in scheduler.cancelled)
        model.restoreSubscription(1)
        advanceUntilIdle()
        assertTrue(repository.subscriptions.value.single().isActive)
        assertEquals(setOf(3), scheduler.days)
    }
    @Test fun invalidAndFailedSavesKeepEditorOpenWithoutLosingInputs() = scenario { model, repository, _ ->
        model.openEditor()
        advanceUntilIdle()
        model.updateEditor { it.copy(name = "", amount = "15.00") }
        model.saveSubscription()
        advanceUntilIdle()
        assertTrue(repository.subscriptions.value.isEmpty())
        assertNotNull(model.uiState.value.editor.validationMessage)
        repository.failWrites = true
        model.updateEditor { it.copy(name = "保留输入") }
        model.saveSubscription()
        advanceUntilIdle()
        assertEquals("保留输入", model.uiState.value.editor.name)
        assertTrue(model.uiState.value.editor.isOpen)
        assertFalse(model.uiState.value.editor.isSaving)
        assertNotNull(model.uiState.value.errorMessage)
    }
    @Test fun editingCancelledSubscriptionRetainsInactiveStateAndReminderSettings() = scenario { model, repository, scheduler ->
        repository.seed(active = false)
        advanceUntilIdle()
        model.openEditor(repository.subscriptions.value.single())
        advanceUntilIdle()
        assertEquals(setOf(3), model.uiState.value.editor.reminderDays)
        model.updateEditor { it.copy(name = "编辑名称") }
        model.saveSubscription()
        advanceUntilIdle()
        assertFalse(repository.subscriptions.value.single().isActive)
        assertTrue(scheduler.days.isEmpty())
    }
    @Test fun editingActualPaymentDoesNotChangeBillingAnchor() = scenario { model, repository, _ ->
        repository.seed()
        advanceUntilIdle()
        val anchor = repository.subscriptions.value.single().nextBillingDate
        model.openPaymentEditor(1, repository.payments.value.single())
        model.updatePaymentEditor { it.copy(amount = "10.25") }
        model.savePayment()
        advanceUntilIdle()
        assertEquals(1025L, repository.payments.value.single().amountMinor)
        assertEquals(anchor, repository.subscriptions.value.single().nextBillingDate)
    }

    @Test fun savingOpenEditorCannotUndoLaterCancellation() = scenario { model, repository, scheduler ->
        repository.seed()
        advanceUntilIdle()
        model.openEditor(repository.subscriptions.value.single())
        advanceUntilIdle()
        model.cancelSubscription(1)
        advanceUntilIdle()
        model.updateEditor { it.copy(name = "修改名称") }
        model.saveSubscription()
        advanceUntilIdle()
        assertFalse(repository.subscriptions.value.single().isActive)
        assertEquals(LocalDate.now(), repository.subscriptions.value.single().cancelDate)
        assertTrue(scheduler.days.isEmpty())
    }

    @Test fun oldReminderFailureCannotContaminateNewEditor() = scenario { model, repository, _ ->
        repository.seed()
        val result = CompletableDeferred<Set<Int>>()
        repository.reminderRead = { result.await() }
        model.openEditor(repository.subscriptions.value.single())
        runCurrent()
        model.closeEditor()
        model.openEditor()
        advanceUntilIdle()
        model.updateEditor { it.copy(name = "新表单", reminderDays = setOf(7)) }
        result.completeExceptionally(IllegalStateException("old read failed"))
        advanceUntilIdle()
        assertEquals("新表单", model.uiState.value.editor.name)
        assertEquals(setOf(7), model.uiState.value.editor.reminderDays)
        assertNull(model.uiState.value.editor.validationMessage)
        assertNull(model.uiState.value.errorMessage)
    }

    @Test fun exportKeepsProgressAndOutcomeInViewModelWhileWriterIsSuspended() = scenario { model, repository, _ ->
        repository.seed()
        advanceUntilIdle()
        model.prepareCsvExport()
        val release = CompletableDeferred<Unit>()
        var written: String? = null
        model.completeCsvExport { csv -> release.await(); written = csv }
        runCurrent()
        assertTrue(model.uiState.value.isExporting)
        assertNull(model.uiState.value.exportMessage)
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(assertNotNull(written).contains("音乐"))
        assertFalse(model.uiState.value.isExporting)
        assertNotNull(model.uiState.value.exportMessage)
    }

    @Test fun statisticsOverflowKeepsRecordsAvailableAndShowsAnError() = scenario { model, repository, _ ->
        repository.seed()
        val first = repository.subscriptions.value.single().copy(amountMinor = Long.MAX_VALUE)
        repository.subscriptions.value = listOf(first, first.copy(id = 2, amountMinor = 1))
        advanceUntilIdle()
        assertEquals(2, model.uiState.value.subscriptions.size)
        assertNull(model.uiState.value.statistics)
        assertNotNull(model.uiState.value.errorMessage)
    }

    @Test fun subscriptionReadFailureRefusesExportUntilRetryRecovers() = readFailureScenario(subscriptionRead = true)

    @Test fun paymentReadFailureRefusesExportUntilRetryRecovers() = readFailureScenario(subscriptionRead = false)

    private fun readFailureScenario(subscriptionRead: Boolean) = scenario(configure = {
        seed()
        if (subscriptionRead) failSubscriptionReads.value = true else failPaymentReads.value = true
    }) { model, repository, _ ->
        assertNotNull(model.uiState.value.errorMessage)
        model.clearError()
        advanceUntilIdle()
        assertNull(model.prepareCsvExport())
        advanceUntilIdle()
        assertFalse(model.uiState.value.isExportPending)
        var written: String? = null
        model.completeCsvExport { written = it }
        advanceUntilIdle()
        assertNull(written)

        repository.failSubscriptionReads.value = false
        repository.failPaymentReads.value = false
        model.retry()
        advanceUntilIdle()
        val csv = assertNotNull(model.prepareCsvExport())
        assertTrue(csv.contains("subscription,1,音乐,1500,CNY"))
        assertTrue(csv.contains("payment,1,,1200,CNY"))
        advanceUntilIdle()
        assertTrue(model.uiState.value.isExportPending)
    }

    @Test fun readFailureAfterSuccessfulSnapshotRefusesNewExport() = scenario { model, repository, _ ->
        repository.seed()
        advanceUntilIdle()
        assertNotNull(model.prepareCsvExport())
        model.cancelCsvExport()
        repository.failPaymentReads.value = true
        advanceUntilIdle()
        assertNull(model.prepareCsvExport())
        advanceUntilIdle()
        assertFalse(model.uiState.value.isExportPending)
    }

    @Test fun successfullyLoadedEmptyDataExportsHeaderOnly() = scenario { model, _, _ ->
        val csv = assertNotNull(model.prepareCsvExport())
        assertEquals(
            "record_type,subscription_id,app_name,amount_minor,currency,billing_cycle,next_billing_date,start_date,category,note,is_active,cancel_date,paid_at\n",
            csv,
        )
        advanceUntilIdle()
        assertTrue(model.uiState.value.isExportPending)
    }

    @Test fun writeFailureDoesNotSuppressSuccessfullyLoadedExport() = scenario { model, repository, _ ->
        repository.seed()
        advanceUntilIdle()
        repository.failWrites = true
        model.openEditor()
        advanceUntilIdle()
        model.updateEditor { it.copy(name = "未保存", amount = "20.00") }
        model.saveSubscription()
        advanceUntilIdle()
        assertNotNull(model.uiState.value.errorMessage)
        val csv = assertNotNull(model.prepareCsvExport())
        assertTrue(csv.contains("subscription,1,音乐,1500,CNY"))
        assertTrue(csv.contains("payment,1,,1200,CNY"))
        assertFalse(csv.contains("未保存"))
    }

    @Test fun notificationOpensDetailImmediatelyWhenNoEditorIsOpen() = scenario { model, repository, _ ->
        repository.seedNotificationTargets()
        advanceUntilIdle()
        model.openNotificationDetail(2)
        advanceUntilIdle()
        assertEquals(2L, model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
        assertFalse(model.uiState.value.editor.isOpen)
        assertFalse(model.uiState.value.paymentEditor.isOpen)
    }

    @Test fun subscriptionDraftDefersLatestNotificationUntilClosed() = scenario { model, repository, _ ->
        repository.seedNotificationTargets()
        advanceUntilIdle()
        model.openDetail(1)
        model.openEditor(repository.subscriptions.value.first { it.id == 1L })
        advanceUntilIdle()
        model.updateEditor { it.copy(name = "未保存的名称", amount = "23.45", note = "保留备注") }
        advanceUntilIdle()
        val draft = model.uiState.value.editor
        model.openNotificationDetail(2)
        advanceUntilIdle()
        assertEquals(draft, model.uiState.value.editor)
        assertEquals(1L, model.uiState.value.detailId)
        assertEquals(2L, model.uiState.value.pendingNotificationId)
        model.openNotificationDetail(3)
        advanceUntilIdle()
        assertEquals(draft, model.uiState.value.editor)
        assertEquals(3L, model.uiState.value.pendingNotificationId)
        model.closeEditor()
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertEquals(3L, model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
        assertEquals("音乐", model.uiState.value.subscriptions.first { it.id == 1L }.appName)
    }

    @Test fun subscriptionSaveOpensDeferredNotificationAfterPersistingDraft() = scenario { model, repository, _ ->
        repository.seedNotificationTargets()
        advanceUntilIdle()
        model.openDetail(1)
        model.openEditor(repository.subscriptions.value.first { it.id == 1L })
        advanceUntilIdle()
        model.updateEditor { it.copy(name = "保存后的名称", amount = "23.45") }
        model.openNotificationDetail(2)
        model.saveSubscription()
        advanceUntilIdle()
        assertEquals("保存后的名称", model.uiState.value.subscriptions.first { it.id == 1L }.appName)
        assertEquals(2345L, model.uiState.value.subscriptions.first { it.id == 1L }.amountMinor)
        assertFalse(model.uiState.value.editor.isOpen)
        assertEquals(2L, model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
    }

    @Test fun subscriptionValidationAndWriteFailureKeepDraftAndDeferredNotification() = scenario { model, repository, _ ->
        repository.seedNotificationTargets()
        advanceUntilIdle()
        model.openDetail(1)
        model.openEditor()
        advanceUntilIdle()
        model.updateEditor { it.copy(amount = "23.45", note = "保留备注") }
        model.openNotificationDetail(2)
        model.saveSubscription()
        advanceUntilIdle()
        assertTrue(model.uiState.value.editor.isOpen)
        assertNotNull(model.uiState.value.editor.validationMessage)
        assertEquals("保留备注", model.uiState.value.editor.note)
        assertEquals(1L, model.uiState.value.detailId)
        assertEquals(2L, model.uiState.value.pendingNotificationId)
        repository.failWrites = true
        model.updateEditor { it.copy(name = "未保存的名称") }
        model.saveSubscription()
        advanceUntilIdle()
        assertTrue(model.uiState.value.editor.isOpen)
        assertFalse(model.uiState.value.editor.isSaving)
        assertEquals("未保存的名称", model.uiState.value.editor.name)
        assertEquals("23.45", model.uiState.value.editor.amount)
        assertEquals("保留备注", model.uiState.value.editor.note)
        assertNotNull(model.uiState.value.editor.validationMessage)
        assertEquals(1L, model.uiState.value.detailId)
        assertEquals(2L, model.uiState.value.pendingNotificationId)
        repository.failWrites = false
        model.saveSubscription()
        advanceUntilIdle()
        assertEquals(2L, model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
        assertFalse(model.uiState.value.editor.isOpen)
    }

    @Test fun paymentDraftDefersNotificationUntilClosed() = scenario { model, repository, _ ->
        repository.seedNotificationTargets()
        advanceUntilIdle()
        model.openDetail(1)
        model.openPaymentEditor(1, repository.payments.value.single())
        model.updatePaymentEditor { it.copy(amount = "10.25", note = "未保存的扣费备注") }
        advanceUntilIdle()
        val draft = model.uiState.value.paymentEditor
        model.openNotificationDetail(2)
        advanceUntilIdle()
        assertEquals(draft, model.uiState.value.paymentEditor)
        assertEquals(1L, model.uiState.value.detailId)
        assertEquals(2L, model.uiState.value.pendingNotificationId)
        model.closePaymentEditor()
        advanceUntilIdle()
        assertFalse(model.uiState.value.paymentEditor.isOpen)
        assertEquals(2L, model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
        assertEquals(1200L, model.uiState.value.payments.single().amountMinor)
    }

    @Test fun paymentSaveOpensDeferredNotificationAfterPersistingDraft() = scenario { model, repository, _ ->
        repository.seedNotificationTargets()
        advanceUntilIdle()
        model.openDetail(1)
        model.openPaymentEditor(1, repository.payments.value.single())
        model.updatePaymentEditor { it.copy(amount = "10.25", note = "保存的扣费备注") }
        model.openNotificationDetail(2)
        model.savePayment()
        advanceUntilIdle()
        assertEquals(1025L, model.uiState.value.payments.single().amountMinor)
        assertEquals("保存的扣费备注", model.uiState.value.payments.single().note)
        assertFalse(model.uiState.value.paymentEditor.isOpen)
        assertEquals(2L, model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
    }

    @Test fun paymentValidationAndWriteFailureKeepDraftAndDeferredNotification() = scenario { model, repository, _ ->
        repository.seedNotificationTargets()
        advanceUntilIdle()
        model.openDetail(1)
        model.openPaymentEditor(1, repository.payments.value.single())
        model.updatePaymentEditor { it.copy(amount = "invalid", note = "保留扣费备注") }
        model.openNotificationDetail(2)
        model.savePayment()
        advanceUntilIdle()
        assertTrue(model.uiState.value.paymentEditor.isOpen)
        assertNotNull(model.uiState.value.paymentEditor.validationMessage)
        assertEquals("invalid", model.uiState.value.paymentEditor.amount)
        assertEquals("保留扣费备注", model.uiState.value.paymentEditor.note)
        assertEquals(1L, model.uiState.value.detailId)
        assertEquals(2L, model.uiState.value.pendingNotificationId)
        repository.failWrites = true
        model.updatePaymentEditor { it.copy(amount = "10.25") }
        model.savePayment()
        advanceUntilIdle()
        assertTrue(model.uiState.value.paymentEditor.isOpen)
        assertFalse(model.uiState.value.paymentEditor.isSaving)
        assertEquals("10.25", model.uiState.value.paymentEditor.amount)
        assertEquals("保留扣费备注", model.uiState.value.paymentEditor.note)
        assertNotNull(model.uiState.value.paymentEditor.validationMessage)
        assertEquals(1L, model.uiState.value.detailId)
        assertEquals(2L, model.uiState.value.pendingNotificationId)
        repository.failWrites = false
        model.savePayment()
        advanceUntilIdle()
        assertEquals(2L, model.uiState.value.detailId)
        assertNull(model.uiState.value.pendingNotificationId)
        assertFalse(model.uiState.value.paymentEditor.isOpen)
    }

    private fun scenario(
        configure: FakeRepository.() -> Unit = {},
        preferences: Preferences = Preferences(),
        block: suspend TestScope.(SubscriptionViewModel, FakeRepository, RecordingScheduler) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = FakeRepository()
        repository.configure()
        val scheduler = RecordingScheduler()
        val model = SubscriptionViewModel(repository, scheduler, preferences, dispatcher)
        try { advanceUntilIdle(); block(model, repository, scheduler) }
        finally { model.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    private class RecordingScheduler : SubscriptionReminderSchedulerContract {
        var days = emptySet<Int>()
        val cancelled = mutableListOf<Long>()
        override fun schedule(subscription: Subscription, reminderDays: Set<Int>) { days = if (subscription.isActive) reminderDays else emptySet() }
        override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) { cancelled += subscriptionId; days = emptySet() }
        override fun cancelAll(subscriptionId: Long) { cancelled += subscriptionId; days = emptySet() }
    }
    private class Preferences(initial: AppSettings = AppSettings()) : SettingsRepository {
        private val values = MutableStateFlow(initial)
        var read: (suspend () -> AppSettings)? = null
        override fun observeSettings() = values
        override suspend fun getSettings() = read?.invoke() ?: values.value
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) { values.value = transform(values.value) }
    }
    private class FakeRepository : SubscriptionRepository {
        val subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
        val payments = MutableStateFlow<List<SubscriptionPayment>>(emptyList())
        val failSubscriptionReads = MutableStateFlow(false)
        val failPaymentReads = MutableStateFlow(false)
        private val reminders = MutableStateFlow<Map<Long, Set<Int>>>(emptyMap())
        var failWrites = false
        var reminderRead: (suspend () -> Set<Int>)? = null
        fun seed(active: Boolean = true) {
            subscriptions.value = listOf(Subscription(id = 1, appName = "音乐", amountMinor = 1500,
                billingCycle = BillingCycle.MONTHLY, nextBillingDate = LocalDate.now().plusDays(10),
                startDate = LocalDate.now().minusMonths(1), isActive = active,
                cancelDate = if (active) null else LocalDate.now(), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
            payments.value = listOf(SubscriptionPayment(1, 1, 1200, "CNY", LocalDate.now()))
            reminders.value = mapOf(1L to setOf(3))
        }
        fun seedNotificationTargets() {
            seed()
            val first = subscriptions.value.single()
            subscriptions.value = listOf(first, first.copy(id = 2, appName = "阅读"), first.copy(id = 3, appName = "云存储"))
        }
        override fun observeSubscriptions(): Flow<List<Subscription>> = failSubscriptionReads.flatMapLatest { fail ->
            flow { check(!fail) { "订阅读取失败" }; emitAll(subscriptions) }
        }
        override fun observeAllPayments(): Flow<List<SubscriptionPayment>> = failPaymentReads.flatMapLatest { fail ->
            flow { check(!fail) { "扣费读取失败" }; emitAll(payments) }
        }
        override fun observePayments(subscriptionId: Long) = payments.map { it.filter { p -> p.subscriptionId == subscriptionId } }
        override fun observeReminders(subscriptionId: Long) = reminders.map { it[subscriptionId].orEmpty() }
        override suspend fun getSubscriptions() = subscriptions.value
        override suspend fun getSubscription(id: Long) = subscriptions.value.firstOrNull { it.id == id }
        override suspend fun getReminderDays(subscriptionId: Long) = reminderRead?.invoke() ?: reminders.value[subscriptionId].orEmpty()
        override suspend fun saveSubscription(subscription: Subscription, reminderDays: Set<Int>): Long {
            check(!failWrites) { "写入失败" }
            val id = subscription.id.takeIf { it != 0L } ?: ((subscriptions.value.maxOfOrNull { it.id } ?: 0) + 1)
            subscriptions.value = subscriptions.value.filterNot { it.id == id } + subscription.copy(id = id)
            reminders.value = reminders.value + (id to reminderDays)
            return id
        }
        override suspend fun savePayment(payment: SubscriptionPayment): Long {
            check(!failWrites)
            val id = payment.id.takeIf { it != 0L } ?: ((payments.value.maxOfOrNull { it.id } ?: 0) + 1)
            payments.value = payments.value.filterNot { it.id == id } + payment.copy(id = id)
            return id
        }
        override suspend fun deletePayment(id: Long) { payments.value = payments.value.filterNot { it.id == id } }
        override suspend fun deleteSubscription(id: Long) {
            subscriptions.value = subscriptions.value.filterNot { it.id == id }
            payments.value = payments.value.filterNot { it.subscriptionId == id }
        }
    }
}
