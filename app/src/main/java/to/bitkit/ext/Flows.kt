package to.bitkit.ext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

/**
 * Suspends and collects the elements of the Flow until the provided predicate satisfies
 * a `WatchResult.Complete`. The predicate evaluates each emitted value and determines
 * whether to continue the Flow or complete with the given result.
 *
 * @param predicate A suspending function that processes each emitted value and returns a
 * `WatchResult` indicating whether to continue or complete with a result.
 * @return The result of type `R` when the `WatchResult.Complete` is returned by the predicate.
 */
suspend inline fun <T, R> Flow<T>.watchUntil(
    crossinline predicate: suspend (T) -> WatchResult<R>,
): R {
    val result = CompletableDeferred<R>()

    this.takeWhile { value ->
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

    return result.await()
}

sealed interface WatchResult<T> {
    data class Continue<T>(val result: T? = null) : WatchResult<T>
    data class Complete<T>(val result: T) : WatchResult<T>
}
