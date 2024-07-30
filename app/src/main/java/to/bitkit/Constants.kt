package to.bitkit

import android.util.Log
import to.bitkit.Tag.LDK
import to.bitkit.env.Network
import kotlin.io.path.Path
import org.lightningdevkit.ldknode.Network as LdkNetwork

@Suppress("unused")
internal object Tag {
    const val FCM = "FCM"
    const val LDK = "LDK"
    const val BDK = "BDK"
    const val DEV = "DEV"
    const val APP = "APP"
}

internal const val HOST = "10.0.2.2"
internal const val REST = "https://electrs-regtest.synonym.to"
internal const val SEED = "universe more push obey later jazz huge buzz magnet team muscle robust"

internal val PEER_REMOTE = LnPeer(
    nodeId = "033f4d3032ce7f54224f4bd9747b50b7cd72074a859758e40e1ca46ffa79a34324",
    host = HOST,
    port = "9735",
)

internal val PEER = LnPeer(
    nodeId = "02faf2d1f5dc153e8931d8444c4439e46a81cb7eeadba8562e7fec3690c261ce87",
    host = HOST,
    port = "9736",
)

@Suppress("unused")
internal object Env {
    val isDebug = BuildConfig.DEBUG

    object LdkStorage {
        lateinit var path: String

        fun init(base: String): String {
            require(base.isNotEmpty()) { "Base path for LDK storage cannot be empty" }
            path = Path(base, network.id, "ldk")
                .toFile()
                .absolutePath
            Log.d(LDK, "Storage path: $path")
            return path
        }
    }

    val network = Network.Regtest

    val trustedLnPeers = listOf(
        PEER_REMOTE,
        // PEER,
    )

    val ldkRgsServerUrl: String?
        get() = when (network.ldk) {
            LdkNetwork.BITCOIN -> "https://rapidsync.lightningdevkit.org/snapshot/"
            else -> null
        }
}

data class LnPeer(
    val nodeId: String,
    val host: String,
    val port: String,
) {
    constructor(nodeId: String, address: String) : this(
        nodeId,
        address.substringBefore(":"),
        address.substringAfter(":"),
    )

    val address get() = "$host:$port"
    override fun toString() = "$nodeId@${address}"
}
