package to.bitkit.ldk

import android.util.Log
import org.lightningdevkit.ldknode.AnchorChannelsConfig
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.defaultConfig
import to.bitkit.Env
import to.bitkit.LnPeer
import to.bitkit.REST
import to.bitkit.SEED
import to.bitkit.Tag.LDK
import to.bitkit.bdk.BitcoinService

// TODO support concurrency
class LightningService {
    companion object {
        val shared by lazy {
            LightningService()
        }
    }

    lateinit var node: Node

    fun init(mnemonic: String = SEED) {
        val dir = Env.Storage.ldk

        val builder = Builder
            .fromConfig(
                defaultConfig().apply {
                    storageDirPath = dir
                    logDirPath = dir
                    network = Env.network.ldk
                    logLevel = LogLevel.TRACE

                    trustedPeers0conf = Env.trustedLnPeers.map { it.nodeId }
                    anchorChannelsConfig = AnchorChannelsConfig(
                        trustedPeersNoReserve = trustedPeers0conf,
                        perChannelReserveSats = 2000u, // TODO set correctly
                    )
                })
            .apply {
                setEsploraServer(REST)
                if (Env.ldkRgsServerUrl != null) {
                    setGossipSourceRgs(requireNotNull(Env.ldkRgsServerUrl))
                } else {
                    setGossipSourceP2p()
                }
                setEntropyBip39Mnemonic(mnemonic, passphrase = null)
            }

        Log.d(LDK, "Building node...")

        node = builder.build()

        Log.i(LDK, "Node initialised.")
    }

    fun start() {
        check(::node.isInitialized) { "LDK node is not initialised" }
        Log.d(LDK, "Starting node...")

        node.start()

        Log.i(LDK, "Node started.")
        connectToTrustedPeers()
    }

    fun stop() {
        Log.d(LDK, "Stopping node...")
        node.stop()
        Log.i(LDK, "Node stopped.")
    }

    private fun connectToTrustedPeers() {
        for (peer in Env.trustedLnPeers) {
            connectPeer(peer)
        }
    }

    fun sync() {
        node.syncWallets()
    }

    // region state
    val nodeId: String get() = node.nodeId()
    val balances get() = node.listBalances()
    val status get() = node.status()
    val peers get() = node.listPeers()
    val channels get() = node.listChannels()
    val payments get() = node.listPayments()
    // endregion
}

// region peers
internal fun LightningService.connectPeer(peer: LnPeer) {
    Log.d(LDK, "Connecting peer: $peer")
    val res = runCatching {
        node.connect(peer.nodeId, peer.address, persist = true)
    }
    Log.d(LDK, "Connection ${if (res.isSuccess) "succeeded" else "failed"} with: $peer")
}
// endregion

// region channels
internal suspend fun LightningService.openChannel() {
    val peer = peers.first()

    // sendToAddress
    // mine 6 blocks & wait for esplora to pick up block
    // wait for esplora to pick up tx

    sync()
    node.connectOpenChannel(
        nodeId = peer.nodeId,
        address = peer.address,
        channelAmountSats = 50000u,
        pushToCounterpartyMsat = null,
        channelConfig = null,
        announceChannel = true,
    )
    sync()

    val pendingEvent = node.nextEventAsync()
    check(pendingEvent is Event.ChannelPending) { "Expected ChannelPending event, got $pendingEvent" }
    Log.d(LDK, "Channel pending with peer: ${peer.address}")
    node.eventHandled()

    val fundingTxid = pendingEvent.fundingTxo.txid
    Log.d(LDK, "Channel funding txid: $fundingTxid")

    // wait for counterparty to pickup event: ChannelPending
    // wait for esplora to pick up tx: fundingTx
    // mine 6 blocks & wait for esplora to pick up block

    sync()

    val readyEvent = node.nextEventAsync()
    check(readyEvent is Event.ChannelReady) { "Expected ChannelReady event, got $readyEvent" }
    node.eventHandled()

    // wait for counterparty to pickup event: ChannelReady
    val userChannelId = readyEvent.userChannelId
    Log.i(LDK, "Channel ready: $userChannelId")
}

internal suspend fun LightningService.closeChannel(userChannelId: String, counterpartyNodeId: String) {
    node.closeChannel(userChannelId, counterpartyNodeId)

    val event = node.nextEventAsync()
    check(event is Event.ChannelClosed) { "Expected ChannelClosed event, got $event" }
    Log.i(LDK, "Channel closed: $userChannelId")
    node.eventHandled()

    // mine 1 block & wait for esplora to pick up block
    sync()
}
// endregion

// region payments
internal fun LightningService.createInvoice(): String {
    return node.bolt11Payment().receive(amountMsat = 112u, description = "description", expirySecs = 7200u)
}

internal suspend fun LightningService.payInvoice(invoice: String): Boolean {
    Log.d(LDK, "Paying invoice: $invoice")

    node.bolt11Payment().send(invoice)

    when (val event = node.nextEventAsync()) {
        is Event.PaymentSuccessful -> {
            Log.i(LDK, "Payment successful for invoice: $invoice")
        }

        is Event.PaymentFailed -> {
            Log.e(LDK, "Payment error: ${event.reason}")
            return false
        }

        else -> {
            Log.e(LDK, "Expected PaymentSuccessful/PaymentFailed event, got $event")
            return false
        }
    }

    node.eventHandled()

    return true
}
// endregion

internal fun warmupNode() {
    runCatching {
        LightningService.shared.apply {
            init()
            start()
            sync()
        }
        BitcoinService.shared.apply {
            sync()
        }
    }.onFailure {
        Log.e(LDK, "Warmup error:", it)
    }
}
