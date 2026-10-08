package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class SubscriptionReminderReceiver(
    private val repositoryProvider: (Context) -> SubscriptionRepository = {
        EntryPointAccessors.fromApplication(it.applicationContext, SubscriptionReminderEntryPoint::class.java)
            .subscriptionRepository()
    },
    private val schedulerProvider: (Context) -> SubscriptionReminderSchedulerContract = {
        EntryPointAccessors.fromApplication(it.applicationContext, SubscriptionReminderEntryPoint::class.java)
            .subscriptionReminderScheduler()
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val runnerProvider: (Context) -> ReminderBroadcastRunner = {
        ReminderBroadcastRunner.fromApplication(it, dispatcher)
    },
    private val onBusy: (Context) -> Unit = { ReminderReconciliationWorker.enqueueImmediate(it) },
    private val settingsProvider: (Context) -> com.example.lifemanager.domain.repository.SettingsRepository = ::reminderSettings,
) : BroadcastReceiver() {
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
        val generation = ReminderGeneration.read(intent)
        // A PendingIntent can survive consumption of its one-shot alarm. This is only a matching
        // operational receipt, not permission to post or mutate Room while maintenance is frozen.
        if (generation != null) {
            try { SubscriptionReminderScheduler.recordArrival(context, subscriptionId, daysBefore, dueDate, generation) }
            catch (_: Exception) { /* Admission still decides whether delivery is allowed. */ }
        }
        startReminderBroadcast(context, runnerProvider, { pendingResult?.finish() }, onBusy, generation) {
            try {
                SubscriptionOperationCoordinator.run {
                    if (SubscriptionReminderScheduler.wasDelivered(context, subscriptionId, daysBefore, dueDate,
                            requireNotNull(generation))) return@run
                    if (!settingsProvider(context).getSettings().subscriptionReminders) {
                        schedulerProvider(context).cancelAll(subscriptionId)
                        return@run
                    }
                    val repository = repositoryProvider(context)
                    val subscription = repository.getSubscription(subscriptionId)
                    val selectedDays = repository.getReminderDays(subscriptionId)
                    // Global admission encloses the module lock, reads, alarm changes and posting.
                    SubscriptionReminderDelivery.reconcile(
                        subscriptionId, subscription, selectedDays, daysBefore, dueDate,
                        Instant.now(), ZoneId.systemDefault(), schedulerProvider(context),
                    ) { current ->
                        if (settingsProvider(context).getSettings().subscriptionReminders) {
                            SubscriptionNotificationHelper.showReminder(context, current, daysBefore, dueDate, requireNotNull(generation))
                            SubscriptionReminderScheduler.recordDelivery(context, subscriptionId, daysBefore, dueDate, generation)
                        }
                    }
                }
            } catch (error: Exception) {
                try { onBusy(context.applicationContext) } catch (queueError: Exception) { error.addSuppressed(queueError) }
                throw error
            }
        }
    }

    companion object {
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
        const val EXTRA_DAYS_BEFORE = "subscription_days_before"
        const val EXTRA_DUE_DATE = "subscription_due_date"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SubscriptionReminderEntryPoint {
    fun subscriptionRepository(): SubscriptionRepository
    fun subscriptionReminderScheduler(): SubscriptionReminderSchedulerContract
}
