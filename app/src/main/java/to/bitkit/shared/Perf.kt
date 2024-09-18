package to.bitkit.shared

import android.util.Log
import to.bitkit.env.Tag.PERF
import kotlin.system.measureTimeMillis

internal inline fun <T> measured(
    functionName: String,
    block: () -> T,
): T {
    var result: T

    val elapsed = measureTimeMillis {
        result = block()
    }.let { it / 1000.0 }

    val threadName = Thread.currentThread().name
    Log.v(PERF, "$functionName took $elapsed seconds on $threadName")

    return result
}
