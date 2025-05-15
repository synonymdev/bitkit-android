package to.bitkit.ext

import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.PaymentType

fun Activity.rawId(): String = when (this) {
    is Activity.Lightning -> v1.id
    is Activity.Onchain -> v1.id
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
fun Activity.totalValue() = when(this) {
    is Activity.Lightning -> v1.value + (v1.fee ?: 0u)
    is Activity.Onchain -> when (v1.txType) {
        PaymentType.SENT -> v1.value + v1.fee
        else -> v1.value
    }
}
