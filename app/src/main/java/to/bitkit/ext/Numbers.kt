package to.bitkit.ext

import java.time.Instant

val ULong.millis: ULong get() = this * 1000u

fun ULong.toActivityItemDate(): String {
    return Instant.ofEpochSecond(this.toLong()).formatted(DatePattern.ACTIVITY_ITEM)
}
