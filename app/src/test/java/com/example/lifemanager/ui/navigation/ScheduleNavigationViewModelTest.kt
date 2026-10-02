package com.example.lifemanager.ui.navigation

import kotlin.test.*

class ScheduleNavigationViewModelTest {
    @Test fun consumedRequestDoesNotReplay() {
        val model = ScheduleNavigationViewModel()
        model.open(42)
        val request = assertNotNull(model.pending.value)
        assertEquals(42L, request.scheduleId)
        model.consume(request.token)
        assertNull(model.pending.value)
    }
    @Test fun repeatedTapCannotBeConsumedByOldAcknowledgement() {
        val model = ScheduleNavigationViewModel()
        model.open(42)
        val first = assertNotNull(model.pending.value)
        model.open(42)
        val second = assertNotNull(model.pending.value)
        assertNotEquals(first.token, second.token)
        model.consume(first.token)
        assertEquals(second, model.pending.value)
    }
    @Test fun invalidIdDoesNotOverwriteRealRequest() {
        val model = ScheduleNavigationViewModel()
        model.open(42)
        val original = model.pending.value
        model.open(0); model.open(-1)
        assertEquals(original, model.pending.value)
    }
}
