package to.bitkit.async

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import to.bitkit.ext.callerName
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.measured
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.CoroutineContext

enum class ServiceQueue {
    LDK, CORE, FOREX, MIGRATION;

    private val scope by lazy { CoroutineScope(dispatcher("$name-queue".lowercase()) + SupervisorJob()) }

    fun <T> blocking(
        coroutineContext: CoroutineContext = scope.coroutineContext,
        functionName: String = Thread.currentThread().callerName,
        block: suspend CoroutineScope.() -> T,
    ): T {
        return runBlocking(coroutineContext) {
            try {
                measured(functionName) {
                    block()
                }
            } catch (e: Exception) {
                Logger.error("ServiceQueue.$name error", e)
                throw AppError(e)
            }
        }
    }

    suspend fun <T> background(
        coroutineContext: CoroutineContext = scope.coroutineContext,
        functionName: String = Thread.currentThread().callerName,
        block: suspend CoroutineScope.() -> T,
    ): T {
        return withContext(coroutineContext) {
            try {
                measured(functionName) {
                    block()
                }
            } catch (e: Exception) {
                Logger.error("ServiceQueue.$name error", e)
                throw AppError(e)
            }
        }
    }

    companion object {
        fun dispatcher(name: String): ExecutorCoroutineDispatcher {
            val threadFactory = ThreadFactory { Thread(it, name).apply { priority = Thread.NORM_PRIORITY - 1 } }
            return Executors.newSingleThreadExecutor(threadFactory).asCoroutineDispatcher()
        }
    }
}
