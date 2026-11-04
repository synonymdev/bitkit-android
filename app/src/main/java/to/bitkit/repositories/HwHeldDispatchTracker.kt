package to.bitkit.repositories

import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every dispatch of a held hardware send's signed bytes in this process, by txid. A broadcast proving it sent nothing
 * says nothing about another dispatch of the same bytes, so a hold is released on such proof only while every other
 * dispatch of those bytes is proven not sent too.
 */
@Singleton
class HwHeldDispatchTracker @Inject constructor() {
    private val lock = Any()
    private val dispatches = mutableListOf<HwHeldDispatch>()
    private val _inFlight = MutableStateFlow<ImmutableSet<String>>(persistentSetOf())

    /** Txids with a dispatch still waiting for its answer. */
    val inFlight: StateFlow<ImmutableSet<String>> = _inFlight.asStateFlow()

    /** Records a dispatch of [txId]'s bytes and returns its handle, unless one is already in flight. */
    fun begin(txId: String): HwHeldDispatch? = synchronized(lock) {
        val key = txId.lowercase()
        if (dispatches.any { it.txId == key && it.outcome == HwHeldDispatch.Outcome.IN_FLIGHT }) return null
        HwHeldDispatch(key).also {
            dispatches += it
            publish()
        }
    }

    /** Records the answer of [dispatch]: [isNotSent] only when it proves the bytes never left this device. */
    fun finish(dispatch: HwHeldDispatch, isNotSent: Boolean) {
        synchronized(lock) {
            if (dispatch.outcome != HwHeldDispatch.Outcome.IN_FLIGHT) return
            dispatch.outcome = if (isNotSent) HwHeldDispatch.Outcome.NOT_SENT else HwHeldDispatch.Outcome.MAY_HAVE_SENT
            publish()
        }
    }

    /** Whether every dispatch of [dispatch]'s bytes other than [dispatch] itself is proven not sent. */
    fun isOnlyPossibleSend(dispatch: HwHeldDispatch): Boolean = synchronized(lock) {
        dispatches.none {
            it !== dispatch && it.txId == dispatch.txId && it.outcome != HwHeldDispatch.Outcome.NOT_SENT
        }
    }

    private fun publish() {
        val inFlight = dispatches.filter { it.outcome == HwHeldDispatch.Outcome.IN_FLIGHT }.map { it.txId }
        _inFlight.update { inFlight.toImmutableSet() }
    }
}

/** One dispatch of a held send's signed bytes. */
class HwHeldDispatch internal constructor(internal val txId: String) {
    internal enum class Outcome { IN_FLIGHT, NOT_SENT, MAY_HAVE_SENT }

    internal var outcome = Outcome.IN_FLIGHT
}
