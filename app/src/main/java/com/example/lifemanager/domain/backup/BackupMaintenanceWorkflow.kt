package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.maintenance.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class BackupMaintenanceStage { PREPARING, CHOOSING_PROTECTION, WRITING_PROTECTION, CONFIRMING, COMMITTING, RECONCILING }

sealed interface BackupMaintenanceRequest {
    val expected: DataGeneration
    data class Restore(
        override val expected: DataGeneration,
        val source: BackupLocation,
        val document: BackupDocument,
    ) : BackupMaintenanceRequest
    data class Clear(override val expected: DataGeneration, val createBackup: Boolean) : BackupMaintenanceRequest
}

interface BackupMaintenanceInteraction {
    fun progress(stage: BackupMaintenanceStage)
    suspend fun chooseProtection(): BackupLocation?
    suspend fun confirm(protection: BackupLocation?): Boolean
}

data class BackupMaintenanceResult(val committed: Boolean, val remindersPending: Boolean)

/** Cancellation still propagates, but must not misrepresent a transaction which already committed. */
class BackupCommittedCancellationException(cause: CancellationException) : CancellationException("Data committed; follow-up interrupted") {
    init { initCause(cause) }
}

/** Structured ownership keeps freeze active across picker/confirmation, but never a DB transaction. */
class BackupMaintenanceWorkflow(
    private val snapshots: BackupRepository,
    private val mutations: BackupMutationRepository,
    private val files: BackupFileUseCases,
    private val generations: DataGenerationRepository,
    private val coordinator: MaintenanceCoordinator,
    private val reminders: MaintenanceReminderEffects,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend fun execute(
        request: BackupMaintenanceRequest,
        appVersion: String,
        exportedAt: String,
        interaction: BackupMaintenanceInteraction,
    ): BackupMaintenanceResult {
        var committed = false
        try {
            return withContext(dispatcher) {
                if (request is BackupMaintenanceRequest.Restore) BackupValidator.validate(request.document)
                var entered = false
                var reminderFailure = false
                try {
                    interaction.progress(BackupMaintenanceStage.PREPARING)
                    coordinator.withSession { session ->
                        entered = true
                        if (session.generation != request.expected) throw StaleGenerationException()
                        val old = coordinator.withMaintenance(session) { mutations.reminderIdentities() }
                        val needsBackup = request is BackupMaintenanceRequest.Restore ||
                            (request as BackupMaintenanceRequest.Clear).createBackup
                        var protection: BackupLocation? = null
                        if (needsBackup) {
                            val snapshot = coordinator.withMaintenance(session) { snapshots.snapshot() }
                            interaction.progress(BackupMaintenanceStage.CHOOSING_PROTECTION)
                            protection = interaction.chooseProtection() ?: return@withSession
                            if (request is BackupMaintenanceRequest.Restore && protection == request.source) {
                                throw BackupFileException("当前数据备份必须保存到另一份文件，不能覆盖恢复来源")
                            }
                            interaction.progress(BackupMaintenanceStage.WRITING_PROTECTION)
                            files.writeVerified(protection, BackupDocument(appVersion, exportedAt, snapshot))
                        }
                        interaction.progress(BackupMaintenanceStage.CONFIRMING)
                        if (!interaction.confirm(protection)) return@withSession
                        currentCoroutineContext().ensureActive()
                        interaction.progress(BackupMaintenanceStage.COMMITTING)
                        coordinator.withMaintenance(session) {
                            try {
                                when (request) {
                                    is BackupMaintenanceRequest.Restore -> mutations.replace(session.generation, request.document.payload)
                                    is BackupMaintenanceRequest.Clear -> mutations.clearBusinessData(session.generation)
                                }
                                committed = true
                            } catch (error: Exception) {
                                // Cancellation can arrive at the return hand-off after Room already committed.
                                // While still frozen, only this operation can have advanced the generation.
                                val advanced = withContext(NonCancellable) {
                                    session.generation.value < Long.MAX_VALUE &&
                                        generations.current().value == session.generation.value + 1
                                }
                                if (!advanced) throw error
                                committed = true
                                reminderFailure = true
                                if (error is CancellationException) throw error
                            }
                            try {
                                reminders.clearPrevious(old)
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                reminderFailure = true
                            }
                        }
                    }
                } finally {
                    if (entered) withContext(NonCancellable) {
                        // Also run on cancelled picker, failed backup, rollback, and VM destruction.
                        try { reminders.requestReconciliation() } catch (_: Exception) { reminderFailure = true }
                    }
                }
                if (entered) {
                    interaction.progress(BackupMaintenanceStage.RECONCILING)
                    try {
                        withTimeout(8_000) { reminders.reconcile() }
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        reminderFailure = true
                    }
                }
                BackupMaintenanceResult(committed, reminderFailure)
            }
        } catch (error: CancellationException) {
            if (committed) throw BackupCommittedCancellationException(error)
            throw error
        }
    }
}
