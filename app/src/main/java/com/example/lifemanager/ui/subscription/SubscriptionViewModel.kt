package com.example.lifemanager.ui.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.usecase.SubscriptionRules
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
    private val scheduler: SubscriptionReminderSchedulerContract,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val controls = MutableStateFlow(SubscriptionUiState())
    private val reload = MutableStateFlow(0)
    private var editorGeneration = 0L
    private var exportSnapshot: String? = null
    private data class ReadState<T>(
        val records: List<T> = emptyList(),
        val isAvailable: Boolean = false,
        val isLoading: Boolean = true,
    )
    private fun <T> Flow<List<T>>.readState(): Flow<ReadState<T>> =
        map { ReadState(records = it, isAvailable = true, isLoading = false) }
            .onStart { emit(ReadState()) }
            .catch { error ->
                if (error is CancellationException) throw error
                controls.update { it.copy(errorMessage = "读取订阅失败，请重试") }
                emit(ReadState(isLoading = false))
            }
    private val data = reload.flatMapLatest {
        combine(repository.observeSubscriptions().readState(), repository.observeAllPayments().readState()) { list, payments -> list to payments }
    }
    val uiState = combine(data, controls) { (subscriptionRead, paymentRead), state ->
        val list = subscriptionRead.records
        val payments = paymentRead.records
        val available = subscriptionRead.isAvailable && paymentRead.isAvailable
        val today = LocalDate.now()
        val stats = try { if (available) SubscriptionRules.calculateStats(list, payments, today) else null }
        catch (_: ArithmeticException) { null }
        state.copy(subscriptions = list.sortedWith(compareBy<Subscription> {
            SubscriptionRules.effectiveNextBillingDate(it, today) ?: LocalDate.MAX
        }.thenBy { it.id }), payments = payments, statistics = stats,
            isLoading = subscriptionRead.isLoading || paymentRead.isLoading,
            hasSubscriptionData = subscriptionRead.isAvailable, hasPaymentData = paymentRead.isAvailable,
            errorMessage = state.errorMessage ?: if (available && stats == null) "统计金额超出支持范围；记录仍可编辑和导出" else null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SubscriptionUiState())

    fun retry() {
        controls.update { it.copy(errorMessage = null) }
        reload.value++
    }
    fun clearError() { controls.update { it.copy(errorMessage = null) } }
    fun reportError(message: String) { controls.update { it.copy(errorMessage = message) } }
    fun selectTab(tab: SubscriptionTab) { controls.update { it.copy(selectedTab = tab) } }
    fun openDetail(id: Long) {
        controls.update { it.copy(detailId = id, detailReminderDays = emptySet()) }
        loadDetailReminders(id)
    }
    fun openNotificationDetail(id: Long) {
        val previous = controls.getAndUpdate { state ->
            if (state.editor.isOpen || state.paymentEditor.isOpen) state.copy(pendingNotificationId = id)
            else state.copy(detailId = id, detailReminderDays = emptySet(), pendingNotificationId = null)
        }
        if (!previous.editor.isOpen && !previous.paymentEditor.isOpen) loadDetailReminders(id)
    }
    private fun drainPendingNotification() {
        val previous = controls.getAndUpdate { state ->
            if (state.editor.isOpen || state.paymentEditor.isOpen || state.pendingNotificationId == null) state
            else state.copy(detailId = state.pendingNotificationId, detailReminderDays = emptySet(), pendingNotificationId = null)
        }
        if (!previous.editor.isOpen && !previous.paymentEditor.isOpen) {
            previous.pendingNotificationId?.let(::loadDetailReminders)
        }
    }
    private fun loadDetailReminders(id: Long) {
        viewModelScope.launch(dispatcher) {
            try {
                val days = repository.getReminderDays(id)
                controls.update { if (it.detailId == id) it.copy(detailReminderDays = days) else it }
            } catch (error: Exception) { handleError(error, "读取提醒设置失败") }
        }
    }
    fun closeDetail() { controls.update { it.copy(detailId = null) } }

    fun openEditor(subscription: Subscription? = null) {
        if (controls.value.isBusy) return
        val generation = ++editorGeneration
        controls.update { it.copy(editor = SubscriptionEditorState(isOpen = true, sessionId = generation, original = subscription,
            name = subscription?.appName.orEmpty(), amount = subscription?.let { amountText(it.amountMinor, it.currency) }.orEmpty(),
            currency = subscription?.currency ?: "CNY", billingCycle = subscription?.billingCycle ?: com.example.lifemanager.domain.model.BillingCycle.MONTHLY,
            nextBillingDate = (subscription?.nextBillingDate ?: LocalDate.now()).toString(),
            startDate = (subscription?.startDate ?: LocalDate.now()).toString(), category = subscription?.category.orEmpty(),
            note = subscription?.note.orEmpty(), isLoadingReminders = subscription != null)) }
        if (subscription != null) viewModelScope.launch(dispatcher) {
            try {
                val days = repository.getReminderDays(subscription.id)
                controls.update { state ->
                    if (state.editor.sessionId == generation && state.editor.isOpen)
                        state.copy(editor = state.editor.copy(reminderDays = days, isLoadingReminders = false))
                    else state
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                controls.update { state ->
                    if (state.editor.sessionId == generation && state.editor.isOpen)
                        state.copy(editor = state.editor.copy(validationMessage = "读取提醒配置失败，请关闭后重试"))
                    else state
                }
                // Keep saving disabled: do not silently replace unread reminder settings.
            }
        }
    }
    fun closeEditor() {
        if (controls.value.editor.isSaving) return
        editorGeneration++
        controls.update { it.copy(editor = SubscriptionEditorState()) }
        drainPendingNotification()
    }
    fun updateEditor(transform: (SubscriptionEditorState) -> SubscriptionEditorState) {
        controls.update { if (it.editor.isSaving) it else it.copy(editor = transform(it.editor).copy(validationMessage = null)) }
    }
    fun saveSubscription() {
        val editor = controls.value.editor
        if (!editor.isOpen || editor.isSaving || editor.isLoadingReminders || controls.value.isBusy) return
        val currency = editor.currency.trim().uppercase(Locale.ROOT)
        val amount = SubscriptionRules.parseAmountMinor(editor.amount, currency)
        val next = parseDate(editor.nextBillingDate)
        val start = parseDate(editor.startDate)
        val validation = when {
            editor.name.isBlank() -> "订阅名称不能为空"
            amount == null -> "请输入有效正数金额，精度须符合币种"
            next == null || start == null -> "请输入有效日期（YYYY-MM-DD）"
            else -> null
        }
        if (validation != null) { controls.update { it.copy(editor = editor.copy(validationMessage = validation)) }; return }
        val now = Instant.now()
        val original = editor.original
        val subscription = Subscription(id = original?.id ?: 0, appName = editor.name.trim(), amountMinor = amount!!,
            currency = currency, billingCycle = editor.billingCycle, nextBillingDate = next!!, startDate = start!!,
            category = editor.category, note = editor.note, isActive = original?.isActive ?: true,
            cancelDate = original?.cancelDate, createdAt = original?.createdAt ?: now, updatedAt = now)
        SubscriptionRules.validate(subscription)?.let { message ->
            controls.update { it.copy(editor = editor.copy(validationMessage = message)) }; return
        }
        controls.update { it.copy(editor = editor.copy(isSaving = true)) }
        mutate("保存订阅失败") {
            // Lifecycle state is not an editable form field. Preserve the latest cancellation/restoration.
            val current = original?.let { checkNotNull(repository.getSubscription(it.id)) { "订阅已被删除" } }
            val saved = subscription.copy(isActive = current?.isActive ?: true,
                cancelDate = current?.cancelDate, createdAt = current?.createdAt ?: subscription.createdAt)
            require(SubscriptionRules.validate(saved) == null) { "日期与当前订阅状态不一致" }
            val id = repository.saveSubscription(saved, editor.reminderDays)
            reconcile(saved.copy(id = id), editor.reminderDays)
            editorGeneration++
            controls.update { it.copy(editor = SubscriptionEditorState(), detailReminderDays = editor.reminderDays) }
            drainPendingNotification()
        }
    }
    fun cancelSubscription(id: Long) = changeActive(id, false)
    fun restoreSubscription(id: Long) = changeActive(id, true)
    private fun changeActive(id: Long, active: Boolean) = mutate("更新订阅失败") {
        val subscription = checkNotNull(repository.getSubscription(id)) { "订阅已被删除" }
        val days = repository.getReminderDays(id)
        val saved = subscription.copy(isActive = active, cancelDate = if (active) null else maxOf(LocalDate.now(), subscription.startDate), updatedAt = Instant.now())
        repository.saveSubscription(saved, days)
        reconcile(saved, days)
    }
    fun deleteSubscription(id: Long) = mutate("删除订阅失败") {
        repository.deleteSubscription(id)
        try { scheduler.cancelAll(id) } catch (error: Exception) { handleError(error, "数据已删除，但提醒清理失败") }
        if (controls.value.detailId == id) closeDetail()
    }
    private fun reconcile(subscription: Subscription, days: Set<Int>) {
        try {
            scheduler.cancelAll(subscription.id)
            if (subscription.isActive) scheduler.schedule(subscription, days)
        } catch (error: Exception) { handleError(error, "数据已保存，但提醒安排失败，请检查系统提醒权限") }
    }

    fun openPaymentEditor(subscriptionId: Long, payment: SubscriptionPayment? = null) {
        if (controls.value.isBusy) return
        val subscription = uiState.value.subscriptions.firstOrNull { it.id == subscriptionId } ?: return
        controls.update { it.copy(paymentEditor = PaymentEditorState(isOpen = true, subscriptionId = subscriptionId,
            editingId = payment?.id ?: 0, amount = amountText(payment?.amountMinor ?: subscription.amountMinor, payment?.currency ?: subscription.currency),
            currency = payment?.currency ?: subscription.currency, paidAt = (payment?.paidAt ?: LocalDate.now()).toString(), note = payment?.note.orEmpty())) }
    }
    fun closePaymentEditor() {
        if (controls.value.paymentEditor.isSaving) return
        controls.update { it.copy(paymentEditor = PaymentEditorState()) }
        drainPendingNotification()
    }
    fun updatePaymentEditor(transform: (PaymentEditorState) -> PaymentEditorState) {
        controls.update { if (it.paymentEditor.isSaving) it else it.copy(paymentEditor = transform(it.paymentEditor).copy(validationMessage = null)) }
    }
    fun savePayment() {
        val editor = controls.value.paymentEditor
        if (!editor.isOpen || editor.isSaving || controls.value.isBusy) return
        val currency = editor.currency.trim().uppercase(Locale.ROOT)
        val amount = SubscriptionRules.parseAmountMinor(editor.amount, currency)
        val date = parseDate(editor.paidAt)
        if (amount == null || date == null) {
            controls.update { it.copy(paymentEditor = editor.copy(validationMessage = "请输入有效的金额、币种和日期（YYYY-MM-DD）")) }; return
        }
        controls.update { it.copy(paymentEditor = editor.copy(isSaving = true)) }
        mutate("保存扣费记录失败") {
            repository.savePayment(SubscriptionPayment(editor.editingId, editor.subscriptionId, amount, currency, date, editor.note))
            controls.update { it.copy(paymentEditor = PaymentEditorState()) }
            drainPendingNotification()
        }
    }
    fun deletePayment(id: Long) = mutate("删除扣费记录失败") { repository.deletePayment(id) }

    fun prepareCsvExport(): String? {
        val state = uiState.value
        if (!state.canExportCsv || controls.value.isExportPending || controls.value.isExporting) return null
        return SubscriptionRules.exportCsv(state.subscriptions, state.payments).also {
            exportSnapshot = it
            controls.update { current -> current.copy(isExportPending = true, exportMessage = null) }
        }
    }
    fun cancelCsvExport() {
        exportSnapshot = null
        controls.update { it.copy(isExportPending = false) }
    }
    /** Writer uses application context at the UI boundary; work outlives the composition. */
    fun completeCsvExport(write: suspend (String) -> Unit) {
        val snapshot = exportSnapshot ?: return
        exportSnapshot = null
        controls.update { it.copy(isExportPending = false, isExporting = true, exportMessage = null) }
        viewModelScope.launch(dispatcher) {
            try {
                write(snapshot)
                controls.update { it.copy(exportMessage = "CSV 已导出") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                controls.update { it.copy(exportMessage = "导出失败，请检查文件位置和可用空间") }
            } finally { controls.update { it.copy(isExporting = false) } }
        }
    }

    private fun mutate(failureMessage: String, action: suspend () -> Unit) {
        if (controls.value.isBusy) return
        controls.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch(dispatcher) {
            try { SubscriptionOperationCoordinator.run { action() } }
            catch (error: Exception) { handleError(error, failureMessage) }
            finally { controls.update { it.copy(isBusy = false, editor = it.editor.copy(isSaving = false), paymentEditor = it.paymentEditor.copy(isSaving = false)) } }
        }
    }
    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value.trim()) }.getOrNull()
    private fun handleError(error: Exception, message: String) {
        if (error is CancellationException) throw error
        controls.update { it.copy(errorMessage = message,
            editor = if (it.editor.isOpen) it.editor.copy(validationMessage = message) else it.editor,
            paymentEditor = if (it.paymentEditor.isOpen) it.paymentEditor.copy(validationMessage = message) else it.paymentEditor) }
    }
}
