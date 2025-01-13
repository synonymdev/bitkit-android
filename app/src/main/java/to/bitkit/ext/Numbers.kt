package to.bitkit.ext

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant

val ULong.millis: ULong get() = this * 1000u

fun ULong.toActivityItemDate(): String {
    return Instant.ofEpochMilli(this.toLong()).formatted(DatePattern.ACTIVITY_ITEM)
}

fun Number.formatWithDotSeparator(): String {
    val symbols = DecimalFormatSymbols().apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    }
    val decimalFormat = DecimalFormat("#,###", symbols)
    return decimalFormat.format(this)
}
