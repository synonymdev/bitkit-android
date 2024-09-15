package to.bitkit.services

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.lightningdevkit.ldknode.AnchorChannelsConfig
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.defaultConfig
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.env.LnPeer
import to.bitkit.env.LnPeer.Companion.toLnPeer
import to.bitkit.env.REST
import to.bitkit.env.SEED
import to.bitkit.env.Tag.LDK
import to.bitkit.ext.uByteList
import to.bitkit.shared.LdkError
import to.bitkit.shared.ServiceError
import javax.inject.Inject

class LightningService @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
) : BaseCoroutineScope(bgDispatcher) {
    companion object {
        val shared by lazy {
            LightningService(Dispatchers.Default)
        }
    }

    var node: Node? = null

    fun setup(mnemonic: String = SEED) {
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

        Log.d(LDK, "Setting up node…")

        node = builder.build()

        Log.i(LDK, "Node set up")
    }

    suspend fun start() {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Log.d(LDK, "Starting node…")
        ServiceQueue.LDK.background {
            node.start()
        }
        Log.i(LDK, "Node started")
        connectToTrustedPeers()
    }

    suspend fun stop() {
        val node = this.node ?: throw ServiceError.NodeNotStarted

        Log.d(LDK, "Stopping node…")
        ServiceQueue.LDK.background {
            node.stop()
        }
        Log.i(LDK, "Node stopped.")
    }

    suspend fun sync() {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Log.d(LDK, "Syncing node…")
        ServiceQueue.LDK.background {
            node.syncWallets()
            // setMaxDustHtlcExposureForCurrentChannels()
        }
        Log.i(LDK, "Node synced")
    }

    suspend fun sign(message: String): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup
        val msg = runCatching { message.uByteList }.getOrNull() ?: throw ServiceError.InvalidNodeSigningMessage

        return ServiceQueue.LDK.background {
            node.signMessage(msg)
        }
    }

    // region peers
    private suspend fun connectToTrustedPeers() {
        for (peer in Env.trustedLnPeers) {
            connectPeer(peer)
        }
    }

    suspend fun connectPeer(peer: LnPeer) {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Log.d(LDK, "Connecting peer: $peer")

        val res = runCatching {
            ServiceQueue.LDK.background {
                node.connect(peer.nodeId, peer.address, persist = true)
            }
        }.onFailure { e ->
            (e as? NodeException)?.let { throw LdkError(it) }
        }

        Log.i(LDK, "Connection ${if (res.isSuccess) "succeeded" else "failed"} with: $peer")
    }
    // endregion

    // region channels
    suspend fun openChannel(peer: LnPeer) {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        // sendToAddress
        // mine 6 blocks & wait for esplora to pick up block
        // wait for esplora to pick up tx
        sync()

        ServiceQueue.LDK.background {
            node.connectOpenChannel(
                nodeId = peer.nodeId,
                address = peer.address,
                channelAmountSats = 50000u,
                pushToCounterpartyMsat = null,
                channelConfig = null,
                announceChannel = true,
            )
        }

        sync()

        val pendingEvent = node.nextEventAsync()
        check(pendingEvent is Event.ChannelPending) { "Expected ChannelPending event, got $pendingEvent" }
        node.eventHandled()

        Log.d(LDK, "Channel pending with peer: ${peer.address}")
        Log.d(LDK, "Channel funding txid: ${pendingEvent.fundingTxo.txid}")

        // wait for counterparty to pickup event: ChannelPending
        // wait for esplora to pick up tx: fundingTx
        // mine 6 blocks & wait for esplora to pick up block
        sync()

        val readyEvent = node.nextEventAsync()
        check(readyEvent is Event.ChannelReady) { "Expected ChannelReady event, got $readyEvent" }
        node.eventHandled()

        // wait for counterparty to pickup event: ChannelReady

        Log.i(LDK, "Channel ready: ${readyEvent.userChannelId}")
    }

    suspend fun closeChannel(userChannelId: String, counterpartyNodeId: String) {
        val node = this.node ?: throw ServiceError.NodeNotStarted

        ServiceQueue.LDK.background {
            node.closeChannel(userChannelId, counterpartyNodeId)
        }

        val event = node.nextEventAsync()
        check(event is Event.ChannelClosed) { "Expected ChannelClosed event, got $event" }
        node.eventHandled()

        // mine 1 block & wait for esplora to pick up block
        sync()

        Log.i(LDK, "Channel closed: $userChannelId")
    }
    // endregion

    // region payments
    suspend fun createInvoice(amountSat: ULong, description: String, expirySecs: UInt): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        return ServiceQueue.LDK.background {
            node.bolt11Payment().receive(amountMsat = amountSat * 1000u, description, expirySecs)
        }
    }

    suspend fun payInvoice(invoice: String): Boolean {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Log.d(LDK, "Paying invoice: $invoice")

        ServiceQueue.LDK.background {
            node.bolt11Payment().send(invoice)
        }
        node.eventHandled()

        when (val event = node.nextEventAsync()) {
            is Event.PaymentSuccessful -> {
                Log.i(LDK, "Payment successful for invoice: $invoice")
                return true
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
    }
    // endregion

    // region state
    val nodeId: String? get() = node?.nodeId()
    val balances: BalanceDetails? get() = node?.listBalances()
    val status: NodeStatus? get() = node?.status()
    val peers: List<LnPeer>? get() = node?.listPeers()?.map { it.toLnPeer() }
    val channels: List<ChannelDetails>? get() = node?.listChannels()
    val payments: List<PaymentDetails>? get() = node?.listPayments()
    // endregion
}

internal suspend fun warmupNode() {
    runCatching {
        LightningService.shared.apply {
            setup()
            start()
            sync()
        }
        BitcoinService.shared.apply {
            setup()
            fullScan()
        }
    }.onFailure {
        Log.e(LDK, "Node warmup error", it)
    }
}
