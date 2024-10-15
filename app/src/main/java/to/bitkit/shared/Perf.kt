package to.bitkit.shared

import android.util.Log
import to.bitkit.env.Tag.PERF
import java.time.Instant
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

internal inline fun <T> withPerformanceLogging(block: () -> T): T {
    val startTime = System.currentTimeMillis()
    val startTimestamp = Instant.ofEpochMilli(startTime)
    Log.v(PERF, "Start Time: $startTimestamp")

    val result: T = block()

    val endTime = System.currentTimeMillis()
    val endTimestamp = Instant.ofEpochMilli(endTime)
    val duration = (endTime - startTime) / 1000.0
    Log.v(PERF, "End Time: $endTimestamp, Duration: $duration seconds")

    return result
}
