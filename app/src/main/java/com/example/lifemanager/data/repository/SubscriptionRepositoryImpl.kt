package com.example.lifemanager.data.repository

import androidx.room.withTransaction
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.entity.SubscriptionEntity
import com.example.lifemanager.data.local.entity.SubscriptionPaymentEntity
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.usecase.SubscriptionRules
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.map

class SubscriptionRepositoryImpl @Inject constructor(private val database: LifeManagerDatabase) : SubscriptionRepository {
    private val dao = database.subscriptionDao()
    override fun observeSubscriptions() = dao.observeSubscriptions().map { it.map(SubscriptionEntity::toDomain) }
    override fun observeAllPayments() = dao.observeAllPayments().map { it.map(SubscriptionPaymentEntity::toDomain) }
    override fun observePayments(subscriptionId: Long) = dao.observePayments(subscriptionId).map { it.map(SubscriptionPaymentEntity::toDomain) }
    override fun observeReminders(subscriptionId: Long) = dao.observeReminderDays(subscriptionId).map { it.toSet() }
    override suspend fun getSubscriptions() = dao.getAll().map(SubscriptionEntity::toDomain)
    override suspend fun getSubscription(id: Long) = dao.getById(id)?.toDomain()
    override suspend fun getReminderDays(subscriptionId: Long) = dao.getReminderDays(subscriptionId).toSet()
    override suspend fun saveSubscription(subscription: Subscription, reminderDays: Set<Int>): Long = database.withTransaction {
        require(SubscriptionRules.validate(subscription) == null) { SubscriptionRules.validate(subscription).orEmpty() }
        val now = Instant.now().toEpochMilli()
        val created = if (subscription.id == 0L) now else checkNotNull(dao.getById(subscription.id)).createdAt
        val id = dao.upsertSubscription(SubscriptionEntity(subscription.id, subscription.appName.trim(), subscription.amountMinor,
            subscription.currency.trim().uppercase(Locale.ROOT), subscription.billingCycle, subscription.nextBillingDate.toEpochDay(),
            subscription.startDate.toEpochDay(), subscription.category.clean(), subscription.note.clean(), subscription.isActive,
            subscription.cancelDate?.toEpochDay(), created, now))
        dao.replaceReminders(id, reminderDays)
        id
    }
    override suspend fun savePayment(payment: SubscriptionPayment): Long {
        require(payment.amountMinor > 0) { "金额必须大于 0" }
        val entity = SubscriptionPaymentEntity(payment.id, payment.subscriptionId, payment.amountMinor,
            payment.currency.trim().uppercase(Locale.ROOT), payment.paidAt.toEpochDay(), payment.note.clean())
        if (payment.id == 0L) return dao.insertPayment(entity)
        check(dao.updatePayment(entity) == 1) { "付款记录已被删除" }
        return payment.id
    }
    override suspend fun deletePayment(id: Long) = dao.deletePayment(id)
    override suspend fun deleteSubscription(id: Long) = dao.deleteSubscription(id)
}

fun SubscriptionEntity.toDomain() = Subscription(id, appName, amountMinor, currency, billingCycle,
    LocalDate.ofEpochDay(nextBillingDate), LocalDate.ofEpochDay(startDate), category, note, isActive,
    cancelDate?.let(LocalDate::ofEpochDay), Instant.ofEpochMilli(createdAt), Instant.ofEpochMilli(updatedAt))
private fun SubscriptionPaymentEntity.toDomain() = SubscriptionPayment(id, subscriptionId, amountMinor, currency, LocalDate.ofEpochDay(paidAt), note)
private fun String?.clean() = this?.trim()?.ifEmpty { null }
