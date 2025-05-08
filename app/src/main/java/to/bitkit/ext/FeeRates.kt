package to.bitkit.ext

import to.bitkit.models.TransactionSpeed
import uniffi.bitkitcore.FeeRates

fun FeeRates.getSatsPerVByteFor(speed: TransactionSpeed): UInt {
    return when (speed) {
        is TransactionSpeed.Fast -> fast
        is TransactionSpeed.Medium -> mid
        is TransactionSpeed.Slow -> slow
        is TransactionSpeed.Custom -> speed.satsPerVByte
    }
}
