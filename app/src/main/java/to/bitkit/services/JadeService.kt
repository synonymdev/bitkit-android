package to.bitkit.services

import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.CompletedTransaction
import com.synonym.bitkitcore.JadeAccountExport
import com.synonym.bitkitcore.JadeAddressVariant
import com.synonym.bitkitcore.JadeDeviceInfo
import com.synonym.bitkitcore.JadeNetwork
import com.synonym.bitkitcore.JadePingStatus
import com.synonym.bitkitcore.JadeTransportKind
import com.synonym.bitkitcore.JadeVersionInfo
import com.synonym.bitkitcore.jadeCancel
import com.synonym.bitkitcore.jadeConnect
import com.synonym.bitkitcore.jadeDisconnect
import com.synonym.bitkitcore.jadeGetAccountExport
import com.synonym.bitkitcore.jadeGetConnectedDevice
import com.synonym.bitkitcore.jadeGetMasterFingerprint
import com.synonym.bitkitcore.jadeGetVersionInfo
import com.synonym.bitkitcore.jadeIsConnected
import com.synonym.bitkitcore.jadeListDevices
import com.synonym.bitkitcore.jadeNotifyDisconnected
import com.synonym.bitkitcore.jadePing
import com.synonym.bitkitcore.jadeRefreshVersionInfo
import com.synonym.bitkitcore.jadeScan
import com.synonym.bitkitcore.jadeSetTransportCallback
import com.synonym.bitkitcore.jadeSignPsbt
import com.synonym.bitkitcore.jadeUnlock
import com.synonym.bitkitcore.jadeVerifyAddress
import to.bitkit.async.ServiceQueue
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import com.synonym.bitkitcore.finalizePsbt as coreFinalizePsbt

/**
 * Thin wrapper over bitkit-core's `jade*` functions. Every call runs on [ServiceQueue.CORE], whose
 * single thread also serialises the vendors' blocking Bluetooth scans against each other.
 */
@Suppress("TooManyFunctions")
@Singleton
class JadeService @Inject constructor(
    private val transport: JadeTransport,
) {
    companion object {
        private const val TAG = "JadeService"

        /** Matches the Trezor transport's scan window so one search loop iteration stays predictable. */
        const val SCAN_TIMEOUT_MS = 3_000u
    }

    @Volatile
    private var callbackRegistered = false

    private fun ensureCallbackRegistered() {
        if (!callbackRegistered) {
            synchronized(this) {
                if (!callbackRegistered) {
                    val replaced = jadeSetTransportCallback(transport)
                    if (replaced) Logger.warn("Replaced a previously registered Jade transport", context = TAG)
                    callbackRegistered = true
                }
            }
        }
    }

    suspend fun initialize() {
        ServiceQueue.CORE.background { ensureCallbackRegistered() }
    }

    suspend fun scan(includeBluetooth: Boolean = true, timeoutMs: UInt = SCAN_TIMEOUT_MS): List<JadeDeviceInfo> =
        ServiceQueue.CORE.background {
            ensureCallbackRegistered()
            transport.withBluetoothScanningEnabled(includeBluetooth) {
                jadeScan(timeoutMs)
            }
        }

    suspend fun listDevices(): List<JadeDeviceInfo> = ServiceQueue.CORE.background { jadeListDevices() }

    suspend fun connect(
        transportKind: JadeTransportKind,
        path: String,
        requestUsbPermission: Boolean = true,
    ): JadeVersionInfo = ServiceQueue.CORE.background {
        ensureCallbackRegistered()
        transport.withUsbPermissionRequestsEnabled(requestUsbPermission) {
            jadeConnect(transport = transportKind, path = path)
        }
    }

    suspend fun disconnect() = ServiceQueue.CORE.background { jadeDisconnect() }

    suspend fun cancel() = ServiceQueue.CORE.background { jadeCancel() }

    suspend fun notifyDisconnected(path: String) = ServiceQueue.CORE.background { jadeNotifyDisconnected(path) }

    suspend fun isConnected(): Boolean = ServiceQueue.CORE.background { jadeIsConnected() }

    suspend fun getConnectedDevice(): JadeDeviceInfo? = ServiceQueue.CORE.background { jadeGetConnectedDevice() }

    suspend fun getVersionInfo(): JadeVersionInfo? = ServiceQueue.CORE.background { jadeGetVersionInfo() }

    suspend fun refreshVersionInfo(): JadeVersionInfo = ServiceQueue.CORE.background { jadeRefreshVersionInfo() }

    suspend fun ping(): JadePingStatus = ServiceQueue.CORE.background { jadePing() }

    suspend fun unlock(network: JadeNetwork) = ServiceQueue.CORE.background { jadeUnlock(network) }

    suspend fun getMasterFingerprint(network: JadeNetwork): String =
        ServiceQueue.CORE.background { jadeGetMasterFingerprint(network) }

    suspend fun getAccountExport(
        network: JadeNetwork,
        accountTypes: List<AccountType>,
        accountIndex: UInt = 0u,
    ): JadeAccountExport = ServiceQueue.CORE.background {
        jadeGetAccountExport(network = network, accountIndex = accountIndex, accountTypes = accountTypes)
    }

    suspend fun verifyAddress(
        network: JadeNetwork,
        variant: JadeAddressVariant,
        derivationPath: String,
        expectedAddress: String,
    ) = ServiceQueue.CORE.background {
        jadeVerifyAddress(
            network = network,
            variant = variant,
            derivationPath = derivationPath,
            expectedAddress = expectedAddress,
        )
    }

    suspend fun signPsbt(network: JadeNetwork, psbtBase64: String): String =
        ServiceQueue.CORE.background { jadeSignPsbt(network = network, psbt = psbtBase64) }

    suspend fun finalizePsbt(originalPsbt: String, signedPsbt: String): CompletedTransaction =
        ServiceQueue.CORE.background { coreFinalizePsbt(originalPsbt = originalPsbt, signedPsbt = signedPsbt) }
}
