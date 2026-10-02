package com.example.lifemanager.ui.subscription

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.usecase.SubscriptionRules
import java.time.LocalDate
import com.example.lifemanager.ui.settings.displayDate

@Composable
internal fun SubscriptionEditor(editor: SubscriptionEditorState, model: SubscriptionViewModel, pendingNotificationLabel: String? = null) {
    AlertDialog(onDismissRequest = model::closeEditor, title = {
        EditorDialogTitle(if (editor.original == null) "新建订阅" else "编辑订阅", pendingNotificationLabel)
    },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Netflix", "Spotify", "iCloud", "微信读书").forEach { name ->
                        SuggestionChip(onClick = { model.updateEditor { it.copy(name = name) } }, label = { Text(name) }, enabled = !editor.isSaving)
                    }
                } }
                item { EditField("订阅名称", editor.name, !editor.isSaving) { value -> model.updateEditor { it.copy(name = value) } } }
                item { EditField("金额", editor.amount, !editor.isSaving) { value -> model.updateEditor { it.copy(amount = value) } } }
                item { EditField("币种（ISO 4217，例如 CNY）", editor.currency, !editor.isSaving && !editor.isLoadingReminders && editor.preferencesError == null) { value -> model.updateEditor { it.copy(currency = value) } } }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BillingCycle.entries.forEach { cycle -> FilterChip(editor.billingCycle == cycle,
                        onClick = { model.updateEditor { it.copy(billingCycle = cycle) } }, label = { Text(cycleLabel(cycle)) }, enabled = !editor.isSaving) }
                } }
                item { EditField("下次扣费日期（YYYY-MM-DD）", editor.nextBillingDate, !editor.isSaving) { value -> model.updateEditor { it.copy(nextBillingDate = value) } } }
                item { EditField("开始日期（YYYY-MM-DD）", editor.startDate, !editor.isSaving) { value -> model.updateEditor { it.copy(startDate = value) } } }
                item { EditField("分类（可选）", editor.category, !editor.isSaving) { value -> model.updateEditor { it.copy(category = value) } } }
                item { EditField("备注（可选）", editor.note, !editor.isSaving) { value -> model.updateEditor { it.copy(note = value) } } }
                item { Text("扣费前提醒（本地 09:00，可多选）") }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 7).forEach { day -> FilterChip(day in editor.reminderDays, onClick = {
                        model.updateEditor { it.copy(reminderDays = if (day in it.reminderDays) it.reminderDays - day else it.reminderDays + day) }
                    }, label = { Text("$day 天") }, enabled = !editor.isSaving && !editor.isLoadingReminders && editor.preferencesError == null) }
                } }
                if (editor.isLoadingReminders) item { Text(if (editor.original == null) "正在读取新建订阅偏好…" else "正在读取提醒配置…") }
                editor.preferencesError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (editor.original?.isActive == false) item { Text("此订阅已取消；保存编辑后仍保持取消状态。") }
                editor.validationMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { TextButton(onClick = model::saveSubscription, enabled = !editor.isSaving && !editor.isLoadingReminders && editor.preferencesError == null) { Text("保存订阅") } },
        dismissButton = { TextButton(onClick = model::closeEditor, enabled = !editor.isSaving) { Text("取消") } },
    )
}

@Composable
internal fun PaymentEditor(editor: PaymentEditorState, model: SubscriptionViewModel, pendingNotificationLabel: String? = null) {
    AlertDialog(onDismissRequest = model::closePaymentEditor, title = {
        EditorDialogTitle(if (editor.editingId == 0L) "记录实际扣费" else "编辑实际扣费", pendingNotificationLabel)
    },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { EditField("实际金额", editor.amount, !editor.isSaving) { value -> model.updatePaymentEditor { it.copy(amount = value) } } }
            item { EditField("币种", editor.currency, !editor.isSaving) { value -> model.updatePaymentEditor { it.copy(currency = value) } } }
            item { EditField("扣费日期（YYYY-MM-DD）", editor.paidAt, !editor.isSaving) { value -> model.updatePaymentEditor { it.copy(paidAt = value) } } }
            item { EditField("扣费备注", editor.note, !editor.isSaving) { value -> model.updatePaymentEditor { it.copy(note = value) } } }
            item { Text("该记录只更新实际支出，不改变订阅计费日期。") }
            editor.validationMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        } },
        confirmButton = { TextButton(onClick = model::savePayment, enabled = !editor.isSaving) { Text("保存扣费") } },
        dismissButton = { TextButton(onClick = model::closePaymentEditor, enabled = !editor.isSaving) { Text("取消") } },
    )
}

@Composable
private fun EditorDialogTitle(title: String, pendingNotificationLabel: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title)
        pendingNotificationLabel?.let { label ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("通知请求：$label", style = MaterialTheme.typography.titleSmall)
                    Text("保存或关闭当前编辑后查看", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionDetail(state: SubscriptionUiState, model: SubscriptionViewModel) {
    val subscription = state.subscriptions.firstOrNull { it.id == state.detailId }
    var confirm by remember(state.detailId) { mutableStateOf<String?>(null) }
    var deletingPayment by remember(state.detailId) { mutableStateOf<Long?>(null) }
    if (subscription == null) {
        AlertDialog(onDismissRequest = model::closeDetail, title = { Text("订阅详情") }, text = { Text(if (state.isLoading) "正在读取…" else "订阅不存在或已删除") }, confirmButton = { TextButton(onClick = model::closeDetail) { Text("关闭") } })
        return
    }
    AlertDialog(onDismissRequest = model::closeDetail, title = { Text(subscription.appName) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.errorMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Text("${moneyText(subscription.amountMinor, subscription.currency)} / ${cycleLabel(subscription.billingCycle)}") }
            item { Text("开始：${displayDate(subscription.startDate)}") }
            item { Text("下次扣费：${SubscriptionRules.effectiveNextBillingDate(subscription, LocalDate.now())?.let { displayDate(it) } ?: "已停止"}") }
            item { Text("${subscription.category ?: "未分类"}　${subscription.note.orEmpty()}") }
            item { Text("提醒：${state.detailReminderDays.sorted().joinToString { "提前 $it 天" }.ifEmpty { "未设置" }}") }
            subscription.cancelDate?.let { day -> item { Text("取消日期：${displayDate(day)}") } }
            item { Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { model.openEditor(subscription) }, enabled = !state.isBusy) { Text("编辑") }
                TextButton(onClick = { confirm = if (subscription.isActive) "cancel" else "restore" }, enabled = !state.isBusy) { Text(if (subscription.isActive) "取消订阅" else "恢复订阅") }
                TextButton(onClick = { confirm = "delete" }, enabled = !state.isBusy) { Text("删除订阅") }
            } }
            item { Text("实际扣费历史", style = MaterialTheme.typography.titleMedium) }
            item { TextButton(onClick = { model.openPaymentEditor(subscription.id) }, enabled = !state.isBusy) { Text("记录实际扣费") } }
            val payments = state.payments.filter { it.subscriptionId == subscription.id }.sortedWith(compareByDescending<com.example.lifemanager.domain.model.SubscriptionPayment> { it.paidAt }.thenByDescending { it.id })
            if (payments.isEmpty()) item { Text("还没有手工扣费记录") }
            payments.forEach { payment -> item {
                Text("${displayDate(payment.paidAt)}　${moneyText(payment.amountMinor, payment.currency)}")
                payment.note?.let { Text(it) }
                Row {
                    TextButton(onClick = { model.openPaymentEditor(subscription.id, payment) }, enabled = !state.isBusy) { Text("编辑扣费") }
                    TextButton(onClick = { deletingPayment = payment.id }, enabled = !state.isBusy) { Text("删除扣费") }
                }
            } }
        }
    }, confirmButton = { TextButton(onClick = model::closeDetail) { Text("关闭") } })
    confirm?.let { action -> ConfirmAction(
        title = when (action) { "delete" -> "删除订阅？"; "cancel" -> "取消订阅？"; else -> "恢复订阅？" },
        message = when (action) { "delete" -> "订阅、全部实际扣费历史和提醒配置将被删除，无法撤销。"; "cancel" -> "停止未来提醒与预测，保留订阅和全部扣费历史。"; else -> "恢复费用预测，并按原配置重新安排未来提醒。" },
        onDismiss = { confirm = null }, onConfirm = {
            when (action) { "delete" -> model.deleteSubscription(subscription.id); "cancel" -> model.cancelSubscription(subscription.id); else -> model.restoreSubscription(subscription.id) }
            confirm = null
        },
    ) }
    deletingPayment?.let { id -> ConfirmAction("删除扣费记录？", "只删除这条实际扣费记录，预计费用不会改变。", { deletingPayment = null }, { model.deletePayment(id); deletingPayment = null }) }
}

@Composable
private fun EditField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled)
}

@Composable
private fun ConfirmAction(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
