package to.bitkit.ext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE

/**
 * Suspends and collects the elements of the Flow until the provided predicate satisfies
 * a `WatchResult.Complete`.
 *
 * @param timeout Maximum duration to wait before returning null. Defaults to [Duration.INFINITE].
 * @param predicate A suspending function that processes each emitted value and returns a
 * `WatchResult` indicating whether to continue or complete with a result.
 * @return The result of type `R` when the `WatchResult.Complete` is returned by the predicate,
 * or null if the timeout elapses first.
 */
suspend inline fun <T, R> Flow<T>.watchUntil(
    timeout: Duration = INFINITE,
    crossinline predicate: suspend (T) -> WatchResult<R>,
): R? {
    return withTimeoutOrNull(timeout) {
        val result = CompletableDeferred<R>()

        this@watchUntil.takeWhile { value ->
            when (val eventResult = predicate(value)) {
                is WatchResult.Continue -> {
                    eventResult.result?.let { result.complete(it) }
                    true
                }

                is WatchResult.Complete -> {
                    result.complete(eventResult.result)
                    false
                }
            }
        }.collect()

        result.await()
    }
}

sealed interface WatchResult<T> {
    data class Continue<T>(val result: T? = null) : WatchResult<T>
    data class Complete<T>(val result: T) : WatchResult<T>
}
