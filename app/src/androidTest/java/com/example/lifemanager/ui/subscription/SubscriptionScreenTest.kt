package com.example.lifemanager.ui.subscription

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
    private val model = SubscriptionViewModel(repository, NoAlarms(), Dispatchers.IO)
    @After fun cleanup() { model.viewModelScope.cancel() }
    private fun show() {
        compose.setContent {
            val state by model.uiState.collectAsStateWithLifecycle()
            LifeManagerTheme { SubscriptionContent(state, model, onExport = {}) }
        }
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
        compose.onNodeWithText("保存订阅").performClick()
        compose.waitUntil(5000) { repository.list.value.size == 1 }
        compose.onNodeWithText("我的服务").assertExists()
        assertEquals(1500L, repository.list.value.single().amountMinor)
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
