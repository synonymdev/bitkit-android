package to.bitkit.ext

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import to.bitkit.utils.Logger

@Suppress("TooGenericExceptionCaught")
suspend inline fun <R> runSuspendCatching(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (e: Throwable) {
        Result.failure(e)
    }

fun Job.logCompletion(name: String = "") = invokeOnCompletion { err ->
    if (err != null) {
        Logger.verbose("Coroutine '$name' error: ${err.message}")
    } else {
        Logger.verbose("Coroutine '$name' completed successfully")
    }
}
