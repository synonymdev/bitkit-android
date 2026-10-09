package to.bitkit.models

import kotlinx.serialization.Serializable
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PaykitUsdtAttempt
import to.bitkit.repositories.PaykitUsdtReceipt

@Serializable
data class PaykitUsdtStateBackup(
    val attempts: List<PaykitUsdtAttempt>,
    val receipts: List<Receipt>,
) {
    @Serializable
    data class Receipt(
        val wallet: String,
        val identity: String,
        val requestId: PaykitPaymentRequestId,
        val paymentId: String,
        val proofEventId: String,
        val verified: Boolean,
        val transferId: String,
        val amountAtomic: ULong,
        val receivedAtMillis: Long,
        val underpaid: Boolean,
        val afterExpiry: Boolean,
    ) {
        constructor(value: PaykitUsdtReceipt) : this(
            value.wallet, value.identity, value.requestId, value.paymentId, value.proofEventId,
            value.verified, value.transferId, value.amount.atomic, value.receivedAtMillis,
            value.underpaid, value.afterExpiry,
        )

        fun restored() = PaykitUsdtReceipt(
            wallet, identity, requestId, paymentId, proofEventId, verified, transferId,
            PaykitAmount(PaykitAsset.USDT, amountAtomic), receivedAtMillis, underpaid, afterExpiry,
        )
    }
}
