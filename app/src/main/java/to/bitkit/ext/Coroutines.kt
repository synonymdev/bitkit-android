package to.bitkit.ext

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

suspend fun CoroutineDispatcher.call(block: suspend CoroutineScope.() -> Unit) {
    withContext(this) {
        block()
    }
}
