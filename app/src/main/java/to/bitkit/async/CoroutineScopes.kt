package to.bitkit.async

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import to.bitkit.utils.Logger

fun loggingExceptionHandler(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        Logger.error("Uncaught coroutine exception", throwable, context = tag)
    }

fun appScope(dispatcher: CoroutineDispatcher, tag: String): CoroutineScope =
    CoroutineScope(dispatcher + SupervisorJob() + loggingExceptionHandler(tag))
