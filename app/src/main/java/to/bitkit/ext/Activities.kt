package to.bitkit.ext

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType

fun Activity.rawId(): String = when (this) {
    is Activity.Lightning -> v1.id
    is Activity.Onchain -> v1.id
}

fun Activity.txType(): PaymentType = when (this) {
    is Activity.Lightning -> v1.txType
    is Activity.Onchain -> v1.txType
}

/**
 * Calculates the total value of an activity based on its type.
 *
 * For `Lightning` activity, the total value = `value + fee`.
 *
 * For `Onchain` activity:
 * - If it is a send, the total value = `value + fee`.
 * - Otherwise it's equal to `value`.
 *
 * @return The total value as an `ULong`.
 */
fun Activity.totalValue() = when (this) {
    is Activity.Lightning -> v1.value + (v1.fee ?: 0u)
    is Activity.Onchain -> when (v1.txType) {
        PaymentType.SENT -> v1.value + v1.fee
        else -> v1.value
    }
}

fun Activity.isBoosted() = when (this) {
    is Activity.Onchain -> v1.isBoosted
    else -> false
}

fun Activity.isFinished() = when (this) {
    is Activity.Onchain -> v1.confirmed
    is Activity.Lightning -> v1.status != PaymentState.PENDING
}

fun Activity.isBoosting(): Boolean = isBoosted() && !isFinished() && doesExist()

fun Activity.isSent() = when (this) {
    is Activity.Lightning -> v1.txType == PaymentType.SENT
    is Activity.Onchain -> v1.txType == PaymentType.SENT
}

fun Activity.matchesPaymentId(paymentHashOrTxId: String): Boolean = when (this) {
    is Activity.Lightning -> paymentHashOrTxId == v1.id
    is Activity.Onchain -> paymentHashOrTxId == v1.txId
}

fun Activity.isTransfer() = this is Activity.Onchain && this.v1.isTransfer

fun Activity.doesExist() = this is Activity.Onchain && this.v1.doesExist

fun Activity.paymentState(): PaymentState? = when (this) {
    is Activity.Lightning -> this.v1.status
    is Activity.Onchain -> null
}

fun Activity.Onchain.boostType() = when (this.v1.txType) {
    PaymentType.SENT -> BoostType.RBF
    PaymentType.RECEIVED -> BoostType.CPFP
}

enum class BoostType { RBF, CPFP }
