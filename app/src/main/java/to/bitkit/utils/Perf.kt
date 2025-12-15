package to.bitkit.utils

import java.time.Instant
import kotlin.system.measureTimeMillis

internal inline fun <T> measured(
    label: String,
    block: () -> T,
): T {
    var result: T

    val elapsedMs = measureTimeMillis {
        result = block()
    }

    Logger.debug("$label took ${elapsedMs}ms")

    return result
}

internal inline fun <T> withPerformanceLogging(block: () -> T): T {
    val startTime = System.currentTimeMillis()
    val startTimestamp = Instant.ofEpochMilli(startTime)
    Logger.performance("Start Time: $startTimestamp")

    val result: T = block()

    val endTime = System.currentTimeMillis()
    val endTimestamp = Instant.ofEpochMilli(endTime)
    val duration = (endTime - startTime) / 1000.0
    Logger.performance("End Time: $endTimestamp, Duration: $duration seconds")

    return result
}
