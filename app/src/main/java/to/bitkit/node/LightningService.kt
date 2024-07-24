package to.bitkit.node

import android.util.Log
import org.lightningdevkit.ldknode.AnchorChannelsConfig
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.PeerDetails
import org.lightningdevkit.ldknode.defaultConfig
import to.bitkit.Env
import to.bitkit.LnPeer
import to.bitkit.REST
import to.bitkit.SEED
import to.bitkit._LDK
import to.bitkit.ldkDir

internal class LightningService {
    companion object {
        val shared by lazy {
            LightningService()
        }
    }

    lateinit var node: Node

    fun init(cwd: String) {
        val dir = ldkDir(cwd)
        Env.LdkStorage.path = dir

        val builder = Builder.fromConfig(
            defaultConfig().apply {
                storageDirPath = dir
                logDirPath = dir
                network = Env.Network.ldk
                logLevel = LogLevel.TRACE

                trustedPeers0conf = Env.trustedLnPeers.map { it.nodeId }
                anchorChannelsConfig = AnchorChannelsConfig(
                    trustedPeersNoReserve = trustedPeers0conf,
                    perChannelReserveSats = 2000.toULong(), // TODO set correctly
                )
            })
            .apply {
                setEsploraServer(REST)
                if (Env.ldkRgsServerUrl != null) {
                    setGossipSourceRgs(requireNotNull(Env.ldkRgsServerUrl))
                } else {
                    setGossipSourceP2p()
                }
                setEntropyBip39Mnemonic(mnemonic = SEED, passphrase = null)
            }

        Log.d(_LDK, "LDK storage path: ${Env.LdkStorage.path}")
        Log.d(_LDK, "Building node...")

        node = builder.build()

        Log.i(_LDK, "Node initialised.")
    }

    fun start(/* onEvent: ((Event) -> Unit)? = null */) {
        val node = checkNotNull(this.node) { "LDK node is not initialised" }

        // onEvent?.let { node.listenForEvents(it) }

        Log.d(_LDK, "Starting node...")

        node.start()

        Log.i(_LDK, "Node started.")

        connectToTrustedPeers()
    }

    // private fun Node.listenForEvents(onEvent: (Event) -> Unit) {}

    private fun connectToTrustedPeers() {
        for (peer in Env.trustedLnPeers) {
            connectPeer(peer)
        }
    }

    fun sync() {
        node.syncWallets()
    }

    // region State
    fun nodeId() = node.nodeId()
    fun balances() = node.listBalances()
    fun status() = node.status()
    fun peers(): List<PeerDetails> = node.listPeers()
    fun channels() = node.listChannels()
    fun payments() = node.listPayments()
}

internal fun LightningService.connectPeer(peer: LnPeer) {
    Log.d(_LDK, "Connecting peer: $peer")
    val res = runCatching {
        node.connect(peer.nodeId, peer.address(), persist = true)
    }
    Log.d(_LDK, "Connection ${if (res.isSuccess) "succeeded" else "failed"} with: $peer")
}
