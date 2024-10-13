package to.bitkit.env

import android.util.Log
import to.bitkit.BuildConfig
import to.bitkit.env.Tag.APP
import to.bitkit.ext.ensureDir
import to.bitkit.models.LnPeer
import to.bitkit.models.WalletNetwork
import to.bitkit.models.blocktank.BlocktankNotificationType
import kotlin.io.path.Path

internal object Env {
    const val SEED = "universe more push obey later jazz huge buzz magnet team muscle robust"
    val isDebug = BuildConfig.DEBUG
    val network: WalletNetwork = WalletNetwork.REGTEST
    val trustedLnPeers
        get() = when (network) {
            WalletNetwork.REGTEST -> listOf(
                Peers.btStaging,
                // Peers.polarToRegtest,
                // Peers.local,
            )

            else -> TODO("Not yet implemented")
        }
    val ldkRgsServerUrl
        get() = when (network) {
            WalletNetwork.BITCOIN -> "https://rapidsync.lightningdevkit.org/snapshot/"
            else -> null
        }
    val esploraUrl
        get() = when (network) {
            WalletNetwork.REGTEST -> "https://electrs-regtest.synonym.to"
            else -> TODO("Not yet implemented")
        }
    private val blocktankBaseUrl
        get() = when (network) {
            WalletNetwork.REGTEST -> "https://api.stag.blocktank.to"
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

    object Storage {
        private var base = ""
        fun init(basePath: String) {
            require(basePath.isNotEmpty()) { "Base storage path cannot be empty" }
            base = basePath
            Log.i(APP, "Storage path: $basePath")
        }

        val ldk get() = storagePathOf(0, network.id, "ldk")
        val bdk get() = storagePathOf(0, network.id, "bdk")

        private fun storagePathOf(walletIndex: Int, network: String, dir: String): String {
            require(base.isNotEmpty()) { "Base storage path cannot be empty" }
            val absolutePath = Path(base, network, "wallet$walletIndex", dir)
                .toFile()
                .ensureDir()
                .absolutePath
            Log.d(APP, "$dir storage path: $absolutePath")
            return absolutePath
        }
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
