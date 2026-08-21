package com.example.lifemanager.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReminderKeyTest {
    @Test
    fun `same todo id always produces the same request code`() {
        assertEquals(ReminderKey.forTodo(42L), ReminderKey.forTodo(42L))
    }

    @Test
    fun `different todo ids produce different request codes`() {
        assertNotEquals(ReminderKey.forTodo(42L), ReminderKey.forTodo(43L))
    }
}
