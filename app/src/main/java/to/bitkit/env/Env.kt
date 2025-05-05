package to.bitkit.env

import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Network
import to.bitkit.BuildConfig
import to.bitkit.ext.ensureDir
import to.bitkit.models.LnPeer
import to.bitkit.models.blocktank.BlocktankNotificationType
import to.bitkit.utils.Logger
import kotlin.io.path.Path

@Suppress("ConstPropertyName")
internal object Env {
    val isDebug = BuildConfig.DEBUG
    val isUnitTest = System.getProperty("java.class.path")?.contains("junit") == true
    val network = Network.REGTEST
    val defaultWalletWordCount = 12
    val walletSyncIntervalSecs = 10_uL // TODO review
    val ldkNodeSyncIntervalSecs = 60_uL // TODO review

    // TODO: remove this to load from BT API instead
    val trustedLnPeers
        get() = when (network) {
            Network.REGTEST -> listOf(
                Peers.btStaging,
            )

            else -> TODO("Not yet implemented")
        }
    val ldkRgsServerUrl
        get() = when (network) {
            Network.BITCOIN -> "https://rapidsync.lightningdevkit.org/snapshot/"
            else -> null
        }
    val vssServerUrl
        get() = when (network) {
            Network.REGTEST -> "https://bitkit.stag0.blocktank.to/vss"
            else -> TODO("${network.name} network not implemented")
        }
    val vssStoreId
        get() = when (network) {
            Network.REGTEST -> "bitkit_regtest"
            else -> TODO("${network.name} network not implemented")
        }
    val esploraServerUrl
        get() = when (network) {
            Network.REGTEST -> "https://bitkit.stag0.blocktank.to/electrs"
            else -> TODO("${network.name} network not implemented")
        }
    val blocktankBaseUrl
        get() = when (network) {
            Network.REGTEST -> "https://api.stag0.blocktank.to"
            else -> TODO("${network.name} network not implemented")
        }

    val blocktankClientServer get() = "${blocktankBaseUrl}/blocktank/api/v2"
    val blocktankPushNotificationServer get() = "${blocktankBaseUrl}/notifications/api"
    val btcRatesServer get() = "https://blocktank.synonym.to/fx/rates/btc/"
    val geoCheckUrl get() = "https://api1.blocktank.to/api/geocheck"

    const val fxRateRefreshInterval: Long = 2 * 60 * 1000 // 2 minutes in milliseconds
    const val fxRateStaleThreshold: Long = 10 * 60 * 1000 // 10 minutes in milliseconds

    const val blocktankOrderRefreshInterval: Long = 2 * 60 * 1000 // 2 minutes in milliseconds

    val pushNotificationFeatures = listOf(
        BlocktankNotificationType.incomingHtlc,
        BlocktankNotificationType.mutualClose,
        BlocktankNotificationType.orderPaymentConfirmed,
        BlocktankNotificationType.cjitPaymentArrived,
        BlocktankNotificationType.wakeToTimeout,
    )
    const val DERIVATION_NAME = "bitkit-notifications"

    object TransactionDefaults {
        val recommendedBaseFee = 256u // Total recommended tx base fee in sats
        // val dustLimit = 546
    }

    private lateinit var appStoragePath: String

    fun initAppStoragePath(path: String) {
        require(path.isNotBlank()) { "App storage path cannot be empty." }
        Logger.info("App storage path: $path")
        appStoragePath = path
    }

    fun ldkLogFilePath(walletIndex: Int): String {
        val logPath = Path(ldkStoragePath(walletIndex), "ldk_node_latest.log").toFile().absolutePath
        Logger.info("LDK-node log path: $logPath")
        return logPath
    }

    val ldkLogLevel = LogLevel.TRACE

    fun ldkStoragePath(walletIndex: Int) = storagePathOf(walletIndex, network.name.lowercase(), "ldk")
    fun bitkitCoreStoragePath(walletIndex: Int) = storagePathOf(walletIndex, network.name.lowercase(), "core")

    private fun storagePathOf(walletIndex: Int, network: String, dir: String): String {
        require(::appStoragePath.isInitialized) { "App storage path should be 'context.filesDir.absolutePath'." }
        val path = Path(appStoragePath, network, "wallet$walletIndex", dir)
            .toFile()
            .ensureDir()
            .path
        Logger.debug("Using ${dir.uppercase()} storage path: $path")
        return path
    }

    object Peers {
        val btStaging = LnPeer(
            nodeId = "028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc",
            address = "34.65.86.104:9400",
        )
    }

    const val PIN_LENGTH = 4
    const val PIN_ATTEMPTS = 8
    const val DEFAULT_INVOICE_MESSAGE = "Bitkit"
}
