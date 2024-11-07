package to.bitkit.env

import android.util.Log
import org.lightningdevkit.ldknode.Network
import to.bitkit.BuildConfig
import to.bitkit.env.Tag.APP
import to.bitkit.ext.ensureDir
import to.bitkit.models.LnPeer
import to.bitkit.models.blocktank.BlocktankNotificationType
import kotlin.io.path.Path

internal object Env {
    const val SEED = "universe more push obey later jazz huge buzz magnet team muscle robust"
    val isDebug = BuildConfig.DEBUG
    val isUnitTest = System.getProperty("java.class.path")?.contains("junit") == true
    val network = Network.REGTEST
    val defaultWalletWordCount = 12
    val onchainWalletStopGap = 20_UL
    val walletSyncIntervalSecs = 60_UL
    val feeRateCacheUpdateIntervalSecs = 60_UL
    val esploraParallelRequests = 6
    val trustedLnPeers
        get() = when (network) {
            Network.REGTEST -> listOf(
                Peers.btStaging,
                // Peers.polarToRegtest,
                // Peers.local,
            )

            else -> TODO("Not yet implemented")
        }
    val ldkRgsServerUrl
        get() = when (network) {
            Network.BITCOIN -> "https://rapidsync.lightningdevkit.org/snapshot/"
            else -> null
        }
    val esploraUrl
        get() = when (network) {
            Network.REGTEST -> "https://electrs-regtest.synonym.to"
            else -> TODO("Not yet implemented")
        }
    private val blocktankBaseUrl
        get() = when (network) {
            Network.REGTEST -> "https://api.stag.blocktank.to"
            else -> TODO("Not yet implemented")
        }
    val blocktankClientServer get() = "${blocktankBaseUrl}/blocktank/api/v2"
    val blocktankPushNotificationServer get() = "${blocktankBaseUrl}/notifications/api"
    val pushNotificationFeatures = listOf(
        BlocktankNotificationType.incomingHtlc,
        BlocktankNotificationType.mutualClose,
        BlocktankNotificationType.orderPaymentConfirmed,
        BlocktankNotificationType.cjitPaymentArrived,
        BlocktankNotificationType.wakeToTimeout,
    )
    const val DERIVATION_NAME = "bitkit-notifications"

    private lateinit var appStoragePath: String

    fun initAppStoragePath(path: String) {
        require(path.isNotBlank()) { "App storage path cannot be empty." }
        Log.i("APP", "App storage path: $path")
        appStoragePath = path
    }

    fun ldkStoragePath(walletIndex: Int) = storagePathOf(walletIndex, network.name.lowercase(), "ldk")

    private fun storagePathOf(walletIndex: Int, network: String, dir: String): String {
        require(::appStoragePath.isInitialized) { "App storage path should be init as context.filesDir.absolutePath." }
        val absolutePath = Path(appStoragePath, network, "wallet$walletIndex", dir)
            .toFile()
            .ensureDir()
            .absolutePath
        Log.d(APP, "Using ${dir.uppercase()} storage path: $absolutePath")
        return absolutePath
    }

    object Peers {
        val btStaging = LnPeer(
            nodeId = "03b9a456fb45d5ac98c02040d39aec77fa3eeb41fd22cf40b862b393bcfc43473a",
            address = "35.233.47.252:9400",
        )
        val polarToRegtest = LnPeer(
            nodeId = "023f6e310ff049d68c64a0eb97440b998aa15fd99162317d6743d7023519862e23",
            address = "10.0.2.2:9735",
        )
        val local = LnPeer(
            nodeId = "02faf2d1f5dc153e8931d8444c4439e46a81cb7eeadba8562e7fec3690c261ce87",
            address = "10.0.2.2:9738",
        )
    }
}
