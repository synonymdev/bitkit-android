package to.bitkit.appwidget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

interface ValidatedNetworkGate {
    suspend fun awaitReady(timeout: Duration): Boolean
}

@Singleton
class AndroidValidatedNetworkGate @Inject constructor(
    @ApplicationContext private val context: Context,
) : ValidatedNetworkGate {
    override suspend fun awaitReady(timeout: Duration): Boolean {
        val deadlineMs = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadlineMs) {
            if (isNetworkReady()) return true
            delay(POLL_INTERVAL)
        }
        return isNetworkReady()
    }

    private suspend fun isNetworkReady(): Boolean =
        hasConnectedNetwork() && canResolveWidgetHost()

    private fun hasConnectedNetwork(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivityManager.activeNetwork
            ?.let(connectivityManager::getNetworkCapabilities)
            ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    }

    private suspend fun canResolveWidgetHost(): Boolean = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getByName(WIDGET_DNS_PROBE_HOST) }.isSuccess
    }

    private companion object {
        private const val WIDGET_DNS_PROBE_HOST = "feeds.synonym.to"
        private val POLL_INTERVAL = 500.milliseconds
    }
}
