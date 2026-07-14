package to.bitkit.ext

import com.synonym.bitkitcore.BroadcastException
import kotlinx.coroutines.TimeoutCancellationException

fun Throwable.isBroadcastConnectivityFailure(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is TimeoutCancellationException || it is BroadcastException.ElectrumException
    }
