package com.example.lifemanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.BuildConfig
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.ui.common.GenerationAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BackupAction { EXPORT, RESTORE, CLEAR }
enum class BackupStep {
    IDLE, EXPORT_NOTICE, PICK_SOURCE, READING_SOURCE, RESTORE_PREVIEW, CLEAR_CHOICE,
    PREPARING, PICK_OUTPUT, WRITING_BACKUP, FINAL_CONFIRM, COMMITTING, RECONCILING, FINISHED, ERROR,
}

data class BackupSettingsUiState(
    val operationId: String? = null,
    val action: BackupAction? = null,
    val step: BackupStep = BackupStep.IDLE,
    val rowCounts: Map<String, Int> = emptyMap(),
    val sourceDate: String? = null,
    val protection: String? = null,
    val message: String? = null,
) {
    val busy get() = step !in setOf(BackupStep.IDLE, BackupStep.FINISHED, BackupStep.ERROR)
    val canCancel get() = busy && step !in setOf(BackupStep.COMMITTING, BackupStep.RECONCILING)
}

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val files: BackupFileUseCases,
    private val workflow: BackupMaintenanceWorkflow,
    private val access: GenerationAccess,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(BackupSettingsUiState())
    val uiState = state.asStateFlow()
    private var active: Operation? = null

    fun start(action: BackupAction, generation: DataGeneration?) {
        if (state.value.busy) return
        val token = try { access.eventToken(generation) } catch (_: IllegalStateException) { return }
        val operation = Operation(UUID.randomUUID().toString(), action, token)
        active = operation
        state.value = BackupSettingsUiState(operation.id, action, BackupStep.PREPARING)
        operation.job = viewModelScope.launch(dispatcher, start = CoroutineStart.LAZY) {
            try {
                access.run(token) { currentCoroutineContext().ensureActive() }
                when (action) {
                    BackupAction.EXPORT -> export(operation)
                    BackupAction.RESTORE -> restore(operation)
                    BackupAction.CLEAR -> clear(operation)
                }
            } catch (error: CancellationException) {
                val message = if (operation.committed || error is BackupCommittedCancellationException) {
                    val done = if (operation.action == BackupAction.RESTORE) "数据已恢复" else "业务数据已清空，设置已保留"
                    "$done，但后续处理被中断，请重新打开应用检查提醒"
                } else "已取消"
                withContext(NonCancellable + Dispatchers.Main.immediate) { finish(operation, message) }
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    val message = when (error) {
                        is BackupValidationException -> "备份无法使用：${error.message}"
                        is BackupFileException -> error.message ?: "文件操作失败，请重新选择文件"
                        is StaleGenerationException -> "数据已更换，请重新开始操作"
                        is MaintenanceBusyException -> "正在处理另一项数据维护，请稍后重试"
                        else -> "操作未完成，请重试或重新打开页面检查数据"
                    }
                    finish(operation, message, failed = true)
                }
            }
        }
        operation.job!!.start()
    }

    fun sourceSelected(id: String, location: String?) {
        val operation = matching(id, BackupStep.PICK_SOURCE) ?: return
        operation.source.complete(location?.let(::BackupLocation))
    }

    fun outputSelected(id: String, location: String?) {
        val operation = matching(id, BackupStep.PICK_OUTPUT) ?: return
        operation.output?.complete(location?.let(::BackupLocation))
    }

    fun answerFirst(id: String, choice: Boolean?) {
        val operation = active?.takeIf { it.id == id } ?: return
        if (state.value.step !in setOf(BackupStep.EXPORT_NOTICE, BackupStep.RESTORE_PREVIEW, BackupStep.CLEAR_CHOICE)) return
        operation.first.complete(choice)
    }

    fun answerFinal(id: String, confirmed: Boolean) {
        matching(id, BackupStep.FINAL_CONFIRM)?.final?.complete(confirmed)
    }

    fun pickerFailed(id: String) {
        val operation = active?.takeIf { it.id == id } ?: return
        val error = BackupFileException("无法打开系统文件选择器，请检查设备的文件应用后重试")
        when (state.value.step) {
            BackupStep.PICK_SOURCE -> operation.source.completeExceptionally(error)
            BackupStep.PICK_OUTPUT -> operation.output?.completeExceptionally(error)
            else -> Unit
        }
    }

    fun cancel(id: String) {
        active?.takeIf { it.id == id && state.value.canCancel }?.job?.cancel()
    }

    private suspend fun export(operation: Operation) {
        publish(operation, BackupStep.EXPORT_NOTICE)
        if (operation.first.await() != true) return complete(operation, "已取消")
        val location = chooseOutput(operation) ?: return complete(operation, "已取消")
        publish(operation, BackupStep.WRITING_BACKUP)
        files.export(location, BuildConfig.VERSION_NAME, Instant.now().toString(), operation.generation)
        val published = access.publishResult(operation.generation) { finish(operation, "备份已导出并验证，可用于恢复") }
        if (!published) complete(operation, "先前备份已导出；应用数据随后发生了更换，请核对文件")
    }

    private suspend fun restore(operation: Operation) {
        publish(operation, BackupStep.PICK_SOURCE)
        val source = operation.source.await() ?: return complete(operation, "已取消")
        publish(operation, BackupStep.READING_SOURCE)
        val preview = files.preview(source)
        state.update { if (it.operationId == operation.id) it.copy(rowCounts = preview.rowCounts, sourceDate = preview.document.exportedAt) else it }
        publish(operation, BackupStep.RESTORE_PREVIEW)
        if (operation.first.await() != true) return complete(operation, "已取消")
        maintain(operation, BackupMaintenanceRequest.Restore(operation.generation, source, preview.document))
    }

    private suspend fun clear(operation: Operation) {
        publish(operation, BackupStep.CLEAR_CHOICE)
        val createBackup = operation.first.await() ?: return complete(operation, "已取消")
        maintain(operation, BackupMaintenanceRequest.Clear(operation.generation, createBackup))
    }

    private suspend fun maintain(operation: Operation, request: BackupMaintenanceRequest) {
        val result = workflow.execute(request, BuildConfig.VERSION_NAME, Instant.now().toString(), object : BackupMaintenanceInteraction {
            override fun progress(stage: BackupMaintenanceStage) {
                publish(operation, when (stage) {
                    BackupMaintenanceStage.PREPARING -> BackupStep.PREPARING
                    BackupMaintenanceStage.CHOOSING_PROTECTION -> BackupStep.PREPARING
                    BackupMaintenanceStage.WRITING_PROTECTION -> BackupStep.WRITING_BACKUP
                    BackupMaintenanceStage.CONFIRMING -> BackupStep.PREPARING
                    BackupMaintenanceStage.COMMITTING -> BackupStep.COMMITTING
                    BackupMaintenanceStage.RECONCILING -> BackupStep.RECONCILING
                })
            }
            override suspend fun chooseProtection() = chooseOutput(operation)
            override suspend fun confirm(protection: BackupLocation?): Boolean {
                state.update { if (it.operationId == operation.id) it.copy(protection = protection?.value) else it }
                publish(operation, BackupStep.FINAL_CONFIRM)
                return operation.final.await()
            }
        })
        // Remember the durable outcome before the cancellable Main publication hand-off.
        operation.committed = result.committed
        val message = if (!result.committed) "已取消，原数据和草稿已保留" else {
            val done = if (operation.action == BackupAction.RESTORE) "数据已恢复" else "业务数据已清空，设置已保留"
            if (result.remindersPending) "$done，但部分提醒处理失败，请重新打开应用后检查提醒" else "$done，提醒已重新校准"
        }
        complete(operation, message)
    }

    private suspend fun chooseOutput(operation: Operation): BackupLocation? {
        val response = CompletableDeferred<BackupLocation?>()
        operation.output = response
        publish(operation, BackupStep.PICK_OUTPUT)
        return response.await()
    }

    private fun publish(operation: Operation, step: BackupStep) {
        state.update { if (it.operationId == operation.id) it.copy(step = step, message = null) else it }
    }

    private suspend fun complete(operation: Operation, message: String) = withContext(Dispatchers.Main.immediate) { finish(operation, message) }

    private fun finish(operation: Operation, message: String, failed: Boolean = false) {
        if (active !== operation) return
        active = null
        state.update { it.copy(step = if (failed) BackupStep.ERROR else BackupStep.FINISHED, message = message) }
    }

    private fun matching(id: String, step: BackupStep) = active?.takeIf { it.id == id && state.value.step == step }

    private class Operation(val id: String, val action: BackupAction, val generation: DataGeneration) {
        var job: Job? = null
        var committed = false
        val source = CompletableDeferred<BackupLocation?>()
        var output: CompletableDeferred<BackupLocation?>? = null
        val first = CompletableDeferred<Boolean?>()
        val final = CompletableDeferred<Boolean>()
    }
}
