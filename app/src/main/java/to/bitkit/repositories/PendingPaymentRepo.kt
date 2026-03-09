package to.bitkit.repositories

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.bitkit.R
import to.bitkit.models.NotificationDetails
import to.bitkit.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingPaymentRepo @Inject constructor() {

    private val _state = MutableStateFlow(PendingPaymentsState())
    val state = _state.asStateFlow()

    private val _resolution = MutableSharedFlow<PendingPaymentResolution>(extraBufferCapacity = 1)
    val resolution = _resolution.asSharedFlow()

    fun track(paymentHash: String) {
        _state.update { it.copy(pendingPayments = it.pendingPayments + paymentHash) }
    }

    fun isPending(hash: String): Boolean = _state.value.pendingPayments.contains(hash)

    suspend fun resolve(resolution: PendingPaymentResolution) {
        _state.update { it.copy(pendingPayments = it.pendingPayments - resolution.paymentHash) }
        _resolution.emit(resolution)
    }

    fun setActiveHash(hash: String?) = _state.update { it.copy(activeHash = hash) }

    fun isActive(hash: String): Boolean = _state.value.activeHash == hash
}

data class PendingPaymentsState(
    val pendingPayments: Set<String> = emptySet(),
    val activeHash: String? = null,
)

class PaymentPendingException(val paymentHash: String) : AppError("Payment pending")

sealed interface PendingPaymentResolution {
    val paymentHash: String

    data class Success(override val paymentHash: String) : PendingPaymentResolution
    data class Failure(override val paymentHash: String) : PendingPaymentResolution
}

object PendingPaymentNotification {
    fun success(context: Context) = NotificationDetails(
        title = context.getString(R.string.wallet__toast_payment_sent_title),
        body = context.getString(R.string.wallet__toast_payment_sent_description),
    )

    fun error(context: Context) = NotificationDetails(
        title = context.getString(R.string.wallet__toast_payment_failed_title),
        body = context.getString(R.string.wallet__toast_payment_failed_description),
    )
}
