package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Subscription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Android-free delivery flow; caller holds the operation gate from Room reads through notification. */
object SubscriptionReminderDelivery {
    suspend fun reconcile(
        subscriptionId: Long,
        subscription: Subscription?,
        selectedDays: Set<Int>,
        daysBefore: Int,
        dueDate: LocalDate,
        now: Instant,
        zone: ZoneId,
        scheduler: SubscriptionReminderSchedulerContract,
        showReminder: suspend (Subscription) -> Unit,
    ) {
        val valid = SubscriptionReminderRules.matchesCurrentReminder(
            subscription, selectedDays, daysBefore, dueDate, now, zone,
        )
        var rearmFailure: Exception? = null
        try {
            // Retain a consumed occurrence while the suspending generation/settings reads run.
            // A cancellation here must leave today's still-unposted notification recoverable.
            if (subscription == null) scheduler.cancelAll(subscriptionId)
            else scheduler.scheduleCurrent(subscription, selectedDays)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            rearmFailure = error
        }
        currentCoroutineContext().ensureActive()
        // A failed rearm must not suppress a qualified notification; cancellation must.
        if (valid && subscription != null) {
            showReminder(subscription)
            // Only delivery consumes the occurrence; a retry before posting can still recover it.
            try {
                scheduler.cancel(subscriptionId, setOf(daysBefore))
                scheduler.scheduleCurrent(subscription, selectedDays)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (rearmFailure == null) rearmFailure = error else rearmFailure.addSuppressed(error)
            }
        }
        rearmFailure?.let { throw it }
    }
}
