package to.bitkit.di

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.Tag.APP
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureTimeMillis

enum class ServiceQueue {
    LDK, BDK, MIGRATION;

    private val scope: CoroutineScope
        get() = when (this) {
            LDK -> ldkScope
            BDK -> bdkScope
            MIGRATION -> migrationScope
        }

    suspend fun <T> background(
        coroutineContext: CoroutineContext = scope.coroutineContext,
        functionName: String = Thread.currentThread().stackTrace[1].methodName,
        block: suspend CoroutineScope.() -> T,
    ): T {
        return withContext(coroutineContext) {
            try {
                var result: T
                val timeElapsed = measureTimeMillis {
                    result = block()
                }
                Log.d(APP, "$functionName took ${timeElapsed / 1000.0} seconds on queue: $name")

                return@withContext result
            } catch (e: Exception) {
                Log.e(APP, "ServiceQueue.$name error", e)
                throw e
            }
        }
    }

    companion object {
        private val ldkScope by lazy { CoroutineScope(dispatcher("ldk-queue") + SupervisorJob()) }
        private val bdkScope by lazy { CoroutineScope(dispatcher("bdk-queue") + SupervisorJob()) }
        private val migrationScope by lazy { CoroutineScope(dispatcher("migration-queue") + SupervisorJob()) }

        private fun dispatcher(name: String): ExecutorCoroutineDispatcher {
            val threadFactory = ThreadFactory { Thread(it, name).apply { priority = Thread.NORM_PRIORITY - 1 } }
            return Executors.newSingleThreadExecutor(threadFactory).asCoroutineDispatcher()
        }
    }
}
