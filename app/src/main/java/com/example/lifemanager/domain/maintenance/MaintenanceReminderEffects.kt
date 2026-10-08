package com.example.lifemanager.domain.maintenance

data class ReminderIdentities(
    val todoIds: List<Long>,
    val scheduleIds: List<Long>,
    val subscriptionIds: List<Long>,
)

/** Platform work is deliberately outside the Room commit. */
interface MaintenanceReminderEffects {
    suspend fun clearPrevious(identities: ReminderIdentities)
    suspend fun reconcile()
    fun requestReconciliation()
}
