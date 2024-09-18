package to.bitkit.env

import android.util.Log
import to.bitkit.BuildConfig
import to.bitkit.env.Tag.APP
import to.bitkit.ext.ensureDir
import kotlin.io.path.Path
import org.lightningdevkit.ldknode.Network as LdkNetwork

internal const val SEED = "universe more push obey later jazz huge buzz magnet team muscle robust"

internal object Env {
    val isDebug = BuildConfig.DEBUG
    val network = Network.Regtest
    val trustedLnPeers = listOf(
        LnPeers.remote,
        // Peers.local,
    )
    val ldkRgsServerUrl: String?
        get() = when (network.ldk) {
            LdkNetwork.BITCOIN -> "https://rapidsync.lightningdevkit.org/snapshot/"
            else -> null
        }
    val esploraUrl: String
        get() = when (network) {
            Network.Regtest -> "https://electrs-regtest.synonym.to"
            else -> TODO("Not yet implemented")
        }

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
}
