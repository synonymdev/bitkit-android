package to.bitkit.utils

import kotlin.time.Duration
import kotlin.time.measureTime

fun Duration.formatted(): String = toComponents { hours, minutes, seconds, nanoseconds ->
    val ms = nanoseconds / 1_000_000
    buildString {
        if (hours > 0) append("${hours}h ")
        if (minutes > 0) append("${minutes}m ")
        if (seconds > 0) append("${seconds}s ")
        if (ms > 0 || isEmpty()) append("${ms}ms")
    }.trim()
}

internal inline fun <T> measured(
    label: String,
    context: String,
    block: () -> T,
): T {
    var result: T
    val elapsed = measureTime {
        result = block()
    }
    Logger.perf("$label took ${elapsed.formatted()}", context = context)
    return result
}
