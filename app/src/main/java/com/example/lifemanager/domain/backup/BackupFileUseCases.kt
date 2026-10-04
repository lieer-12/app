package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class BackupFileUseCases(
    private val repository: BackupRepository,
    private val codec: BackupCodec,
    private val files: BackupFileStore,
    private val maintenance: MaintenanceCoordinator,
    private val workDispatcher: CoroutineDispatcher,
) {
    suspend fun export(location: BackupLocation, appVersion: String, exportedAt: String): BackupDocument = withContext(workDispatcher) {
        val token = maintenance.capture()
        val payload = maintenance.run(token) { repository.snapshot() }
        // Snapshot admission ends here. Slow provider I/O must not delay a maintenance session.
        writeVerified(location, BackupDocument(appVersion, exportedAt, payload))
    }

    suspend fun writeVerified(location: BackupLocation, document: BackupDocument): BackupDocument = withContext(workDispatcher) {
        val bytes = codec.encode(document)
        currentCoroutineContext().ensureActive()
        files.write(location, bytes)
        currentCoroutineContext().ensureActive()
        val reopened = codec.decode(files.read(location, BackupLimits().maxBytes))
        if (reopened != document) throw BackupFileException("输出与导出的备份不一致，不能视为成功备份")
        currentCoroutineContext().ensureActive()
        document
    }

    suspend fun preview(location: BackupLocation): BackupPreview = withContext(workDispatcher) {
        files.withStagedInput(location, BackupLimits().maxBytes) { bytes ->
            val document = codec.decode(bytes)
            currentCoroutineContext().ensureActive()
            BackupPreview(document)
        }
    }
}
