package to.bitkit.wear.models

import breez_sdk_spark.PrepareSendPaymentResponse
import com.synonym.bitkitcore.LnurlPayData
import to.bitkit.wear.utils.AppError

/** An amountless BOLT11 invoice of this wallet, for the receive QR. [expiresAtSecs] is in epoch seconds. */
data class ReceiveInvoice(
    val bolt11: String,
    val paymentHash: String,
    val expiresAtSecs: ULong,
) {
    fun isExpired(nowSecs: ULong): Boolean = nowSecs >= expiresAtSecs
}

/** What a scanned or opened payment input asks for, before anything is priced. */
sealed interface SendRequest {
    /** The amount the input fixes, or null when the wearer picks it. */
    val fixedAmountSats: ULong?

    /** One short line naming who is paid, or null when the input names nobody. */
    val payee: String?

    /** A BOLT11 invoice; [amountSats] is null for an amountless one. */
    data class Invoice(
        val bolt11: String,
        val amountSats: ULong?,
        val description: String?,
    ) : SendRequest {
        override val fixedAmountSats: ULong? get() = amountSats
        override val payee: String? get() = description
    }

    /**
     * An LNURL-pay request or a Lightning address, which takes any whole amount from [minSats] to [maxSats]. [payee]
     * is the Lightning address, or else the host that answered.
     */
    data class Lnurl(
        val data: LnurlPayData,
        val minSats: ULong,
        val maxSats: ULong,
        override val payee: String?,
    ) : SendRequest {
        override val fixedAmountSats: ULong? get() = minSats.takeIf { it == maxSats }
    }

    /** A Spark address, which names no amount. */
    data class SparkAddress(val address: String) : SendRequest {
        override val fixedAmountSats: ULong? get() = null
        override val payee: String? get() = null
    }
}

/**
 * A send that Spark priced and the checks passed: what the review screen shows and the hold confirms. It is only made
 * by `PaymentsRepo.prepare` and sent by `PaymentsRepo.send`.
 */
class PreparedSend internal constructor(
    val amountSats: ULong,
    val feeSats: ULong,
    val payee: String?,
    internal val response: PrepareSendPaymentResponse,
    /** The invoice's payment hash, or null for a Spark address. */
    internal val paymentHash: String?,
    /** The idempotency key of a send without a payment hash, made once so a repeated send pays once. */
    internal val transferKey: String?,
) {
    val totalSats: ULong get() = amountSats + feeSats
}

enum class PaymentState { Completed, Pending, Failed }

/** A payment of this wallet as the activity list shows it. [timestampSecs] is in epoch seconds. */
data class WatchPayment(
    val id: String,
    val isSent: Boolean,
    val amountSats: ULong,
    val feeSats: ULong,
    val timestampSecs: ULong,
    val state: PaymentState,
)

/**
 * Why an input cannot be paid, or a send did not go through. Every case but [Unknown] means nothing left the wallet
 * for this request.
 */
sealed class SendError(message: String, cause: Throwable? = null) : AppError(message, cause) {
    /** The wallet is not connected and synced, so it neither prices nor sends. */
    class NotReady : SendError("The wallet is not ready to pay")

    /** The input is not a payment request at all, or it cannot be read. */
    class Invalid(cause: Throwable? = null) : SendError("The payment request cannot be read", cause)

    /** A payment request the watch does not pay, such as an on-chain address. */
    class Unsupported : SendError("The watch cannot pay this kind of request")

    class Expired(cause: Throwable? = null) : SendError("The invoice has expired", cause)

    /** The request pays this wallet, which the phone and the watch share. */
    class OwnInvoice : SendError("The payment request was created by this wallet")

    /** The request names no amount and none was given. */
    class AmountRequired : SendError("The payment request needs an amount")

    /** The amount is zero, or outside what the LNURL service takes, in sats. */
    class AmountOutOfRange(val minSats: ULong, val maxSats: ULong) :
        SendError("The amount is outside '$minSats' to '$maxSats' sats")

    /** The balance does not cover the amount and its fee; [shortfallSats] is what is missing, when known. */
    class InsufficientBalance(val shortfallSats: ULong?) : SendError("The balance is '$shortfallSats' sats short")

    /** This wallet already paid the invoice. */
    class AlreadyPaid : SendError("The invoice is already paid")

    /** A send of this invoice is still under way, from the watch or the phone. */
    class InFlight : SendError("A payment of this invoice is under way")

    /** Spark refused or failed the payment. */
    class Failed(cause: Throwable? = null) : SendError("The payment failed", cause)

    /**
     * The send was handed to Spark and its outcome could not be learned: it may have been paid. Never shown as a
     * plain failure; the activity list tells once Spark lists it, and sending the same invoice again cannot pay twice.
     */
    class Unknown(cause: Throwable? = null) : SendError("The payment's outcome is not known yet", cause)
}

/** Why an invoice to receive with could not be made. */
sealed class ReceiveError(message: String, cause: Throwable? = null) : AppError(message, cause) {
    /** The wallet is not connected and synced, so Spark cannot make an invoice. */
    class NotReady : ReceiveError("The wallet is not ready to receive")
}
