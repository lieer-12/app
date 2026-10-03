package com.example.lifemanager.ui.subscription

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.CompositionLocalProvider
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.SettingsRepositoryImpl
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.ui.settings.LocalDateFormat
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import com.example.lifemanager.ui.theme.LifeManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SubscriptionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val repository = ScreenRepository()
    private val settingsDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LifeManagerDatabase::class.java)
        .addCallback(LifeManagerDatabase.INITIALIZE).build()
    private val generations = DataGenerationRepositoryImpl(settingsDatabase)
    private val model = SubscriptionViewModel(repository, NoAlarms(), SettingsRepositoryImpl(settingsDatabase), Dispatchers.IO,
        GenerationAccess(generations, MaintenanceCoordinator(generations)))
    @After fun cleanup() { model.viewModelScope.cancel(); settingsDatabase.close() }
    private fun show(dateFormat: DateFormat = DateFormat.YMD) {
        compose.setContent {
            val state by model.uiState.collectAsStateWithLifecycle()
            LifeManagerTheme { CompositionLocalProvider(LocalDateFormat provides dateFormat) {
                SubscriptionContent(state, model, onExport = {})
            } }
        }
        compose.waitUntil(5000) { !model.uiState.value.isLoading }
    }
    @Test fun addSubscriptionOpensEditableForm() {
        show()
        compose.onNodeWithContentDescription("添加订阅").performClick()
        compose.onNodeWithText("订阅名称").assertExists()
        compose.onNodeWithText("金额").assertExists()
    }
    @Test fun enteredSubscriptionIsSavedAndDisplayedWithoutSeedData() {
        show()
        compose.onNodeWithContentDescription("添加订阅").performClick()
        compose.onNodeWithText("订阅名称").performTextInput("我的服务")
        compose.onNodeWithText("金额").performTextInput("15.00")
        compose.waitUntil(5000) { !model.uiState.value.editor.isLoadingReminders }
        compose.onNodeWithText("保存订阅").performClick()
        compose.waitUntil(5000) { repository.list.value.size == 1 }
        compose.onNodeWithText("我的服务").assertExists()
        assertEquals(1500L, repository.list.value.single().amountMinor)
    }

    @Test fun subscriptionDetailsUseDatePreferenceButDoNotRewriteStoredDates() {
        val date = java.time.LocalDate.of(2026, 10, 2)
        repository.list.value = listOf(Subscription(id = 42, appName = "日期测试", amountMinor = 1250,
            billingCycle = com.example.lifemanager.domain.model.BillingCycle.MONTHLY,
            startDate = date, nextBillingDate = date, isActive = false, cancelDate = date,
            createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH))
        show(DateFormat.DMY)
        compose.waitUntil(5000) { model.uiState.value.subscriptions.size == 1 }
        compose.runOnIdle { model.openDetail(42) }
        compose.onNodeWithText("开始：02-10-2026").assertExists()
        assertEquals(date, repository.list.value.single().startDate)
    }

    private class NoAlarms : SubscriptionReminderSchedulerContract {
        override fun schedule(subscription: Subscription, reminderDays: Set<Int>) {}
        override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) {}
        override fun cancelAll(subscriptionId: Long) {}
    }
    private class ScreenRepository : SubscriptionRepository {
        val list = MutableStateFlow<List<Subscription>>(emptyList())
        private val payments = MutableStateFlow<List<SubscriptionPayment>>(emptyList())
        override fun observeSubscriptions() = list
        override fun observeAllPayments() = payments
        override fun observePayments(subscriptionId: Long) = payments.map { it.filter { p -> p.subscriptionId == subscriptionId } }
        override fun observeReminders(subscriptionId: Long) = MutableStateFlow(emptySet<Int>())
        override suspend fun getSubscriptions() = list.value
        override suspend fun getSubscription(id: Long) = list.value.firstOrNull { it.id == id }
        override suspend fun getReminderDays(subscriptionId: Long) = emptySet<Int>()
        override suspend fun saveSubscription(subscription: Subscription, reminderDays: Set<Int>): Long {
            val id = subscription.id.takeIf { it > 0 } ?: 1
            list.value = list.value.filterNot { it.id == id } + subscription.copy(id = id)
            return id
        }
        override suspend fun savePayment(payment: SubscriptionPayment) = 1L
        override suspend fun deletePayment(id: Long) {}
        override suspend fun deleteSubscription(id: Long) { list.value = list.value.filterNot { it.id == id } }
    }
}
