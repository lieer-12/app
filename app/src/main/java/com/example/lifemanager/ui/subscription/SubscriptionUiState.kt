package com.example.lifemanager.ui.subscription

import com.example.lifemanager.domain.model.*
import java.time.LocalDate

enum class SubscriptionTab { LIST, STATS }

data class SubscriptionEditorState(
    val isOpen: Boolean = false,
    val sessionId: Long = 0,
    val original: Subscription? = null,
    val name: String = "",
    val amount: String = "",
    val currency: String = "CNY",
    val billingCycle: BillingCycle = BillingCycle.MONTHLY,
    val nextBillingDate: String = LocalDate.now().toString(),
    val startDate: String = LocalDate.now().toString(),
    val category: String = "",
    val note: String = "",
    val reminderDays: Set<Int> = emptySet(),
    val isLoadingReminders: Boolean = false,
    val preferencesError: String? = null,
    val isSaving: Boolean = false,
    val validationMessage: String? = null,
)

data class PaymentEditorState(
    val isOpen: Boolean = false,
    val subscriptionId: Long = 0,
    val editingId: Long = 0,
    val amount: String = "",
    val currency: String = "CNY",
    val paidAt: String = LocalDate.now().toString(),
    val note: String = "",
    val isSaving: Boolean = false,
    val validationMessage: String? = null,
)

data class SubscriptionUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val payments: List<SubscriptionPayment> = emptyList(),
    val statistics: SubscriptionStats? = null,
    val selectedTab: SubscriptionTab = SubscriptionTab.LIST,
    val detailId: Long? = null,
    val pendingNotificationId: Long? = null,
    val detailReminderDays: Set<Int> = emptySet(),
    val editor: SubscriptionEditorState = SubscriptionEditorState(),
    val paymentEditor: PaymentEditorState = PaymentEditorState(),
    val errorMessage: String? = null,
    val isLoading: Boolean = true,
    val hasSubscriptionData: Boolean = false,
    val hasPaymentData: Boolean = false,
    val isBusy: Boolean = false,
    val isExportPending: Boolean = false,
    val isExporting: Boolean = false,
    val exportMessage: String? = null,
) {
    val canExportCsv: Boolean
        get() = hasSubscriptionData && hasPaymentData && !isLoading && !isExportPending && !isExporting
}
