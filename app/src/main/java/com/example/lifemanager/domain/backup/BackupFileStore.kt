package com.example.lifemanager.domain.backup

/** Opaque system document identifier; Android URI handling belongs in the data layer. */
data class BackupLocation(val value: String) {
    init { require(value.isNotBlank()) }
}

class BackupFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface BackupFileStore {
    /** Return only after flush/close. The caller must independently reopen and verify. */
    suspend fun write(location: BackupLocation, bytes: ByteArray)
    suspend fun read(location: BackupLocation, maxBytes: Int): ByteArray

    /** Copy a bounded input to an app-owned private file; remove only that file on every exit. */
    suspend fun <T> withStagedInput(
        location: BackupLocation,
        maxBytes: Int,
        operation: suspend (ByteArray) -> T,
    ): T
}

data class BackupPreview(val document: BackupDocument) {
    val rowCounts: Map<String, Int> = document.payload.tables.mapValues { it.value.size }
    val totalRows: Int = rowCounts.values.sum()
}
