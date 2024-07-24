package to.bitkit

import kotlin.io.path.Path
import org.bitcoindevkit.Network as BdkNetwork
import org.lightningdevkit.ldknode.Network as LdkNetwork

internal const val _DEV = "_DEV"
internal const val _FCM = "_FCM"
internal const val _LDK = "_LDK"
internal const val _BDK = "_BDK"

internal val BDK_NETWORK = BdkNetwork.REGTEST
internal val LDK_NETWORK get() = org.ldk.enums.Network.LDKNetwork_Regtest

internal const val HOST = "10.0.2.2"
internal const val REST = "http://$HOST:3002"
internal const val PORT = "9736"
internal const val PEER = "02faf2d1f5dc153e8931d8444c4439e46a81cb7eeadba8562e7fec3690c261ce87"
internal const val SEED = "universe more push obey later jazz huge buzz magnet team muscle robust"

internal object Env {
    val isDebug = BuildConfig.DEBUG

    object LdkStorage {
        lateinit var path: String
    }

    object Network {
        val ldkNetwork: LdkNetwork = LdkNetwork.REGTEST
        val bdkNetwork = BdkNetwork.REGTEST
    }

    val trustedLnPeers = listOf(
        LnPeer(),
    )

    val ldkRgsServerUrl: String?
        get() = when (Network.ldkNetwork) {
            LdkNetwork.BITCOIN -> "https://rapidsync.lightningdevkit.org/snapshot/"
            else -> null
        }
}

internal data class LnPeer(
    val nodeId: String = PEER,
    val host: String = HOST,
    val port: String = PORT,
) {
    fun address() = "$HOST:$PORT"
    override fun toString() = "$nodeId@${address()}"
}
