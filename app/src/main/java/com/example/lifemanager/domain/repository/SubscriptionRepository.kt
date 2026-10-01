package com.example.lifemanager.domain.repository

import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import kotlinx.coroutines.flow.Flow

interface SubscriptionRepository {
    fun observeSubscriptions(): Flow<List<Subscription>>
    fun observeAllPayments(): Flow<List<SubscriptionPayment>>
    fun observePayments(subscriptionId: Long): Flow<List<SubscriptionPayment>>
    fun observeReminders(subscriptionId: Long): Flow<Set<Int>>
    suspend fun getSubscriptions(): List<Subscription>
    suspend fun getSubscription(id: Long): Subscription?
    suspend fun getReminderDays(subscriptionId: Long): Set<Int>
    suspend fun saveSubscription(subscription: Subscription, reminderDays: Set<Int>): Long
    suspend fun savePayment(payment: SubscriptionPayment): Long
    suspend fun deletePayment(id: Long)
    suspend fun deleteSubscription(id: Long)
}
