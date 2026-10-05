package to.bitkit.test

/**
 * Runs each case of a table test. An assertion already names its case, so it is rethrown as it is. Any other failure,
 * such as an exception from the code under test or the cancellation a test timeout sends to a case stuck on a gate,
 * is rethrown as an [AssertionError] that names the case.
 */
inline fun <T> Iterable<T>.forEachCase(name: (T) -> String, action: (T) -> Unit) = forEach { case ->
    try {
        action(case)
    } catch (e: Throwable) {
        throw if (e is AssertionError) e else AssertionError("${name(case)}: $e", e)
    }
}
