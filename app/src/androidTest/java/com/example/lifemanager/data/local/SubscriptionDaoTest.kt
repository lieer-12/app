package com.example.lifemanager.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.local.entity.SubscriptionEntity
import com.example.lifemanager.data.local.entity.SubscriptionPaymentEntity
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.domain.model.Subscription
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class SubscriptionDaoTest {
    private lateinit var database: LifeManagerDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LifeManagerDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = database.close()

    @Test fun invalidReminderRollsBackSubscriptionChange() = runBlocking {
        val repository = SubscriptionRepositoryImpl(database)
        val original = Subscription(appName = "原名称", amountMinor = 1500, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.of(2026, 10, 31), startDate = LocalDate.of(2026, 1, 31),
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
        val id = repository.saveSubscription(original, setOf(3))
        val before = repository.getSubscription(id)!!
        val failure = runCatching { repository.saveSubscription(before.copy(appName = "不应保存"), setOf(2)) }
        assertEquals(true, failure.isFailure)
        assertEquals("原名称", repository.getSubscription(id)!!.appName)
        assertEquals(setOf(3), repository.getReminderDays(id))
        repository.saveSubscription(before.copy(appName = "新名称", createdAt = Instant.MAX), setOf(7))
        assertEquals(before.createdAt, repository.getSubscription(id)!!.createdAt)
    }

    @Test fun editingSubscriptionPreservesPaymentsAndReplacesReminderDays() = runBlocking {
        val dao = database.subscriptionDao()
        val subscription = SubscriptionEntity(appName = "音乐", amountMinor = 1500, currency = "CNY",
            billingCycle = BillingCycle.MONTHLY, nextBillingDate = 20000, startDate = 19000,
            category = null, note = null, isActive = true, cancelDate = null, createdAt = 1, updatedAt = 1)
        val id = dao.upsertSubscription(subscription)
        dao.insertPayment(SubscriptionPaymentEntity(subscriptionId = id, amountMinor = 1200, currency = "CNY", paidAt = 20000, note = null))
        dao.replaceReminders(id, setOf(1, 3))
        dao.upsertSubscription(subscription.copy(id = id, appName = "更新名称"))
        dao.replaceReminders(id, setOf(7))
        assertEquals("更新名称", dao.observeSubscriptions().first().single().appName)
        assertEquals(1200L, dao.observePayments(id).first().single().amountMinor)
        assertEquals(listOf(7), dao.observeReminderDays(id).first())
    }

    @Test fun deletingSubscriptionCascadesPaymentsAndReminders() = runBlocking {
        val dao = database.subscriptionDao()
        val id = dao.upsertSubscription(
            SubscriptionEntity(
                appName = "音乐服务",
                amountMinor = 1_500,
                currency = "CNY",
                billingCycle = BillingCycle.MONTHLY,
                nextBillingDate = 20_000,
                startDate = 19_000,
                category = "影音",
                note = null,
                isActive = true,
                cancelDate = null,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        dao.insertPayment(SubscriptionPaymentEntity(subscriptionId = id, amountMinor = 1_500, currency = "CNY", paidAt = 20_000, note = null))
        dao.replaceReminders(id, setOf(1, 3))

        dao.deleteSubscription(id)

        assertEquals(0, dao.observePayments(id).first().size)
        assertEquals(emptyList(), dao.observeReminderDays(id).first())
    }
}
