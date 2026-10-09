package com.example.lifemanager.domain.backup

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.ReminderIdentities

/** Called only by the confirmed maintenance workflow. Each operation is one atomic Room commit. */
interface BackupMutationRepository {
    suspend fun reminderIdentities(): ReminderIdentities
    suspend fun replace(expected: DataGeneration, payload: BackupPayload): DataGeneration
    suspend fun clearBusinessData(expected: DataGeneration): DataGeneration
}
