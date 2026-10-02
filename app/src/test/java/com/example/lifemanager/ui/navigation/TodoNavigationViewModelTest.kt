package com.example.lifemanager.ui.navigation

import kotlin.test.*

class TodoNavigationViewModelTest {
    @Test fun consumedNotificationDoesNotReplayOnRotation() {
        val model = TodoNavigationViewModel()
        model.open(42)
        val request = assertNotNull(model.pending.value)
        assertEquals(42L, request.todoId)
        model.consume(request.token)
        assertNull(model.pending.value)
    }
    @Test fun repeatedTapIsNewEventAndOldAcknowledgementCannotConsumeIt() {
        val model = TodoNavigationViewModel()
        model.open(42)
        val first = assertNotNull(model.pending.value)
        model.open(42)
        val second = assertNotNull(model.pending.value)
        assertNotEquals(first.token, second.token)
        model.consume(first.token)
        assertEquals(second, model.pending.value)
    }
    @Test fun invalidIdDoesNotReplacePendingNotification() {
        val model = TodoNavigationViewModel()
        model.open(42)
        val request = model.pending.value
        model.open(0)
        model.open(-1)
        assertEquals(request, model.pending.value)
    }
}
