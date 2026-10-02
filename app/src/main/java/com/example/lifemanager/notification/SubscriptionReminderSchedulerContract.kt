package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Subscription

interface SubscriptionReminderSchedulerContract {
    fun schedule(subscription: Subscription, reminderDays: Set<Int>)
    fun cancel(subscriptionId: Long, reminderDays: Set<Int>)
    fun cancelAll(subscriptionId: Long)
}
