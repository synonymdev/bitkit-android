package to.bitkit.utils

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun Throwable.isConnectivityFailure(): Boolean {
    var failure: Throwable? = this
    while (failure != null) {
        when (failure) {
            is UnknownHostException,
            is SocketTimeoutException,
            is ConnectException,
            is NoRouteToHostException -> return true
        }
        failure = failure.cause.takeIf { it !== failure }
    }

    return false
}
