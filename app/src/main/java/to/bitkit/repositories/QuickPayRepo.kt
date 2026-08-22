package to.bitkit.repositories

import kotlinx.coroutines.flow.Flow
import org.lightningdevkit.ldknode.PaymentFailureReason
import to.bitkit.utils.AppError
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuickPayRepo @Inject constructor(
    private val coordinator: QuickPayCoordinator,
) {
    companion object {
        const val LEDGER_VERSION = QuickPaySpendStore.LEDGER_VERSION
    }

    fun attach(session: QuickPaySession): Flow<QuickPaySessionEvent> = coordinator.attach(session)

    fun detach(session: QuickPaySession) = coordinator.detach(session)

    fun detachAll() = coordinator.detachAll()

    fun pay(session: QuickPaySession, request: QuickPayPayRequest) = coordinator.pay(session, request)

    suspend fun canApply(amountSats: ULong): Result<Boolean> = coordinator.canApply(amountSats)

    suspend fun reserveBound(
        paymentHash: String,
        amountSats: ULong,
    ): Result<QuickPayLedgerRecord?> = coordinator.reserveBound(paymentHash, amountSats)

    suspend fun signalCompletion(
        paymentId: String?,
        paymentHash: String?,
        success: Boolean,
        feePaidMsat: ULong? = null,
        failureReason: PaymentFailureReason? = null,
    ): QuickPayCompletionOutcome = coordinator.signalCompletion(
        paymentId = paymentId,
        paymentHash = paymentHash,
        success = success,
        feePaidMsat = feePaidMsat,
        failureReason = failureReason,
    )

    suspend fun reconcileAgainstLdk() = coordinator.reconcileAgainstLdk()

    internal suspend fun payNow(
        session: QuickPaySession,
        request: QuickPayPayRequest,
    ) = coordinator.payNow(session, request)
}

data class QuickPaySession(val id: String = UUID.randomUUID().toString())

sealed interface QuickPayPayRequest {
    val amountSats: ULong

    data class Bolt11(
        val bolt11: String,
        override val amountSats: ULong,
    ) : QuickPayPayRequest

    data class LnurlPay(
        val data: com.synonym.bitkitcore.LnurlPayData,
        override val amountSats: ULong,
    ) : QuickPayPayRequest
}

sealed interface QuickPaySessionEvent {
    data class Success(
        val paymentHash: String,
        val amountWithFee: Long,
    ) : QuickPaySessionEvent

    data class Pending(
        val paymentHash: String,
        val amount: Long,
        val paymentRequest: String,
    ) : QuickPaySessionEvent

    data object FallBackToConfirm : QuickPaySessionEvent

    data class Error(
        val error: Throwable,
        val paymentRequest: String?,
    ) : QuickPaySessionEvent
}

enum class QuickPayCompletionKind {
    NONE,
    SETTLED_SUCCESS,
    SETTLED_FAILURE,
}

data class QuickPayCompletionOutcome(
    val kind: QuickPayCompletionKind = QuickPayCompletionKind.NONE,
    val invoicePaymentHash: String? = null,
) {
    val wasQuickPay: Boolean get() = kind != QuickPayCompletionKind.NONE

    companion object {
        val None = QuickPayCompletionOutcome()
    }
}

class QuickPayConversionError : AppError("Currency conversion failed")

class QuickPayPaymentFailedError(
    val paymentHash: String,
    val reason: PaymentFailureReason?,
    val paymentRequest: String?,
) : AppError(reason?.name)
