package com.example.lifemanager.ui.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import com.example.lifemanager.domain.repository.SettingsRepository
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import com.example.lifemanager.domain.usecase.SubscriptionRules
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import com.example.lifemanager.ui.common.GenerationAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
    private val scheduler: SubscriptionReminderSchedulerContract,
    private val settingsRepository: SettingsRepository,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val access: GenerationAccess,
) : ViewModel() {
    private val controls = MutableStateFlow(SubscriptionUiState())
    val uiState = controls.asStateFlow()
    private val reload = MutableStateFlow(0L)
    private val generationReload = MutableStateFlow(0L)
    private var observedGeneration: DataGeneration? = null
    private var generationFailed = false
    private var editorSession = 0L
    private var detailRequest = 0L
    private var notificationRequest = 0L
    private var notificationGeneration: DataGeneration? = null
    private var exportRequest = 0L
    private var activeMutation: Any? = null
    private data class NotificationRequest(val id: Long, val generation: DataGeneration, val request: Long)
    private data class CsvRequest(val text: String, val generation: DataGeneration, val request: Long)
    private var pendingNotification: NotificationRequest? = null
    private var exportSnapshot: CsvRequest? = null
    private var activeExport: CsvRequest? = null

    init {
        viewModelScope.launch {
            access.maintenance.collect { phase -> controls.update { it.copy(isMaintaining = phase != MaintenanceState.IDLE) } }
        }
        viewModelScope.launch(dispatcher) {
            generationReload.collectLatest {
                try {
                    access.generations.collectLatest { generation ->
                        withContext(Dispatchers.Main.immediate) {
                            observeGeneration(generation)
                        }
                        access.maintenance.collectLatest { phase ->
                            if (phase == MaintenanceState.IDLE) reload.collectLatest { observeSnapshot(generation) }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    withContext(Dispatchers.Main.immediate) { generationUnavailable() }
                }
            }
        }
    }

    private suspend fun observeSnapshot(generation: DataGeneration) {
        try {
            // Long-lived streams only invalidate; their possibly buffered DTOs are never published.
            combine(repository.observeSubscriptions().map { Unit }, repository.observeAllPayments().map { Unit }) { _, _ -> Unit }.collect {
                access.read({ repository.observeSubscriptions().first() to repository.observeAllPayments().first() }) { token, (list, payments) ->
                    observeGeneration(token)
                    generationFailed = false
                    val today = LocalDate.now()
                    val stats = try { SubscriptionRules.calculateStats(list, payments, today) } catch (_: ArithmeticException) { null }
                    controls.update { state -> state.copy(
                        generation = token, isAvailable = true, isLoading = false,
                        hasSubscriptionData = true, hasPaymentData = true,
                        subscriptions = list.sortedWith(compareBy<Subscription> {
                            SubscriptionRules.effectiveNextBillingDate(it, today) ?: LocalDate.MAX
                        }.thenBy { it.id }), payments = payments, statistics = stats,
                        errorMessage = state.errorMessage ?: if (stats == null) "统计金额超出支持范围；记录仍可编辑和导出" else null,
                    ) }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            publishResult(generation) { readUnavailable("读取订阅失败，请重试") }
        }
    }

    private fun observeGeneration(token: DataGeneration) {
        val previous = observedGeneration
        if (previous != null && token.value < previous.value) return
        if (previous != null && previous != token) invalidateSnapshot(token)
        observedGeneration = token
    }

    private fun invalidateSnapshot(token: DataGeneration) {
        editorSession++
        detailRequest++
        // Page invalidation and notification invalidation have different generation boundaries.
        if (notificationGeneration?.let { it.value < token.value } != false) {
            notificationRequest++
            notificationGeneration = null
        }
        activeMutation = null
        pendingNotification = null
        exportSnapshot = null
        activeExport = null
        controls.update { SubscriptionUiState(selectedTab = it.selectedTab, isMaintaining = it.isMaintaining) }
    }

    fun retry(generation: DataGeneration? = controls.value.generation) {
        if (generation != controls.value.generation || access.maintenance.value != MaintenanceState.IDLE || controls.value.isBusy) return
        controls.update { it.copy(errorMessage = null, isLoading = true, isAvailable = false) }
        if (generationFailed) generationReload.value++ else reload.value++
    }

    fun clearError(generation: DataGeneration? = controls.value.generation) {
        if (generation == controls.value.generation) controls.update { it.copy(errorMessage = null) }
    }

    fun reportError(message: String, generation: DataGeneration? = controls.value.generation) {
        if (generation == controls.value.generation) controls.update { it.copy(errorMessage = message) }
    }

    fun selectTab(tab: SubscriptionTab) { controls.update { it.copy(selectedTab = tab) } }

    private fun eventToken(generation: DataGeneration?): DataGeneration? {
        if (!controls.value.isAvailable || generation != controls.value.generation) return null
        return try { access.eventToken(generation) } catch (_: IllegalStateException) { null }
    }

    fun openDetail(id: Long, generation: DataGeneration? = controls.value.generation) {
        val token = eventToken(generation) ?: return
        if (controls.value.subscriptions.none { it.id == id }) return
        val request = ++detailRequest
        controls.update { it.copy(detailId = id, detailReminderDays = emptySet()) }
        viewModelScope.launch(dispatcher) {
            try {
                access.run(token) {
                    val days = repository.getReminderDays(id)
                    withContext(Dispatchers.Main.immediate) {
                        if (detailRequest == request && controls.value.generation == token && controls.value.detailId == id)
                            controls.update { it.copy(detailReminderDays = days) }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is MaintenanceBusyException || error is StaleGenerationException) return@launch
                publishResult(token) {
                    if (detailRequest == request && controls.value.detailId == id) setError("读取提醒设置失败")
                }
            }
        }
    }

    /** External intent protection is separate; the VM's lookup and deferred target retain their arrival token. */
    fun openNotificationDetail(id: Long, arrivalGeneration: DataGeneration? = null) {
        if (id <= 0L) return
        val request = ++notificationRequest
        val original = arrivalGeneration ?: controls.value.generation ?: observedGeneration
        notificationGeneration = original
        if (original != null) launchNotification(NotificationRequest(id, original, request))
        else viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Enter capture before the first suspension so maintenance drains the cold-start metadata read.
                val token = access.capture()
                if (notificationRequest != request) return@launch
                notificationGeneration = token
                launchNotification(NotificationRequest(id, token, request))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is MaintenanceBusyException || error is StaleGenerationException) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (notificationRequest == request && observedGeneration == null && controls.value.generation == null) generationUnavailable()
                }
            }
        }
    }

    private fun launchNotification(target: NotificationRequest) {
        viewModelScope.launch(dispatcher) {
            try { deliverNotification(target) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                publishResult(target.generation) { if (notificationRequest == target.request) setError("读取提醒设置失败") }
            }
        }
    }

    private suspend fun deliverNotification(target: NotificationRequest) {
        while (true) {
            try {
                access.run(target.generation) {
                    val deferred = withContext(Dispatchers.Main.immediate) {
                        observeGeneration(target.generation)
                        if (notificationRequest != target.request) true
                        else if (controls.value.editor.isOpen || controls.value.paymentEditor.isOpen) {
                            pendingNotification = target
                            controls.update { it.copy(pendingNotificationId = target.id) }
                            true
                        } else false
                    }
                    if (!deferred) {
                        val days = repository.getReminderDays(target.id)
                        withContext(Dispatchers.Main.immediate) {
                            if (notificationRequest == target.request) {
                                if (controls.value.editor.isOpen || controls.value.paymentEditor.isOpen) {
                                    pendingNotification = target
                                    controls.update { it.copy(pendingNotificationId = target.id) }
                                } else {
                                    detailRequest++
                                    pendingNotification = null
                                    controls.update { it.copy(detailId = target.id, detailReminderDays = days, pendingNotificationId = null) }
                                }
                            }
                        }
                    }
                }
                return
            } catch (_: MaintenanceBusyException) {
                // Only a fixed-token read waits; mutations never queue here.
                access.maintenance.first { it == MaintenanceState.IDLE }
                yield()
            } catch (_: StaleGenerationException) { return }
        }
    }

    private fun drainPendingNotification() {
        val target = pendingNotification ?: return
        if (!controls.value.editor.isOpen && !controls.value.paymentEditor.isOpen) launchNotification(target)
    }

    fun closeDetail(generation: DataGeneration? = controls.value.generation) {
        if (generation != controls.value.generation) return
        detailRequest++
        controls.update { it.copy(detailId = null, detailReminderDays = emptySet()) }
    }

    fun openEditor(subscription: Subscription? = null, generation: DataGeneration? = controls.value.generation) {
        if (controls.value.isBusy) return
        val token = eventToken(generation) ?: return
        if (subscription != null && controls.value.subscriptions.none { it == subscription }) return
        val session = ++editorSession
        controls.update { it.copy(editor = SubscriptionEditorState(generation = token,
            isOpen = true, sessionId = session, original = subscription,
            name = subscription?.appName.orEmpty(), amount = subscription?.let { amountText(it.amountMinor, it.currency) }.orEmpty(),
            currency = subscription?.currency ?: "CNY", billingCycle = subscription?.billingCycle ?: BillingCycle.MONTHLY,
            nextBillingDate = (subscription?.nextBillingDate ?: LocalDate.now()).toString(),
            startDate = (subscription?.startDate ?: LocalDate.now()).toString(), category = subscription?.category.orEmpty(),
            note = subscription?.note.orEmpty(), isLoadingReminders = true)) }
        viewModelScope.launch(dispatcher) {
            try {
                access.run(token) {
                    // Strict getSettings never initializes missing rows; read defaults under this draft's permit.
                    val defaults = if (subscription == null) settingsRepository.getSettings() else null
                    val days = defaults?.defaultReminderDays ?: repository.getReminderDays(subscription!!.id)
                    withContext(Dispatchers.Main.immediate) {
                        controls.update { state ->
                            if (state.editor.sessionId == session && state.editor.generation == token && state.editor.isOpen)
                                state.copy(editor = state.editor.copy(currency = defaults?.defaultCurrency ?: state.editor.currency,
                                    reminderDays = days.toSet(), isLoadingReminders = false))
                            else state
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is StaleGenerationException) return@launch
                publishResult(token) {
                    controls.update { state ->
                        if (state.editor.sessionId == session && state.editor.generation == token && state.editor.isOpen)
                            state.copy(editor = state.editor.copy(isLoadingReminders = false, preferencesError =
                                if (subscription == null) "读取新建订阅偏好失败，请关闭后重试" else "读取提醒配置失败，请关闭后重试"))
                        else state
                    }
                }
            }
        }
    }

    fun closeEditor(generation: DataGeneration? = controls.value.editor.generation) {
        if (controls.value.editor.isSaving || controls.value.editor.generation != generation) return
        editorSession++
        controls.update { it.copy(editor = SubscriptionEditorState()) }
        drainPendingNotification()
    }

    fun updateEditor(generation: DataGeneration? = controls.value.editor.generation, transform: (SubscriptionEditorState) -> SubscriptionEditorState) {
        controls.update {
            if (!it.editor.isOpen || it.editor.isSaving || it.editor.generation != generation) it
            else {
                val next = transform(it.editor)
                it.copy(editor = next.copy(generation = it.editor.generation, sessionId = it.editor.sessionId,
                    original = it.editor.original, reminderDays = next.reminderDays.toSet(), validationMessage = null))
            }
        }
    }

    fun saveSubscription(generation: DataGeneration? = controls.value.editor.generation) {
        val editor = controls.value.editor.copy(reminderDays = controls.value.editor.reminderDays.toSet())
        if (!editor.isOpen || editor.isSaving || editor.isLoadingReminders || editor.preferencesError != null || controls.value.isBusy || editor.generation != generation) return
        val token = eventToken(generation) ?: return
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
        mutate(token, "保存订阅失败") {
            val current = original?.let { checkNotNull(repository.getSubscription(it.id)) { "订阅已被删除" } }
            val saved = subscription.copy(isActive = current?.isActive ?: true,
                cancelDate = current?.cancelDate, createdAt = current?.createdAt ?: subscription.createdAt)
            require(SubscriptionRules.validate(saved) == null) { "日期与当前订阅状态不一致" }
            val id = repository.saveSubscription(saved, editor.reminderDays)
            reconcile(saved.copy(id = id), editor.reminderDays)
            withContext(Dispatchers.Main.immediate) {
                editorSession++
                controls.update { it.copy(editor = SubscriptionEditorState(), detailReminderDays = editor.reminderDays) }
            }
        }
    }

    fun cancelSubscription(id: Long, generation: DataGeneration? = controls.value.generation) = changeActive(id, false, generation)
    fun restoreSubscription(id: Long, generation: DataGeneration? = controls.value.generation) = changeActive(id, true, generation)

    private fun changeActive(id: Long, active: Boolean, generation: DataGeneration?) {
        val token = eventToken(generation) ?: return
        mutate(token, "更新订阅失败") {
            val subscription = checkNotNull(repository.getSubscription(id)) { "订阅已被删除" }
            val days = repository.getReminderDays(id)
            val saved = subscription.copy(isActive = active, cancelDate = if (active) null else maxOf(LocalDate.now(), subscription.startDate), updatedAt = Instant.now())
            repository.saveSubscription(saved, days)
            reconcile(saved, days)
        }
    }

    fun deleteSubscription(id: Long, generation: DataGeneration? = controls.value.generation) {
        val token = eventToken(generation) ?: return
        mutate(token, "删除订阅失败") {
            repository.deleteSubscription(id)
            try { scheduler.cancelAll(id) } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main.immediate) { setError("数据已删除，但提醒清理失败") }
            }
            withContext(Dispatchers.Main.immediate) { if (controls.value.detailId == id) closeDetail(token) }
        }
    }

    private suspend fun reconcile(subscription: Subscription, days: Set<Int>) {
        try {
            scheduler.cancelAll(subscription.id)
            if (subscription.isActive) scheduler.scheduleCurrent(subscription, days)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            withContext(Dispatchers.Main.immediate) { setError("数据已保存，但提醒安排失败，请检查系统提醒权限") }
        }
    }

    fun openPaymentEditor(subscriptionId: Long, payment: SubscriptionPayment? = null, generation: DataGeneration? = controls.value.generation) {
        if (controls.value.isBusy) return
        val token = eventToken(generation) ?: return
        val subscription = controls.value.subscriptions.firstOrNull { it.id == subscriptionId } ?: return
        if (payment != null && (payment.subscriptionId != subscriptionId || payment !in controls.value.payments)) return
        controls.update { it.copy(paymentEditor = PaymentEditorState(generation = token, isOpen = true, subscriptionId = subscriptionId,
            editingId = payment?.id ?: 0, amount = amountText(payment?.amountMinor ?: subscription.amountMinor, payment?.currency ?: subscription.currency),
            currency = payment?.currency ?: subscription.currency, paidAt = (payment?.paidAt ?: LocalDate.now()).toString(), note = payment?.note.orEmpty())) }
    }

    fun closePaymentEditor(generation: DataGeneration? = controls.value.paymentEditor.generation) {
        if (controls.value.paymentEditor.isSaving || controls.value.paymentEditor.generation != generation) return
        controls.update { it.copy(paymentEditor = PaymentEditorState()) }
        drainPendingNotification()
    }

    fun updatePaymentEditor(generation: DataGeneration? = controls.value.paymentEditor.generation, transform: (PaymentEditorState) -> PaymentEditorState) {
        controls.update {
            if (!it.paymentEditor.isOpen || it.paymentEditor.isSaving || it.paymentEditor.generation != generation) it
            else it.copy(paymentEditor = transform(it.paymentEditor).copy(generation = it.paymentEditor.generation,
                subscriptionId = it.paymentEditor.subscriptionId, editingId = it.paymentEditor.editingId, validationMessage = null))
        }
    }

    fun savePayment(generation: DataGeneration? = controls.value.paymentEditor.generation) {
        val editor = controls.value.paymentEditor
        if (!editor.isOpen || editor.isSaving || controls.value.isBusy || editor.generation != generation) return
        val token = eventToken(generation) ?: return
        val currency = editor.currency.trim().uppercase(Locale.ROOT)
        val amount = SubscriptionRules.parseAmountMinor(editor.amount, currency)
        val date = parseDate(editor.paidAt)
        if (amount == null || date == null) {
            controls.update { it.copy(paymentEditor = editor.copy(validationMessage = "请输入有效的金额、币种和日期（YYYY-MM-DD）")) }; return
        }
        controls.update { it.copy(paymentEditor = editor.copy(isSaving = true)) }
        mutate(token, "保存扣费记录失败") {
            checkNotNull(repository.getSubscription(editor.subscriptionId)) { "订阅已被删除" }
            if (editor.editingId != 0L) require(repository.observeAllPayments().first().any { it.id == editor.editingId && it.subscriptionId == editor.subscriptionId }) { "扣费记录已被删除" }
            repository.savePayment(SubscriptionPayment(editor.editingId, editor.subscriptionId, amount, currency, date, editor.note))
            withContext(Dispatchers.Main.immediate) { controls.update { it.copy(paymentEditor = PaymentEditorState()) } }
        }
    }

    fun deletePayment(id: Long, generation: DataGeneration? = controls.value.generation) {
        val token = eventToken(generation) ?: return
        mutate(token, "删除扣费记录失败") { repository.deletePayment(id) }
    }

    fun prepareCsvExport(generation: DataGeneration? = controls.value.generation): String? {
        val state = controls.value
        val token = eventToken(generation) ?: return null
        if (!state.canExportCsv) return null
        val text = SubscriptionRules.exportCsv(state.subscriptions, state.payments)
        val request = CsvRequest(text, token, ++exportRequest)
        exportSnapshot = request
        controls.update { it.copy(isExportPending = true, exportRequestId = request.request, exportMessage = null) }
        return text
    }

    fun cancelCsvExport(generation: DataGeneration? = exportSnapshot?.generation, requestId: Long? = exportSnapshot?.request) {
        val request = exportSnapshot ?: return
        if (generation != request.generation || requestId != request.request) return
        exportSnapshot = null
        controls.update { it.copy(isExportPending = false, exportRequestId = null) }
    }

    /** File IO outlives composition; its result retains the prepared token and request ID. */
    fun completeCsvExport(generation: DataGeneration? = exportSnapshot?.generation, requestId: Long? = exportSnapshot?.request, write: suspend (String) -> Unit) {
        val request = exportSnapshot ?: return
        if (generation != request.generation || requestId != request.request) return
        if (eventToken(generation) == null) {
            // The picker has returned. Release this request instead of leaving an unfinishable pending export.
            cancelCsvExport(generation, requestId)
            return
        }
        exportSnapshot = null
        activeExport = request
        controls.update { it.copy(isExportPending = false, isExporting = true, exportMessage = null) }
        viewModelScope.launch(dispatcher) {
            try {
                // Validate before invoking the provider, without holding DB admission during file IO.
                val admitted = access.run(request.generation) { withContext(Dispatchers.Main.immediate) { activeExport === request } }
                if (!admitted) return@launch
                write(request.text)
                publishResult(request.generation) { if (activeExport === request) controls.update { it.copy(exportMessage = "CSV 已导出") } }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error !is StaleGenerationException && error !is MaintenanceBusyException)
                    publishResult(request.generation) { if (activeExport === request) controls.update { it.copy(exportMessage = "导出失败，请检查文件位置和可用空间") } }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (activeExport === request) {
                        activeExport = null
                        controls.update { it.copy(isExporting = false, exportRequestId = null) }
                    }
                }
            }
        }
    }

    private fun mutate(token: DataGeneration, failureMessage: String, action: suspend () -> Unit) {
        if (controls.value.isBusy) return
        val operation = Any()
        activeMutation = operation
        controls.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch(dispatcher) {
            var succeeded = false
            try {
                access.run(token) { SubscriptionOperationCoordinator.run { action() } }
                succeeded = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error !is MaintenanceBusyException && error !is StaleGenerationException)
                    publishResult(token) { if (activeMutation === operation) setError(failureMessage) }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (activeMutation === operation) {
                        activeMutation = null
                        controls.update { it.copy(isBusy = false, editor = it.editor.copy(isSaving = false), paymentEditor = it.paymentEditor.copy(isSaving = false)) }
                        // Both outer permits have ended before the next lookup begins.
                        if (succeeded) drainPendingNotification()
                    }
                }
            }
        }
    }

    private suspend fun publishResult(token: DataGeneration, publish: () -> Unit) {
        try { access.publishResult(token, publish) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            withContext(Dispatchers.Main.immediate) {
                if (observedGeneration == token && (controls.value.generation == null || controls.value.generation == token)) generationUnavailable()
            }
        }
    }

    private fun generationUnavailable() {
        generationFailed = true
        readUnavailable("读取数据世代失败，请重试并检查数据")
    }

    private fun readUnavailable(message: String) {
        controls.update { it.copy(isAvailable = false, isLoading = false, hasSubscriptionData = false, hasPaymentData = false,
            statistics = null, errorMessage = message) }
    }

    private fun setError(message: String) {
        controls.update { it.copy(errorMessage = message,
            editor = if (it.editor.isOpen) it.editor.copy(validationMessage = message) else it.editor,
            paymentEditor = if (it.paymentEditor.isOpen) it.paymentEditor.copy(validationMessage = message) else it.paymentEditor) }
    }

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value.trim()) }.getOrNull()
}
