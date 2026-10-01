package com.example.lifemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.lifemanager.data.local.entity.SubscriptionEntity
import com.example.lifemanager.data.local.entity.SubscriptionPaymentEntity
import com.example.lifemanager.data.local.entity.SubscriptionReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY nextBillingDate, id")
    abstract fun observeSubscriptions(): Flow<List<SubscriptionEntity>>
    @Query("SELECT * FROM subscriptions ORDER BY id")
    abstract suspend fun getAll(): List<SubscriptionEntity>
    @Query("SELECT * FROM subscriptions WHERE id = :id")
    abstract suspend fun getById(id: Long): SubscriptionEntity?
    @Insert abstract suspend fun insertSubscription(value: SubscriptionEntity): Long
    @Update abstract suspend fun updateSubscription(value: SubscriptionEntity): Int
    @Transaction
    open suspend fun upsertSubscription(value: SubscriptionEntity): Long {
        if (value.id == 0L) return insertSubscription(value)
        check(updateSubscription(value) == 1) { "订阅已被删除" }
        return value.id
    }
    @Query("DELETE FROM subscriptions WHERE id = :id")
    abstract suspend fun deleteSubscription(id: Long)
    @Query("SELECT * FROM subscription_payments WHERE subscriptionId = :id ORDER BY paidAt DESC, id DESC")
    abstract fun observePayments(id: Long): Flow<List<SubscriptionPaymentEntity>>
    @Query("SELECT * FROM subscription_payments ORDER BY paidAt DESC, id DESC")
    abstract fun observeAllPayments(): Flow<List<SubscriptionPaymentEntity>>
    @Insert abstract suspend fun insertPayment(value: SubscriptionPaymentEntity): Long
    @Update abstract suspend fun updatePayment(value: SubscriptionPaymentEntity): Int
    @Query("DELETE FROM subscription_payments WHERE id = :id")
    abstract suspend fun deletePayment(id: Long)
    @Query("SELECT daysBefore FROM subscription_reminders WHERE subscriptionId = :id ORDER BY daysBefore")
    abstract fun observeReminderDays(id: Long): Flow<List<Int>>
    @Query("SELECT daysBefore FROM subscription_reminders WHERE subscriptionId = :id ORDER BY daysBefore")
    abstract suspend fun getReminderDays(id: Long): List<Int>
    @Query("DELETE FROM subscription_reminders WHERE subscriptionId = :id")
    abstract suspend fun deleteReminders(id: Long)
    @Insert abstract suspend fun insertReminders(values: List<SubscriptionReminderEntity>)
    @Transaction
    open suspend fun replaceReminders(id: Long, days: Set<Int>) {
        require(days.all { it in setOf(1, 3, 7) }) { "提醒天数只支持 1、3、7" }
        deleteReminders(id)
        insertReminders(days.sorted().map { SubscriptionReminderEntity(id, it) })
    }
}
