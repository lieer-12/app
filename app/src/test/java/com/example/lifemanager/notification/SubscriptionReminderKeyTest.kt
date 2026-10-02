package com.example.lifemanager.notification

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SubscriptionReminderKeyTest {
    private val dueDate = LocalDate.of(2026, 10, 10)

    @Test
    fun `same reminder always has the same key and alarm identity`() {
        assertEquals(ReminderKey.forSubscription(42L, 3), ReminderKey.forSubscription(42L, 3))
        assertEquals(
            ReminderKey.subscriptionData(42L, 3, dueDate),
            ReminderKey.subscriptionData(42L, 3, dueDate),
        )
    }

    @Test
    fun `each selected offset has its own alarm identity`() {
        val identities = setOf(1, 3, 7).map { ReminderKey.subscriptionData(42L, it, dueDate) }
        assertEquals(3, identities.toSet().size)
    }

    @Test
    fun `full long ids remain distinct even when integer request codes collide`() {
        // These IDs have equal existing Long-to-Int hashes. Intent data must disambiguate them.
        assertEquals(ReminderKey.forTodo(1L), ReminderKey.forTodo(4_294_967_296L))
        assertNotEquals(
            ReminderKey.subscriptionData(1L, 3, dueDate),
            ReminderKey.subscriptionData(4_294_967_296L, 3, dueDate),
        )
    }

    @Test
    fun `replaced due date cannot reuse the stale alarm identity`() {
        assertNotEquals(
            ReminderKey.subscriptionData(42L, 3, dueDate),
            ReminderKey.subscriptionData(42L, 3, LocalDate.of(2026, 11, 10)),
        )
    }
}
