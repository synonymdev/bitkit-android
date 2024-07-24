package to.bitkit

import org.bitcoindevkit.Network as BdkNetwork
import org.lightningdevkit.ldknode.Network as LdkNetwork

internal const val _DEV = "_DEV"
internal const val _FCM = "_FCM"
internal const val _LDK = "_LDK"
internal const val _BDK = "_BDK"

internal const val HOST = "10.0.2.2"
internal const val REST = "http://$HOST:3002"
internal const val SEED = "universe more push obey later jazz huge buzz magnet team muscle robust"
internal val PEER = LnPeer(
    nodeId = "02faf2d1f5dc153e8931d8444c4439e46a81cb7eeadba8562e7fec3690c261ce87",
    host = HOST,
    port = "9736",
)

internal object Env {
    val isDebug = BuildConfig.DEBUG

    object LdkStorage {
        lateinit var path: String
    }

    object Network {
        val ldk: LdkNetwork = LdkNetwork.REGTEST
        val bdk = BdkNetwork.REGTEST
    }

    val trustedLnPeers = listOf(
        PEER,
    )

    val ldkRgsServerUrl: String?
        get() = when (Network.ldk) {
            LdkNetwork.BITCOIN -> "https://rapidsync.lightningdevkit.org/snapshot/"
            else -> null
        }
}

internal data class LnPeer(
    val nodeId: String,
    val host: String,
    val port: String,
) {
    fun address() = "$host:$port"
    override fun toString() = "$nodeId@${address()}"
}
