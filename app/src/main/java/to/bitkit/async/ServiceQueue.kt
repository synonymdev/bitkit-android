package to.bitkit.async

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import to.bitkit.utils.AppError
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.CoroutineContext

enum class ServiceQueue {
    LDK, CORE, FOREX, LOG, MIGRATION;

    private val scope by lazy { CoroutineScope(newSingleThreadDispatcher(name) + SupervisorJob()) }

    fun <T> blocking(
        coroutineContext: CoroutineContext = scope.coroutineContext,
        block: suspend CoroutineScope.() -> T,
    ): T = runBlocking(coroutineContext) {
        runCatching { block() }.getOrElse { throw AppError(it) }
    }

    suspend fun <T> background(
        coroutineContext: CoroutineContext = scope.coroutineContext,
        block: suspend CoroutineScope.() -> T,
    ): T = withContext(coroutineContext) {
        runCatching { block() }.getOrElse { throw AppError(it) }
    }
}

fun newSingleThreadDispatcher(id: String): ExecutorCoroutineDispatcher {
    val name = "$id-queue".lowercase()
    val threadFactory = ThreadFactory { Thread(it, name).apply { priority = Thread.NORM_PRIORITY - 1 } }
    return Executors.newSingleThreadExecutor(threadFactory).asCoroutineDispatcher()
}
