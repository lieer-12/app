package com.example.lifemanager.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.model.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(state, viewModel::setTheme, viewModel::retry, onBack,
        viewModel::setDateFormat, viewModel::setDefaultCurrency, viewModel::setDefaultReminderDay)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(state: SettingsUiState, onThemeChanged: (ThemeMode) -> Unit, onRetry: () -> Unit, onBack: () -> Unit,
    onDateFormatChanged: (DateFormat) -> Unit = {}, onCurrencyChanged: (String) -> Unit = {},
    onReminderDayChanged: (Int, Boolean) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val notificationGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val exactAlarmGranted = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isLoading || state.isSaving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry, enabled = !state.isSaving) { Text("重新读取设置") }
            }
            state.settings?.let { saved ->
                Text("主题", style = MaterialTheme.typography.titleLarge)
                val editable = state.isAvailable && !state.isLoading && !state.isSaving
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (mode, label) ->
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = saved.theme == mode, enabled = editable, role = Role.RadioButton,
                        onClick = { onThemeChanged(mode) },
                    ), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = saved.theme == mode, onClick = null, enabled = editable)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text("日期格式", style = MaterialTheme.typography.titleLarge)
                listOf(DateFormat.YMD to "YYYY-MM-DD", DateFormat.MDY to "MM-DD-YYYY", DateFormat.DMY to "DD-MM-YYYY").forEach { (format, label) ->
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = saved.dateFormat == format, enabled = editable, role = Role.RadioButton,
                        onClick = { onDateFormatChanged(format) },
                    ), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = saved.dateFormat == format, onClick = null, enabled = editable)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text("仅改变日期展示；订阅日期输入和 CSV 导出仍使用 YYYY-MM-DD。", style = MaterialTheme.typography.bodySmall)
                Text("新建订阅偏好", style = MaterialTheme.typography.titleLarge)
                var currency by remember(saved.defaultCurrency) { mutableStateOf(saved.defaultCurrency) }
                OutlinedTextField(currency, { currency = it }, enabled = editable, singleLine = true,
                    label = { Text("新订阅默认币种（ISO 4217）") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onCurrencyChanged(currency) }, enabled = editable) { Text("保存默认币种") }
                Text("新建订阅默认提醒", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 7).forEach { day ->
                        FilterChip(selected = day in saved.defaultReminderDays, enabled = editable,
                            onClick = { onReminderDayChanged(day, day !in saved.defaultReminderDays) },
                            label = { Text("提前 $day 天") })
                    }
                }
                Text(if (saved.defaultReminderDays.isEmpty()) "当前默认不安排提醒" else "提醒使用本地 09:00；实际投递仍需系统权限。")
                Text("只用于之后新建的订阅；不会换算现有金额或覆盖现有提醒，统计仍仅汇总 CNY。", style = MaterialTheme.typography.bodySmall)
            }
            Text("已完成模块", style = MaterialTheme.typography.titleLarge)
            Text("当前已启用待办、日程、打卡和订阅费用管理。打卡提醒、暂停和归档尚未实现。")
            Text(if (notificationGranted) "通知权限：已允许" else "通知权限：未允许")
            if (Build.VERSION.SDK_INT >= 33 && !notificationGranted) {
                Button(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("允许本地提醒")
                }
            }
            Text(if (exactAlarmGranted) "精确提醒：已允许" else "精确提醒：未允许，将使用系统兜底调度")
            if (Build.VERSION.SDK_INT >= 31 && !exactAlarmGranted) {
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }) { Text("允许精确提醒") }
            }
            Button(onClick = onBack) { Text("返回待办") }
        }
    }
}
