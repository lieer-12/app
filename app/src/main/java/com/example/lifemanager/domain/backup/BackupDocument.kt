package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.model.AppSettings

/** Lossless storage values, intentionally not the normalized editor/domain models. */
sealed interface BackupValue {
    data class Integer(val value: Long) : BackupValue
    data class Text(val value: String) : BackupValue
    data class Flag(val value: Boolean) : BackupValue
    data object Null : BackupValue
}

data class BackupRow(val fields: Map<String, BackupValue>)
data class BackupPayload(val tables: Map<String, List<BackupRow>>, val settings: AppSettings)
data class BackupDocument(val appVersion: String, val exportedAt: String, val payload: BackupPayload)

data class BackupLimits(val maxBytes: Int = 64 * 1024 * 1024, val maxRows: Int = 100_000) {
    init {
        require(maxBytes in 1..64 * 1024 * 1024)
        require(maxRows in 1..100_000)
    }
}

class BackupValidationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

interface BackupCodec {
    fun encode(document: BackupDocument): ByteArray
    fun decode(bytes: ByteArray): BackupDocument
}

interface BackupRepository {
    suspend fun snapshot(): BackupPayload
}
