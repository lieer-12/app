package com.example.lifemanager.ui.navigation

import kotlin.test.*

class SubscriptionNavigationViewModelTest {
    @Test fun consumingRequestDoesNotReplayWhenActivityReattaches() {
        val model = SubscriptionNavigationViewModel()
        model.open(42)
        val request = assertNotNull(model.pending.value)
        model.consume(request.token)
        assertNull(model.pending.value)
    }
    @Test fun secondTapOnSameSubscriptionIsNewEventAndOldAcknowledgementCannotConsumeIt() {
        val model = SubscriptionNavigationViewModel()
        model.open(42)
        val first = assertNotNull(model.pending.value)
        model.open(42)
        val second = assertNotNull(model.pending.value)
        assertNotEquals(first.token, second.token)
        model.consume(first.token)
        assertEquals(second, model.pending.value)
    }
}
