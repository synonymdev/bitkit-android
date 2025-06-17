package to.bitkit.ext

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant

fun ULong.toActivityItemDate(): String {
    return Instant.ofEpochSecond(this.toLong()).formatted(DatePattern.ACTIVITY_DATE)
}

fun ULong.toActivityItemTime(): String {
    return Instant.ofEpochSecond(this.toLong()).formatted(DatePattern.ACTIVITY_TIME)
}

fun Number.formatWithDotSeparator(): String {
    val symbols = DecimalFormatSymbols().apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    }
    val decimalFormat = DecimalFormat("#,###", symbols)
    return decimalFormat.format(this)
}

fun ULong.formatWithDotSeparator(): String {
    return this.toLong().formatWithDotSeparator()
}

fun UInt.formatWithDotSeparator(): String {
    return this.toInt().formatWithDotSeparator()
}
