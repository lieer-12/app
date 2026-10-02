package com.example.lifemanager.ui.subscription

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifemanager.domain.usecase.SubscriptionRules
import java.time.LocalDate
import com.example.lifemanager.ui.settings.displayDate
import java.time.temporal.ChronoUnit

@Composable
fun SubscriptionScreen(viewModel: SubscriptionViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    var privacyConfirm by remember { mutableStateOf(false) }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) viewModel.cancelCsvExport()
        else viewModel.completeCsvExport { snapshot ->
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法打开文件" }.use {
                it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                it.write(snapshot.toByteArray(Charsets.UTF_8))
            }
        }
    }
    Column {
        ReminderPermissionBanner()
        if (state.isExporting) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.exportMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
        SubscriptionContent(state, viewModel, onExport = { if (state.canExportCsv) privacyConfirm = true })
    }
    if (privacyConfirm) AlertDialog(
        onDismissRequest = { privacyConfirm = false },
        title = { Text("导出消费数据") },
        text = { Text("文件包含全部订阅名称、金额、备注和实际扣费记录。请保存在可信位置，谨慎分享。非人民币记录也会导出。") },
        confirmButton = { TextButton(enabled = state.canExportCsv, onClick = {
            val snapshot = viewModel.prepareCsvExport()
            privacyConfirm = false
            if (snapshot != null) createCsv.launch("subscriptions-${LocalDate.now()}.csv")
        }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { privacyConfirm = false }) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionContent(state: SubscriptionUiState, viewModel: SubscriptionViewModel, onExport: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("订阅费用") }, actions = { TextButton(onClick = onExport, enabled = state.canExportCsv) { Text("导出 CSV") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { viewModel.openEditor() }) { Icon(Icons.Outlined.Add, contentDescription = "添加订阅") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.selectedTab.ordinal) {
                SubscriptionTab.entries.forEach { tab ->
                    Tab(state.selectedTab == tab, onClick = { viewModel.selectTab(tab) }, text = { Text(if (tab == SubscriptionTab.LIST) "订阅" else "统计") })
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 90.dp)) {
                state.errorMessage?.let { message -> item {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    Row {
                        TextButton(onClick = viewModel::retry) { Text("重试读取") }
                        TextButton(onClick = viewModel::clearError) { Text("关闭提示") }
                    }
                } }
                if (state.isLoading) item { CircularProgressIndicator() }
                item { Text("统计仅汇总 CNY；其他币种可管理和导出，不做汇率换算。", style = MaterialTheme.typography.bodySmall) }
                state.statistics?.let { stats -> item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${stats.currentMonth.month} 人民币费用", style = MaterialTheme.typography.titleMedium)
                            Text("预计：${moneyText(stats.currentMonth.forecastMinor)}")
                            Text("实际：${moneyText(stats.currentMonth.actualMinor)}")
                            Text("实际扣费须手工记录；不会更改预测或计费日期。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } }
                if (state.selectedTab == SubscriptionTab.STATS) {
                    state.statistics?.let { stats -> item { SubscriptionCharts(stats) } }
                } else {
                    if (!state.isLoading && state.subscriptions.isEmpty()) item { Text("还没有订阅。点击右下角添加，开始记录费用。") }
                    items(state.subscriptions, key = { it.id }) { subscription ->
                        val due = SubscriptionRules.effectiveNextBillingDate(subscription, LocalDate.now())
                        Card(onClick = { viewModel.openDetail(subscription.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(subscription.appName, style = MaterialTheme.typography.titleMedium)
                                Text("${moneyText(subscription.amountMinor, subscription.currency)} / ${cycleLabel(subscription.billingCycle)}")
                                Text(if (due != null) "下次扣费 ${displayDate(due)} · 还有 ${ChronoUnit.DAYS.between(LocalDate.now(), due)} 天" else if (!subscription.isActive) "已取消 · 历史记录保留" else "无可用的未来扣费日期")
                                subscription.category?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.detailId != null && !state.editor.isOpen && !state.paymentEditor.isOpen) SubscriptionDetail(state, viewModel)
    val pendingNotificationLabel = state.pendingNotificationId?.let { id ->
        state.subscriptions.firstOrNull { it.id == id }?.let { "${it.appName}（ID: $id）" } ?: "ID: $id"
    }
    if (state.editor.isOpen) SubscriptionEditor(state.editor, viewModel, pendingNotificationLabel)
    if (state.paymentEditor.isOpen) PaymentEditor(state.paymentEditor, viewModel, pendingNotificationLabel)
}

@Composable
private fun ReminderPermissionBanner() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun notificationsAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun exactAllowed() = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    var notifications by remember { mutableStateOf(notificationsAllowed()) }
    var exact by remember { mutableStateOf(exactAllowed()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            notifications = notificationsAllowed(); exact = exactAllowed()
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!notifications || !exact) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(if (!notifications) "通知未允许，订阅提醒无法显示。" else "精确提醒未允许，提醒可能延迟。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val action = if (!notifications) android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    else if (Build.VERSION.SDK_INT >= 31) android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                    else android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                val intent = Intent(action).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                if (action != android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS) intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            }) { Text("打开系统权限设置") }
        }
    }
}
