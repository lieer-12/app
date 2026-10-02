package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import dagger.hilt.android.AndroidEntryPoint
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@AndroidEntryPoint
class SubscriptionReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: SubscriptionRepository
    @Inject lateinit var scheduler: SubscriptionReminderSchedulerContract

    override fun onReceive(context: Context, intent: Intent) {
        val subscriptionId = intent.getLongExtra(EXTRA_SUBSCRIPTION_ID, 0L)
        val daysBefore = intent.getIntExtra(EXTRA_DAYS_BEFORE, 0)
        val dueDate = try {
            intent.getStringExtra(EXTRA_DUE_DATE)?.let(LocalDate::parse)
        } catch (_: DateTimeException) {
            null
        } ?: return
        if (subscriptionId <= 0L || daysBefore !in SubscriptionReminderRules.supportedDays ||
            intent.dataString != ReminderKey.subscriptionData(subscriptionId, daysBefore, dueDate)
        ) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    SubscriptionOperationCoordinator.run {
                        val subscription = repository.getSubscription(subscriptionId)
                        val selectedDays = repository.getReminderDays(subscriptionId)
                        // Keep the snapshot, scheduling and posting protected from UI mutations.
                        SubscriptionReminderDelivery.reconcile(
                            subscriptionId, subscription, selectedDays, daysBefore, dueDate,
                            Instant.now(), ZoneId.systemDefault(), scheduler,
                        ) { current ->
                            SubscriptionNotificationHelper.showReminder(context, current, daysBefore, dueDate)
                        }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                Log.w(TAG, "Subscription reminder timed out; daily reconciliation will retry", error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Room, alarm service and notification permission failures must not crash a background receiver.
                Log.w(TAG, "Unable to deliver subscription reminder", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
        const val EXTRA_DAYS_BEFORE = "subscription_days_before"
        const val EXTRA_DUE_DATE = "subscription_due_date"
        private const val TAG = "SubscriptionReminder"
    }
}
