package to.bitkit.async

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import to.bitkit.di.IoDispatcher
import kotlin.coroutines.CoroutineContext

open class BaseCoroutineScope(
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : CoroutineScope {
    private val job = Job()
    override val coroutineContext = dispatcher + job

    @Throws(InterruptedException::class)
    protected fun <T> runBlocking(
        context: CoroutineContext = coroutineContext,
        block: suspend CoroutineScope.() -> T,
    ): T {
        return kotlinx.coroutines.runBlocking(context, block)
    }
}
