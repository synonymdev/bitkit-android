@file:Suppress("unused")

package to.bitkit

import android.util.Log
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.Tag.APP
import to.bitkit.env.Network
import to.bitkit.ext.ensureDir
import kotlin.io.path.Path
import org.lightningdevkit.ldknode.Network as LdkNetwork

// region globals
internal object Tag {
    const val FCM = "FCM"
    const val LDK = "LDK"
    const val LSP = "LSP"
    const val BDK = "BDK"
    const val DEV = "DEV"
    const val APP = "APP"
    const val PERF = "PERF"
}

internal const val HOST = "10.0.2.2"
internal const val REST = "https://electrs-regtest.synonym.to"
internal const val SEED = "universe more push obey later jazz huge buzz magnet team muscle robust"

internal val PEER_REMOTE = LnPeer(
    nodeId = "033f4d3032ce7f54224f4bd9747b50b7cd72074a859758e40e1ca46ffa79a34324",
    host = HOST,
    port = "9737",
)

internal val PEER = LnPeer(
    nodeId = "02faf2d1f5dc153e8931d8444c4439e46a81cb7eeadba8562e7fec3690c261ce87",
    host = HOST,
    port = "9737",
)
// endregion

// region env
internal object Env {
    val isDebug = BuildConfig.DEBUG

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
// endregion

data class LnPeer(
    val nodeId: String,
    val host: String,
    val port: String,
    val isConnected: Boolean = false,
    val isPersisted: Boolean = false,
) {
    constructor(
        nodeId: String,
        address: String,
        isConnected: Boolean = false,
        isPersisted: Boolean = false,
    ) : this(
        nodeId,
        address.substringBefore(":"),
        address.substringAfter(":"),
        isConnected,
        isPersisted,
    )

    val address get() = "$host:$port"
    override fun toString() = "$nodeId@${address}"

    companion object {
        fun PeerDetails.toLnPeer() = LnPeer(
            nodeId = nodeId,
            address = address,
            isConnected = isConnected,
            isPersisted = isPersisted,
        )
    }
}
