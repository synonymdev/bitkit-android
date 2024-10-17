package to.bitkit.services

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.AnchorChannelsConfig
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.BuildException
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.Txid
import org.lightningdevkit.ldknode.defaultConfig
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.env.Tag.APP
import to.bitkit.env.Tag.LDK
import to.bitkit.ext.millis
import to.bitkit.ext.uByteList
import to.bitkit.models.LnPeer
import to.bitkit.models.LnPeer.Companion.toLnPeer
import to.bitkit.shared.LdkError
import to.bitkit.shared.ServiceError
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.time.Duration

typealias NodeEventHandler = suspend (Event) -> Unit

class LightningService @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
) : BaseCoroutineScope(bgDispatcher) {
    companion object {
        val shared by lazy {
            LightningService(Dispatchers.Default)
        }
    }

    var node: Node? = null

    fun setup(mnemonic: String) {
        val dir = Env.ldkStorage(0)

        val builder = Builder
            .fromConfig(
                defaultConfig().apply {
                    storageDirPath = dir
                    logDirPath = dir
                    network = Env.network
                    logLevel = LogLevel.TRACE

                    trustedPeers0conf = Env.trustedLnPeers.map { it.nodeId }
                    anchorChannelsConfig = AnchorChannelsConfig(
                        trustedPeersNoReserve = trustedPeers0conf,
                        perChannelReserveSats = 2000u, // TODO set correctly
                    )
                })
            .apply {
                setEsploraServer(Env.esploraUrl)
                if (Env.ldkRgsServerUrl != null) {
                    setGossipSourceRgs(requireNotNull(Env.ldkRgsServerUrl))
                } else {
                    setGossipSourceP2p()
                }
                setEntropyBip39Mnemonic(mnemonic, passphrase = null)
            }

        Log.d(LDK, "Setting up node…")

        node = try {
            builder.build()
        } catch (e: BuildException) {
            throw LdkError(e)
        }

        Log.i(LDK, "Node set up")
    }

    suspend fun start(timeout: Duration? = null, onEvent: NodeEventHandler? = null) {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Log.d(LDK, "Starting node…")
        ServiceQueue.LDK.background {
            node.start()
        }
        Log.i(LDK, "Node started")

        connectToTrustedPeers()

        onEvent?.let {
            launch(coroutineContext) {
                try {
                    if (timeout != null) {
                        withTimeout(timeout) { listen(it) }
                    } else {
                        listen(it)
                    }
                } catch (e: Exception) {
                    Log.e(LDK, "Error in event listener", e)
                }
            }
        }
    }

    suspend fun stop() {
        val node = this.node ?: throw ServiceError.NodeNotStarted

        Log.d(LDK, "Stopping node…")
        ServiceQueue.LDK.background {
            node.stop()
        }
        node.close().also { this.node = null }
        Log.i(LDK, "Node stopped.")
    }

    fun wipeStorage(walletIndex: Int) {
        if (node != null) throw ServiceError.NodeStillRunning
        Log.w(APP, "Wiping lightning storage…")
        Path(Env.ldkStorage(walletIndex)).toFile().deleteRecursively()
        Log.i(APP, "Lightning wallet wiped")
    }

    suspend fun sync() {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Log.d(LDK, "Syncing node…")
        ServiceQueue.LDK.background {
            node.syncWallets()
            setMaxDustHtlcExposureForCurrentChannels()
        }
        Log.i(LDK, "Node synced")
    }

    private fun setMaxDustHtlcExposureForCurrentChannels() {
        if (Env.network != Network.REGTEST) {
            Log.d(LDK, "Not updating channel config for non-regtest network")
            return
        }
        val node = this.node ?: throw ServiceError.NodeNotStarted
        for (channel in node.listChannels()) {
            val config = channel.config
            config.setMaxDustHtlcExposureFromFixedLimit(limitMsat= 999999_UL.millis)
            node.updateChannelConfig(channel.userChannelId, channel.counterpartyNodeId, config)
            Log.i(LDK, "Updated channel config for: ${channel.userChannelId}")
        }
    }

    suspend fun sign(message: String): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup
        val msg = runCatching { message.uByteList }.getOrNull() ?: throw ServiceError.InvalidNodeSigningMessage

        return ServiceQueue.LDK.background {
            node.signMessage(msg)
        }
    }

    fun newAddress(): String? {
        return node?.onchainPayment()?.newAddress()
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

        try {
            ServiceQueue.LDK.background {
                node.connect(peer.nodeId, peer.address, persist = true)
            }
            Log.i(LDK, "Connection succeeded with: $peer")
        } catch (e: NodeException) {
            Log.w(LDK, "Connection failed with: $peer", LdkError(e))
        }
    }
    // endregion

    // region channels
    suspend fun openChannel(peer: LnPeer, channelAmountSats: ULong, pushToCounterpartySats: ULong? = null) {
        val node = this@LightningService.node ?: throw ServiceError.NodeNotSetup

        try {
            ServiceQueue.LDK.background {
                node.connectOpenChannel(
                    nodeId = peer.nodeId,
                    address = peer.address,
                    channelAmountSats = channelAmountSats,
                    pushToCounterpartyMsat = pushToCounterpartySats?.millis,
                    channelConfig = null,
                    announceChannel = false,
                )
            }
        } catch (e: NodeException) {
            throw LdkError(e)
        }
    }

    suspend fun closeChannel(userChannelId: String, counterpartyNodeId: String) {
        val node = this.node ?: throw ServiceError.NodeNotStarted
        try {
            ServiceQueue.LDK.background {
                node.closeChannel(userChannelId, counterpartyNodeId)
            }
        } catch (e: NodeException) {
            throw LdkError(e)
        }
    }
    // endregion

    // region payments
    suspend fun receive(sat: ULong, description: String, expirySecs: UInt = 3600u): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        return ServiceQueue.LDK.background {
            node.bolt11Payment().receive(sat.millis, description, expirySecs)
        }
    }

    suspend fun send(address: Address, sats: ULong): Txid {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Log.i(LDK, "Sending $sats sats to $address")

        return ServiceQueue.LDK.background {
            node.onchainPayment().sendToAddress(address, sats)
        }
    }

    suspend fun send(bolt11: Bolt11Invoice, sats: ULong? = null): PaymentId {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Log.d(LDK, "Paying bolt11: $bolt11")

        return ServiceQueue.LDK.background {
            node.bolt11Payment().run {
                when (sats != null) {
                    true -> sendUsingAmount(bolt11, sats.millis)
                    else -> send(bolt11)
                }
            }
        }
    }
    // endregion

    // region events
    private suspend fun listen(onEvent: NodeEventHandler? = null) {
        while (true) {
            val node = this.node ?: let {
                Log.e(LDK, ServiceError.NodeNotStarted.message.orEmpty())
                return
            }
            val event = node.nextEventAsync()
            onEvent?.invoke(event)?.let { node.eventHandled() }

            // TODO: actual event handler
            logEvent(event)
        }
    }

    private fun logEvent(event: Event) {
        when (event) {
            is Event.PaymentSuccessful -> {
                val paymentId = event.paymentId ?: "?"
                val paymentHash = event.paymentHash
                val feePaidMsat = event.feePaidMsat ?: 0
                Log.i(
                    LDK,
                    "✅ Payment successful: paymentId: $paymentId paymentHash: $paymentHash feePaidMsat: $feePaidMsat"
                )
            }

            is Event.PaymentFailed -> {
                val paymentId = event.paymentId ?: "?"
                val paymentHash = event.paymentHash
                val reason = event.reason
                Log.i(LDK, "❌ Payment failed: paymentId: $paymentId paymentHash: $paymentHash reason: $reason")
            }

            is Event.PaymentReceived -> {
                val paymentId = event.paymentId ?: "?"
                val paymentHash = event.paymentHash
                val amountMsat = event.amountMsat
                Log.i(
                    LDK,
                    "🤑 Payment received: paymentId: $paymentId paymentHash: $paymentHash amountMsat: $amountMsat"
                )
            }

            is Event.PaymentClaimable -> {
                val paymentId = event.paymentId
                val paymentHash = event.paymentHash
                val claimableAmountMsat = event.claimableAmountMsat
                Log.i(
                    LDK,
                    "🫰 Payment claimable: paymentId: $paymentId paymentHash: $paymentHash claimableAmountMsat: $claimableAmountMsat"
                )
            }

            is Event.ChannelPending -> {
                val channelId = event.channelId
                val userChannelId = event.userChannelId
                val formerTemporaryChannelId = event.formerTemporaryChannelId
                val counterpartyNodeId = event.counterpartyNodeId
                val fundingTxo = event.fundingTxo
                Log.i(
                    LDK,
                    "⏳ Channel pending: channelId: $channelId userChannelId: $userChannelId formerTemporaryChannelId: $formerTemporaryChannelId counterpartyNodeId: $counterpartyNodeId fundingTxo: $fundingTxo"
                )
            }

            is Event.ChannelReady -> {
                val channelId = event.channelId
                val userChannelId = event.userChannelId
                val counterpartyNodeId = event.counterpartyNodeId ?: "?"
                Log.i(
                    LDK,
                    "👐 Channel ready: channelId: $channelId userChannelId: $userChannelId counterpartyNodeId: $counterpartyNodeId"
                )
            }

            is Event.ChannelClosed -> {
                val channelId = event.channelId
                val userChannelId = event.userChannelId
                val counterpartyNodeId = event.counterpartyNodeId ?: "?"
                val reason = event.reason
                Log.i(
                    LDK,
                    "⛔ Channel closed: channelId: $channelId userChannelId: $userChannelId counterpartyNodeId: $counterpartyNodeId reason: $reason"
                )
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
