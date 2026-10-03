package com.example.lifemanager.ui.common

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/** Deterministically delays one Main publication, without adding test hooks to production. */
class PausingMainDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var pauseNext = false
    private var held: Pair<CoroutineContext, Runnable>? = null
    val hasHeldPublication get() = held != null
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (pauseNext) {
            check(held == null)
            pauseNext = false
            held = context to block
        } else delegate.dispatch(context, block)
    }
    fun resumeHeld() {
        val pending = held ?: return
        held = null
        delegate.dispatch(pending.first, pending.second)
    }
}
