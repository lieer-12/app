package com.example.lifemanager.notification

import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionOperationCoordinatorTest {
    @Test
    fun `cancel cannot commit between receiver snapshot and rearm or show`() = runTest {
        val releaseReceiver = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val receiver = launch {
            SubscriptionOperationCoordinator.run {
                events += "read active"
                releaseReceiver.await()
                events += "rearm"
                events += "show"
            }
        }
        runCurrent()
        val cancel = launch { SubscriptionOperationCoordinator.run { events += "commit cancel" } }
        runCurrent()
        assertEquals(listOf("read active"), events)
        assertFalse(cancel.isCompleted)
        releaseReceiver.complete(Unit)
        receiver.join()
        cancel.join()
        assertEquals(listOf("read active", "rearm", "show", "commit cancel"), events)
    }

    @Test
    fun `receiver sees committed cancellation when mutation acquired gate first`() = runTest {
        val releaseMutation = CompletableDeferred<Unit>()
        var active = true
        val mutation = launch {
            SubscriptionOperationCoordinator.run {
                releaseMutation.await()
                active = false
            }
        }
        runCurrent()
        val receiver = async { SubscriptionOperationCoordinator.run { active } }
        runCurrent()
        assertFalse(receiver.isCompleted)
        releaseMutation.complete(Unit)
        mutation.join()
        assertFalse(receiver.await())
    }

    @Test
    fun `operation returns its result`() = runTest {
        assertEquals(42L, SubscriptionOperationCoordinator.run { 42L })
    }

    @Test
    fun `failing operation releases gate for next caller`() = runTest {
        assertFailsWith<IllegalStateException> {
            SubscriptionOperationCoordinator.run { error("database failed") }
        }
        assertEquals("next", SubscriptionOperationCoordinator.run { "next" })
    }

    @Test
    fun `cancelled operation releases gate for waiting caller`() = runTest {
        val held = launch { SubscriptionOperationCoordinator.run { CompletableDeferred<Unit>().await() } }
        runCurrent()
        val waiting = async { SubscriptionOperationCoordinator.run { "next" } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        held.cancelAndJoin()
        assertEquals("next", waiting.await())
    }
}
