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
import to.bitkit._LDK

class LightningService {
    companion object {
        val shared by lazy {
            LightningService()
        }
    }

    lateinit var node: Node

    fun init(cwd: String) {
        val dir = Env.LdkStorage.init(cwd)

        val builder = Builder.fromConfig(
            defaultConfig().apply {
                storageDirPath = dir
                logDirPath = dir
                network = Env.Network.ldk
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
                setEntropyBip39Mnemonic(mnemonic = SEED, passphrase = null)
            }

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
    val nodeId: String get() = node.nodeId()
    val balances get() = node.listBalances()
    val status get() = node.status()
    val peers get() = node.listPeers()
    val channels get() = node.listChannels()
    val payments get() = node.listPayments()
}

internal fun LightningService.connectPeer(peer: LnPeer) {
    Log.d(_LDK, "Connecting peer: $peer")
    val res = runCatching {
        node.connect(peer.nodeId, peer.address(), persist = true)
    }
    Log.d(_LDK, "Connection ${if (res.isSuccess) "succeeded" else "failed"} with: $peer")
}

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
    check(pendingEvent is Event.ChannelPending)
    Log.d(_LDK, "Channel pending with peer: ${peer.address}")
    node.eventHandled()

    // wait for counterparty to pickup event: ChannelPending

    val fundingTxid = pendingEvent.fundingTxo.txid
    Log.d(_LDK, "Channel funding txid: $fundingTxid")

    // wait for esplora to pick up tx: fundingTx
    // mine 6 blocks & wait for esplora to pick up block

    sync()

    val readyEvent = node.nextEventAsync()
    check(readyEvent is Event.ChannelReady)
    node.eventHandled()

    // wait for counterparty to pickup event: ChannelReady
    val userChannelId = readyEvent.userChannelId
    Log.i(_LDK, "Channel ready: $userChannelId")
}

internal suspend fun LightningService.closeChannel(userChannelId: String, counterpartyNodeId: String) {
    node.closeChannel(userChannelId, counterpartyNodeId)

    val event = node.nextEventAsync()
    check(event is Event.ChannelClosed)
    Log.i(_LDK, "Channel closed: $userChannelId")
    node.eventHandled()

    // mine 1 block & wait for esplora to pick up block
    sync()
}

internal fun LightningService.createInvoice(): String {
    return node.bolt11Payment().receive(amountMsat = 112u, description = "description", expirySecs = 7200u)
}

internal suspend fun LightningService.payInvoice(invoice: String): Boolean {
    Log.d(_LDK, "Paying invoice: $invoice")

    node.bolt11Payment().send(invoice)

    val event = node.nextEventAsync()
    if (event is Event.PaymentSuccessful) {
        Log.i(_LDK, "Payment successful for invoice: $invoice")
    } else if (event is Event.PaymentFailed) {
        Log.e(_LDK, "Payment error: ${event.reason}")
        return false
    }
    node.eventHandled()

    val receivedEvent = node.nextEventAsync()
    check(receivedEvent is Event.PaymentReceived)
    node.eventHandled()

    return true
}
