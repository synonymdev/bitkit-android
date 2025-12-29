package to.bitkit.utils.timedsheets

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun checkTimeout(
    lastIgnoredMillis: Long,
    intervalMillis: Long,
    additionalCondition: Boolean = true,
): Boolean {
    if (!additionalCondition) return false

    val currentTime = Clock.System.now().toEpochMilliseconds()
    val isTimeOutOver = lastIgnoredMillis == 0L ||
        (currentTime - lastIgnoredMillis > intervalMillis)
    return isTimeOutOver
}

const val ONE_DAY_ASK_INTERVAL_MILLIS = 1000 * 60 * 60 * 24L
const val ONE_WEEK_ASK_INTERVAL_MILLIS = ONE_DAY_ASK_INTERVAL_MILLIS * 7L
