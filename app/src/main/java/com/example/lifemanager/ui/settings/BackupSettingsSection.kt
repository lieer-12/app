package com.example.lifemanager.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.maintenance.DataGeneration
import java.time.LocalDate

@Composable
fun BackupSettingsSection(
    state: BackupSettingsUiState,
    enabled: Boolean,
    generation: DataGeneration?,
    model: BackupSettingsViewModel,
) {
    // These IDs survive rotation with the launchers. They are never used to recreate VM operations.
    var sourceRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var outputRequest by rememberSaveable { mutableStateOf<String?>(null) }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        sourceRequest?.let { id -> sourceRequest = null; model.sourceSelected(id, uri?.toString()) }
    }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        outputRequest?.let { id -> outputRequest = null; model.outputSelected(id, uri?.toString()) }
    }
    LaunchedEffect(state.operationId, state.step) {
        val id = state.operationId ?: return@LaunchedEffect
        if (model.uiState.value.operationId != id || model.uiState.value.step != state.step) return@LaunchedEffect
        when (state.step) {
            BackupStep.PICK_SOURCE -> if (sourceRequest != id) {
                if (sourceRequest != null) { model.pickerFailed(id); return@LaunchedEffect }
                sourceRequest = id
                try { sourcePicker.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
                catch (_: Exception) { sourceRequest = null; model.pickerFailed(id) }
            }
            BackupStep.PICK_OUTPUT -> if (outputRequest != id) {
                if (outputRequest != null) { model.pickerFailed(id); return@LaunchedEffect }
                outputRequest = id
                val prefix = if (state.action == BackupAction.EXPORT) "life-manager-backup" else "life-manager-before-change"
                try { outputPicker.launch("$prefix-${LocalDate.now()}.json") }
                catch (_: Exception) { outputRequest = null; model.pickerFailed(id) }
            }
            else -> Unit
        }
    }
    BackupSettingsControls(state, enabled, { model.start(it, generation) }, { state.operationId?.let(model::cancel) })
    BackupSettingsDialogs(state, model::answerFirst, model::answerFinal)
}

@Composable
fun BackupSettingsControls(state: BackupSettingsUiState, enabled: Boolean, onStart: (BackupAction) -> Unit, onCancel: () -> Unit) {
    Text("数据备份与恢复", style = MaterialTheme.typography.titleLarge)
    Text("备份包含四个模块及设置，为未加密的 JSON 文件。请选择可信保存位置，谨慎分享。", style = MaterialTheme.typography.bodyMedium)
    Button(onClick = { onStart(BackupAction.EXPORT) }, enabled = enabled && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份") }
    OutlinedButton(onClick = { onStart(BackupAction.RESTORE) }, enabled = enabled && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("从备份恢复") }
    OutlinedButton(onClick = { onStart(BackupAction.CLEAR) }, enabled = enabled && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("清空业务数据") }
    if (state.busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(when (state.step) {
            BackupStep.PICK_SOURCE -> "请选择恢复来源文件"
            BackupStep.READING_SOURCE -> "正在读取和校验备份"
            BackupStep.PICK_OUTPUT -> if (state.action == BackupAction.EXPORT) "请选择备份保存位置" else "请选择当前数据的保护备份位置"
            BackupStep.WRITING_BACKUP -> "正在写入并重新读取验证备份"
            BackupStep.COMMITTING -> "正在更新数据，请稍候"
            BackupStep.RECONCILING -> "正在重新安排本地提醒"
            BackupStep.FINAL_CONFIRM -> "当前数据已准备好，请完成最终确认"
            else -> "正在准备，请按提示完成操作"
        })
        if (state.canCancel) TextButton(onClick = onCancel) { Text("取消本次操作") }
    }
    state.message?.let { Text(it, color = if (state.step == BackupStep.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
}

@Composable
fun BackupSettingsDialogs(
    state: BackupSettingsUiState,
    answerFirst: (String, Boolean?) -> Unit,
    answerFinal: (String, Boolean) -> Unit,
) {
    val id = state.operationId ?: return
    when (state.step) {
        BackupStep.EXPORT_NOTICE -> AlertDialog(
            onDismissRequest = { answerFirst(id, false) }, title = { Text("导出明文备份") },
            text = { Text("文件包含待办、日程、打卡、订阅及设置，任何获得文件的人都可以读取。请保存到可信位置。") },
            confirmButton = { TextButton(onClick = { answerFirst(id, true) }) { Text("选择保存位置") } },
            dismissButton = { TextButton(onClick = { answerFirst(id, false) }) { Text("取消") } },
        )
        BackupStep.RESTORE_PREVIEW -> AlertDialog(
            onDismissRequest = { answerFirst(id, false) }, title = { Text("恢复备份预览") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导出时间：${state.sourceDate.orEmpty()}")
                    backupTableLabels.forEach { (name, label) -> Text("$label：${state.rowCounts[name] ?: 0}") }
                    Text("将完整替换四个模块的数据与设置，不合并记录。未保存草稿在恢复成功后会被清除。接下来先备份当前数据，再进行最终确认。")
                }
            },
            confirmButton = { TextButton(onClick = { answerFirst(id, true) }) { Text("同意并备份当前数据") } },
            dismissButton = { TextButton(onClick = { answerFirst(id, false) }) { Text("取消") } },
        )
        BackupStep.CLEAR_CHOICE -> AlertDialog(
            onDismissRequest = { answerFirst(id, null) }, title = { Text("清空前先保留备份") },
            text = { Text("将删除待办、标签、日程、打卡、订阅与关联记录，保留主题等设置。未保存草稿会在清空成功后失效。建议先保存完整备份，下一步还有最终确认。") },
            confirmButton = {
                Column {
                    TextButton(onClick = { answerFirst(id, true) }) { Text("先备份当前数据") }
                    TextButton(onClick = { answerFirst(id, false) }) { Text("跳过备份，继续确认") }
                }
            },
            dismissButton = { TextButton(onClick = { answerFirst(id, null) }) { Text("取消") } },
        )
        BackupStep.FINAL_CONFIRM -> AlertDialog(
            onDismissRequest = { answerFinal(id, false) },
            title = { Text(if (state.action == BackupAction.RESTORE) "最终确认：替换全部数据" else "最终确认：清空业务数据") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.protection?.let {
                        val name = Uri.parse(it).lastPathSegment?.substringAfterLast('/') ?: "已选择的文件"
                        Text("当前数据的保护备份已验证：$name")
                    } ?: Text("你已选择跳过备份。删除后只能使用此前保存的备份恢复。")
                    if (state.action == BackupAction.RESTORE) {
                        Text("将以 ${state.sourceDate.orEmpty()} 的备份替换当前四模块及设置，共 ${state.rowCounts.values.sum()} 条业务记录。")
                    } else Text("将删除当前四模块全部业务记录和关联数据，保留用户设置。")
                    Text("确认后未保存的草稿也会失效；取消则保留当前数据和草稿。")
                }
            },
            confirmButton = {
                TextButton(onClick = { answerFinal(id, true) }) {
                    Text(if (state.action == BackupAction.RESTORE) "确认替换全部数据" else "确认清空业务数据", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { answerFinal(id, false) }) { Text("取消，保留原数据") } },
        )
        else -> Unit
    }
}

private val backupTableLabels = listOf(
    "todos" to "待办", "tags" to "标签", "todo_tag_cross_ref" to "待办标签关联",
    "schedules" to "日程", "schedule_exceptions" to "日程例外", "habits" to "习惯",
    "habit_records" to "打卡记录", "subscriptions" to "订阅", "subscription_payments" to "扣费记录",
    "subscription_reminders" to "订阅提醒配置",
)
