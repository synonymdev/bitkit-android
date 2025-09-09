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
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
                handleExceptionForBlocking(e, functionName)
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
                handleExceptionForBackground(e, functionName)
            }
        }
    }

    /**
     * Handle exceptions for blocking calls (these can be more aggressive as they're usually
     * called from background threads)
     */
    private fun <T> handleExceptionForBlocking(e: Exception, functionName: String): T {
        when (e) {
            is UnknownHostException, is SocketTimeoutException, is ConnectException -> {
                Logger.warn("Network error in $functionName: ${e.message}")
                val networkException = NetworkException("Network unavailable: ${e.message}", e)
                Logger.error("ServiceQueue.$name error", networkException)
                throw networkException
            }

            is IOException -> {
                Logger.warn("IO error in $functionName: ${e.message}")
                val networkException = NetworkException("Connection error: ${e.message}", e)
                Logger.error("ServiceQueue.$name error", networkException)
                throw networkException
            }

            is NetworkException -> {
                Logger.warn("Network error in $functionName: ${e.message}")
                Logger.error("ServiceQueue.$name error", e)
                throw e
            }

            else -> {
                val wrappedException = AppError(e)
                Logger.error("ServiceQueue.$name error", wrappedException)
                throw wrappedException
            }
        }
    }

    /**
     * Handle exceptions for background calls (these are more lenient for network errors
     * to prevent main thread crashes)
     */
    private fun <T> handleExceptionForBackground(e: Exception, functionName: String): T {
        when (e) {
            is UnknownHostException, is SocketTimeoutException, is ConnectException -> {
                Logger.warn("Network error in $functionName: ${e.message}")
                // For certain critical services, we want to fail silently to prevent crashes
                if (name == CORE.name || name == FOREX.name) {
                    Logger.warn("Suppressing network error for $name to prevent crash")
                    return getNetworkErrorFallback()
                }
                val networkException = NetworkException("Network unavailable: ${e.message}", e)
                Logger.error("ServiceQueue.$name error", networkException)
                throw networkException
            }

            is IOException -> {
                Logger.warn("IO error in $functionName: ${e.message}")
                if (name == CORE.name || name == FOREX.name) {
                    Logger.warn("Suppressing IO error for ${name} to prevent crash")
                    return getNetworkErrorFallback()
                }
                val networkException = NetworkException("Connection error: ${e.message}", e)
                Logger.error("ServiceQueue.$name error", networkException)
                throw networkException
            }

            is NetworkException -> {
                Logger.warn("Network error in $functionName: ${e.message}")
                if (name == CORE.name || name == FOREX.name) {
                    Logger.warn("Suppressing network exception for ${name} to prevent crash")
                    return getNetworkErrorFallback()
                }
                Logger.error("ServiceQueue.$name error", e)
                throw e
            }

            else -> {
                val wrappedException = AppError(e)
                Logger.error("ServiceQueue.$name error", wrappedException)
                throw wrappedException
            }
        }
    }

    /**
     * Provides safe fallback values for network errors to prevent crashes
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getNetworkErrorFallback(): T {
        return when (name) {
            CORE.name -> {
                // For geo blocking check, assume not blocked if network fails
                false as T
            }

            else -> {
                throw NetworkException("Network unavailable")
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

class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
