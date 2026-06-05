package to.bitkit.appwidget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface ValidatedNetworkGate {
    suspend fun awaitValidated(timeout: Duration): Boolean
}

@Singleton
class AndroidValidatedNetworkGate @Inject constructor(
    @ApplicationContext private val context: Context,
) : ValidatedNetworkGate {
    override suspend fun awaitValidated(timeout: Duration): Boolean {
        val deadlineMs = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadlineMs) {
            if (hasValidatedNetwork()) return true
            delay(POLL_INTERVAL)
        }
        return hasValidatedNetwork()
    }

    private fun hasValidatedNetwork(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivityManager.activeNetwork
            ?.let(connectivityManager::getNetworkCapabilities)
            ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    }

    private companion object {
        private val POLL_INTERVAL = 500.milliseconds
    }
}
