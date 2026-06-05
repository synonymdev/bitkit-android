package to.bitkit.utils

import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

fun Throwable.isConnectivityFailure(): Boolean {
    var failure: Throwable? = this
    while (failure != null) {
        when (failure) {
            is HttpRequestTimeoutException,
            is UnknownHostException,
            is SocketTimeoutException,
            is ConnectException,
            is NoRouteToHostException,
            is UnresolvedAddressException -> return true
        }
        failure = failure.cause.takeIf { it !== failure }
    }

    return false
}
