package to.bitkit.ext

import com.synonym.bitkitcore.FeeRates
import to.bitkit.models.TransactionSpeed

fun FeeRates.getSatsPerVByteFor(speed: TransactionSpeed): UInt {
    return when (speed) {
        is TransactionSpeed.Fast -> fast
        is TransactionSpeed.Medium -> mid
        is TransactionSpeed.Slow -> slow
        is TransactionSpeed.Custom -> speed.satsPerVByte
    }
}
