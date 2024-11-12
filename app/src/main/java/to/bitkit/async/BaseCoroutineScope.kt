package to.bitkit.async

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

open class BaseCoroutineScope(
    private val dispatcher: CoroutineDispatcher,
) : CoroutineScope by CoroutineScope(SupervisorJob() + dispatcher) {

    @Throws(InterruptedException::class)
    protected fun <T> runBlocking(
        context: CoroutineContext = coroutineContext,
        block: suspend CoroutineScope.() -> T,
    ): T {
        return kotlinx.coroutines.runBlocking(context, block)
    }
}
