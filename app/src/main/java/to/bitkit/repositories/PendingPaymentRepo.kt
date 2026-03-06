package to.bitkit.repositories

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import to.bitkit.utils.AppError
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingPaymentRepo @Inject constructor() {

    private val pendingPayments = Collections.synchronizedSet(mutableSetOf<String>())
    private val _activePendingPaymentHash = AtomicReference<String?>(null)
    private val _resolution = MutableSharedFlow<PendingPaymentResolution>(extraBufferCapacity = 1)
    val resolution = _resolution.asSharedFlow()

    fun track(paymentHash: String) = pendingPayments.add(paymentHash)

    fun isPending(hash: String): Boolean = pendingPayments.contains(hash)

    fun resolve(resolution: PendingPaymentResolution): Boolean {
        if (!pendingPayments.remove(resolution.paymentHash)) return false
        _resolution.tryEmit(resolution)
        return true
    }

    fun setActiveHash(hash: String?) = run { _activePendingPaymentHash.set(hash) }

    fun isActive(hash: String): Boolean = _activePendingPaymentHash.get() == hash
}

class PaymentPendingException(val paymentHash: String) : AppError("Payment pending")

sealed interface PendingPaymentResolution {
    val paymentHash: String

    data class Success(override val paymentHash: String) : PendingPaymentResolution
    data class Failure(override val paymentHash: String, val reason: String?) : PendingPaymentResolution
}
