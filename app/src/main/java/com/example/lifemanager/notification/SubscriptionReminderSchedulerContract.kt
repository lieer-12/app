package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Subscription

interface SubscriptionReminderSchedulerContract {
    fun schedule(subscription: Subscription, reminderDays: Set<Int>): Unit =
        error("Android scheduling requires suspend scheduleCurrent under global/module admission")
    suspend fun scheduleCurrent(subscription: Subscription, reminderDays: Set<Int>) = schedule(subscription, reminderDays)
    fun cancel(subscriptionId: Long, reminderDays: Set<Int>)
    fun cancelAll(subscriptionId: Long)
    fun cancelObsolete(currentIds: Set<Long>) = Unit
    fun acknowledgeArrival(subscriptionId: Long, daysBefore: Int, dueDate: java.time.LocalDate, generation: com.example.lifemanager.domain.maintenance.DataGeneration) = Unit
}
