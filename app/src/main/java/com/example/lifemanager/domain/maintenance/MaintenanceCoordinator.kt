package com.example.lifemanager.domain.maintenance

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class MaintenanceCoordinator @Inject constructor(private val generations: DataGenerationRepository) {
    // This lock protects admission/state only, never database I/O, module locks or user interaction.
    private val gate = Mutex()
    private var admitted = 0
    private var freeze: Freeze? = null
    private val mutableState = MutableStateFlow(MaintenanceState.IDLE)
    val state: StateFlow<MaintenanceState> = mutableState.asStateFlow()

    /** Capture when opening/loading UI state, not when a delayed old draft eventually saves. */
    suspend fun capture(): DataGeneration = withAdmission(null) { it }

    /** A result-publication check, not a write permit. Writes must still use run(). */
    suspend fun isCurrent(token: DataGeneration): Boolean = try {
        capture() == token
    } catch (_: MaintenanceBusyException) {
        false
    }

    /** Global admission must surround the existing module coordinator, never the reverse. */
    suspend fun <T> run(token: DataGeneration, operation: suspend () -> T): T =
        withAdmission(token) { operation() }

    /** Session ownership cannot escape a cancelled result hand-off. Children finish before release. */
    suspend fun <T> withSession(operation: suspend CoroutineScope.(MaintenanceSession) -> T): T {
        val session = begin()
        try {
            return coroutineScope {
                val owned = currentCoroutineContext()[OwnedSessions]?.owners.orEmpty() +
                    (session to currentCoroutineContext().job)
                withContext(OwnedSessions(owned)) { operation(session) }
            }
        } finally {
            finish(session)
        }
    }

    private suspend fun begin(): MaintenanceSession {
        checkNotNested()
        val pending = gate.withLock {
            if (freeze != null) throw MaintenanceBusyException()
            Freeze().also {
                freeze = it
                mutableState.value = MaintenanceState.DRAINING
                if (admitted == 0) it.drained.complete(Unit)
            }
        }
        try {
            pending.drained.await()
            val session = MaintenanceSession(generations.current())
            currentCoroutineContext().ensureActive()
            gate.withLock {
                currentCoroutineContext().ensureActive()
                check(freeze === pending)
                pending.session = session
                mutableState.value = MaintenanceState.READY
            }
            currentCoroutineContext().ensureActive()
            return session
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                gate.withLock {
                    if (freeze === pending) {
                        freeze = null
                        mutableState.value = MaintenanceState.IDLE
                    }
                }
            }
            throw error
        }
    }

    /** Short session work (snapshot or DB commit). Do not wrap a picker/confirmation here. */
    suspend fun <T> withMaintenance(session: MaintenanceSession, operation: suspend () -> T): T {
        checkNotNested()
        val context = currentCoroutineContext()
        context.ensureActive()
        val owner = context[OwnedSessions]?.owners?.get(session)
        require(owner != null && belongsTo(context.job, owner)) {
            "维护任务必须在所属 withSession 作用域内执行"
        }
        val active = gate.withLock {
            requireSession(session).also {
                if (it.running) throw MaintenanceBusyException()
                it.running = true
                mutableState.value = MaintenanceState.RUNNING
            }
        }
        try {
            if (generations.current() != session.generation) throw StaleGenerationException()
            return inScope(operation)
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    active.running = false
                    mutableState.value = MaintenanceState.READY
                }
            }
        }
    }

    private suspend fun finish(session: MaintenanceSession) {
        checkNotNested()
        withContext(NonCancellable) {
            gate.withLock {
                val active = requireSession(session)
                if (active.running) throw MaintenanceBusyException()
                freeze = null
                mutableState.value = MaintenanceState.IDLE
            }
        }
    }

    private suspend fun <T> withAdmission(expected: DataGeneration?, operation: suspend (DataGeneration) -> T): T {
        checkNotNested()
        gate.withLock {
            if (freeze != null) throw MaintenanceBusyException()
            admitted++
        }
        try {
            val actual = generations.current()
            if (expected != null && actual != expected) throw StaleGenerationException()
            return inScope { operation(actual) }
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    admitted--
                    if (admitted == 0) freeze?.drained?.complete(Unit)
                }
            }
        }
    }

    private fun requireSession(session: MaintenanceSession): Freeze {
        val active = freeze
        require(active != null && active.session === session) { "维护会话已失效或不属于此协调器" }
        return active
    }

    private suspend fun checkNotNested() {
        check(this !in currentCoroutineContext()[HeldScopes]?.owners.orEmpty()) {
            "全局协调必须先于模块锁，且不可重复获取；请复用外层操作许可"
        }
    }

    private suspend fun <T> inScope(operation: suspend () -> T): T {
        val owners = currentCoroutineContext()[HeldScopes]?.owners.orEmpty() + this
        return withContext(HeldScopes(owners)) { operation() }
    }

    private class Freeze {
        val drained = CompletableDeferred<Unit>()
        var session: MaintenanceSession? = null
        var running = false
    }

    private class HeldScopes(val owners: Set<MaintenanceCoordinator>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HeldScopes>
    }

    // The marker alone survives launch(Job()); validate the real structured parent chain too.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun belongsTo(current: Job, owner: Job): Boolean {
        var candidate: Job? = current
        while (candidate != null) {
            if (candidate === owner) return true
            candidate = candidate.parent
        }
        return false
    }

    private class OwnedSessions(val owners: Map<MaintenanceSession, Job>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<OwnedSessions>
    }
}
