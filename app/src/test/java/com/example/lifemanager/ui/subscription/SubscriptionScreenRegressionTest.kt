package com.example.lifemanager.ui.subscription

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import com.example.lifemanager.domain.repository.SettingsRepository
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SubscriptionScreenRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: SubscriptionViewModel

    @After fun cleanup() {
        if (::model.isInitialized) model.viewModelScope.cancel()
    }

    @Test fun maintenanceMarksAddSubscriptionFabDisabledWhileKeepingAvailableSnapshot() {
        val generations = TestGenerations(7)
        val coordinator = MaintenanceCoordinator(generations)
        val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        model = SubscriptionViewModel(Records(), NoAlarms, Preferences(), Dispatchers.IO,
            GenerationAccess(generations, coordinator))
        try {
            compose.setContent {
                val state by model.uiState.collectAsStateWithLifecycle()
                LifeManagerTheme { SubscriptionContent(state, model, onExport = {}) }
            }
            compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.isAvailable }
            compose.onNodeWithContentDescription("添加订阅").assertIsEnabled()
            maintenanceScope.launch { coordinator.withSession { awaitCancellation() } }
            compose.waitUntil(5000) {
                compose.waitForIdle()
                coordinator.state.value == MaintenanceState.READY && model.uiState.value.isMaintaining
            }
            assertTrue(model.uiState.value.isAvailable)
            compose.onNodeWithText("维护期间保留的订阅").assertExists()
            // An onClick guard alone still exposes an enabled button to Compose semantics/TalkBack.
            compose.onNodeWithContentDescription("添加订阅").assertIsNotEnabled()
        } finally { maintenanceScope.cancel() }
    }

    private object NoAlarms : SubscriptionReminderSchedulerContract {
        override fun schedule(subscription: Subscription, reminderDays: Set<Int>) = Unit
        override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) = Unit
        override fun cancelAll(subscriptionId: Long) = Unit
    }

    private class Preferences : SettingsRepository {
        private val settings = MutableStateFlow(AppSettings())
        override fun observeSettings() = settings
        override suspend fun getSettings() = settings.value
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings): Unit = error("Not used")
    }

    private class Records : SubscriptionRepository {
        private val subscriptions = MutableStateFlow(listOf(Subscription(
            id = 42, appName = "维护期间保留的订阅", amountMinor = 1500, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.now().plusDays(10), startDate = LocalDate.now().minusMonths(1),
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )))
        private val payments = MutableStateFlow(emptyList<SubscriptionPayment>())
        override fun observeSubscriptions() = subscriptions
        override fun observeAllPayments() = payments
        override fun observePayments(subscriptionId: Long) = payments
        override fun observeReminders(subscriptionId: Long) = MutableStateFlow(emptySet<Int>())
        override suspend fun getSubscriptions() = subscriptions.value
        override suspend fun getSubscription(id: Long) = subscriptions.value.firstOrNull { it.id == id }
        override suspend fun getReminderDays(subscriptionId: Long) = emptySet<Int>()
        override suspend fun saveSubscription(subscription: Subscription, reminderDays: Set<Int>): Long = error("Not used")
        override suspend fun savePayment(payment: SubscriptionPayment): Long = error("Not used")
        override suspend fun deletePayment(id: Long): Unit = error("Not used")
        override suspend fun deleteSubscription(id: Long): Unit = error("Not used")
    }
}
