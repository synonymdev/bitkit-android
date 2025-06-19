package to.bitkit.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.AnchorChannelsConfig
import org.lightningdevkit.ldknode.BackgroundSyncConfig
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.BuildException
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.CoinSelectionAlgorithm
import org.lightningdevkit.ldknode.EsploraSyncConfig
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.FeeRate
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.SpendableUtxo
import org.lightningdevkit.ldknode.Txid
import org.lightningdevkit.ldknode.UserChannelId
import org.lightningdevkit.ldknode.defaultConfig
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.DatePattern
import to.bitkit.ext.uByteList
import to.bitkit.models.LnPeer
import to.bitkit.models.LnPeer.Companion.toLnPeer
import to.bitkit.utils.LdkError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import uniffi.bitkitcore.Scanner
import uniffi.bitkitcore.decode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

typealias NodeEventHandler = suspend (Event) -> Unit

@Singleton
class LightningService @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val vssStoreIdProvider: VssStoreIdProvider,
) : BaseCoroutineScope(bgDispatcher) {

    var node: Node? = null

    suspend fun setup(walletIndex: Int) {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

        val dirPath = Env.ldkStoragePath(walletIndex)

        val builder = Builder
            .fromConfig(
                defaultConfig().apply {
                    storageDirPath = dirPath
                    network = Env.network

                    trustedPeers0conf = Env.trustedLnPeers.map { it.nodeId }
                    anchorChannelsConfig = AnchorChannelsConfig(
                        trustedPeersNoReserve = trustedPeers0conf,
                        perChannelReserveSats = 1u,
                    )
                })
            .apply {
                setFilesystemLogger(generateLogFilePath(), Env.ldkLogLevel)

                setChainSourceEsplora(
                    serverUrl = Env.esploraServerUrl,
                    config = EsploraSyncConfig(
                        BackgroundSyncConfig(
                            onchainWalletSyncIntervalSecs = Env.walletSyncIntervalSecs,
                            lightningWalletSyncIntervalSecs = Env.walletSyncIntervalSecs,
                            feeRateCacheUpdateIntervalSecs = Env.walletSyncIntervalSecs,
                        ),
                    ),
                )
                if (Env.ldkRgsServerUrl != null) {
                    setGossipSourceRgs(requireNotNull(Env.ldkRgsServerUrl))
                } else {
                    setGossipSourceP2p()
                }
                setEntropyBip39Mnemonic(mnemonic, passphrase)
            }

        Logger.debug("Building node…")

        val vssStoreId = vssStoreIdProvider.getVssStoreId()

        ServiceQueue.LDK.background {
            node = try {
                builder.buildWithVssStoreAndFixedHeaders(
                    vssUrl = Env.vssServerUrl,
                    storeId = vssStoreId,
                    fixedHeaders = emptyMap(),
                )
            } catch (e: BuildException) {
                throw LdkError(e)
            }
        }

        Logger.info("LDK node setup")
    }

    suspend fun start(timeout: Duration? = null, onEvent: NodeEventHandler? = null) {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        onEvent?.let { eventHandler ->
            shouldListenForEvents = true
            launch {
                try {
                    if (timeout != null) {
                        withTimeout(timeout) { listenForEvents(eventHandler) }
                    } else {
                        listenForEvents(eventHandler)
                    }
                } catch (e: Exception) {
                    Logger.error("LDK event listener error", e)
                }
            }
        }

        Logger.debug("Starting node…")
        ServiceQueue.LDK.background {
            node.start()
        }
        Logger.info("Node started")
    }

    suspend fun stop() {
        shouldListenForEvents = false
        val node = this.node ?: throw ServiceError.NodeNotStarted

        Logger.debug("Stopping node…")
        ServiceQueue.LDK.background {
            node.stop()
            this@LightningService.node = null
        }
        Logger.info("Node stopped")
    }

    fun wipeStorage(walletIndex: Int) {
        if (node != null) throw ServiceError.NodeStillRunning
        Logger.warn("Wiping lightning storage…")
        Path(Env.ldkStoragePath(walletIndex)).toFile().deleteRecursively()
        Logger.info("Lightning wallet wiped")
    }

    suspend fun sync() {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Logger.debug("Syncing LDK…")
        ServiceQueue.LDK.background {
            node.syncWallets()
            // launch { setMaxDustHtlcExposureForCurrentChannels() }
        }
        Logger.info("LDK synced")
    }

    // private fun setMaxDustHtlcExposureForCurrentChannels() {
    //     if (Env.network != Network.REGTEST) {
    //         Logger.debug("Not updating channel config for non-regtest network")
    //         return
    //     }
    //     val node = this.node ?: throw ServiceError.NodeNotStarted
    //     runCatching {
    //         for (channel in node.listChannels()) {
    //             val config = channel.config
    //             config.maxDustHtlcExposure = MaxDustHtlcExposure.FixedLimit(limitMsat = 999_999_UL.millis)
    //             node.updateChannelConfig(channel.userChannelId, channel.counterpartyNodeId, config)
    //             Logger.info("Updated channel config for: ${channel.userChannelId}")
    //         }
    //     }.onFailure {
    //         Logger.error("Failed to update channel config", it)
    //     }
    // }

    suspend fun sign(message: String): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup
        val msg = runCatching { message.uByteList }.getOrNull() ?: throw ServiceError.InvalidNodeSigningMessage

        return ServiceQueue.LDK.background {
            node.signMessage(msg)
        }
    }

    suspend fun newAddress(): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        return ServiceQueue.LDK.background {
            node.onchainPayment().newAddress()
        }
    }

    // region peers
    suspend fun connectToTrustedPeers() {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        ServiceQueue.LDK.background {
            for (peer in Env.trustedLnPeers) {
                try {
                    node.connect(peer.nodeId, peer.address, persist = true)
                    Logger.info("Connected to trusted peer: $peer")
                } catch (e: NodeException) {
                    Logger.error("Peer connect error: $peer", LdkError(e))
                }
            }
        }
    }

    suspend fun connectPeer(peer: LnPeer): Result<Unit> {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        return ServiceQueue.LDK.background {
            try {
                Logger.debug("Connecting peer: $peer")

                node.connect(peer.nodeId, peer.address, persist = true)

                Logger.info("Peer connected: $peer")

                Result.success(Unit)
            } catch (e: NodeException) {
                val error = LdkError(e)
                Logger.error("Peer connect error: $peer", error)
                Result.failure(error)
            }
        }
    }

    suspend fun disconnectPeer(peer: LnPeer) {
        val node = this.node ?: throw ServiceError.NodeNotSetup
        Logger.debug("Disconnecting peer: $peer")
        try {
            ServiceQueue.LDK.background {
                node.disconnect(peer.nodeId)
            }
            Logger.info("Peer disconnected: $peer")
        } catch (e: NodeException) {
            Logger.warn("Peer disconnect error: $peer", LdkError(e))
        }
    }
    // endregion

    // region channels
    suspend fun openChannel(
        peer: LnPeer,
        channelAmountSats: ULong,
        pushToCounterpartySats: ULong? = null,
    ): Result<UserChannelId> {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        return ServiceQueue.LDK.background {
            try {
                Logger.debug("Initiating channel open (sats: $channelAmountSats) with peer: $peer")

                val userChannelId = node.openChannel(
                    nodeId = peer.nodeId,
                    address = peer.address,
                    channelAmountSats = channelAmountSats,
                    pushToCounterpartyMsat = pushToCounterpartySats?.let { it * 1000u },
                    channelConfig = null,
                )

                Logger.info("Channel open initiated, userChannelId: $userChannelId")

                Result.success(userChannelId)
            } catch (e: NodeException) {
                val error = LdkError(e)
                Logger.error("Error initiating channel open", error)
                Result.failure(error)
            }
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
    suspend fun receive(sat: ULong? = null, description: String, expirySecs: UInt = 3600u): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        val message = description.ifBlank { Env.DEFAULT_INVOICE_MESSAGE }
        val amountSats = sat ?: 0u

        return ServiceQueue.LDK.background {
            val bip21 = node.unifiedQrPayment().receive(
                amountSats = amountSats,
                message = message,
                expirySec = expirySecs,
            )

            return@background extractBolt11String(bip21)

            // TODO restore when ldk-node brings back support to get bolt11 string from 'Bolt11Invoice' model
            // if (sat != null) {
            //     node.bolt11Payment()
            //         .receive(
            //             amountMsat = sat * 1000u,
            //             description = Bolt11InvoiceDescription.Direct(
            //                 description = message
            //             ),
            //             expirySecs = expirySecs,
            //         )
            // } else {
            //     node.bolt11Payment()
            //         .receiveVariableAmount(
            //             description = Bolt11InvoiceDescription.Direct(
            //                 description = message
            //             ),
            //             expirySecs = expirySecs,
            //         )
            // }
        }
    }

    fun canSend(amountSats: ULong): Boolean {
        val channels = this.channels
        if (channels == null) {
            Logger.warn("Channels not available")
            return false
        }

        val totalNextOutboundHtlcLimitSats = channels
            .filter { it.isUsable }
            .sumOf { it.nextOutboundHtlcLimitMsat / 1000uL }

        if (totalNextOutboundHtlcLimitSats < amountSats) {
            Logger.warn("Insufficient outbound capacity: $totalNextOutboundHtlcLimitSats < $amountSats")
            return false
        }

        return true
    }

    suspend fun send(
        address: Address,
        sats: ULong,
        satsPerVByte: UInt,
        utxosToSpend: List<SpendableUtxo>? = null,
    ): Txid {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Logger.info("Sending $sats sats to $address with satsPerVByte=$satsPerVByte")

        return ServiceQueue.LDK.background {
            node.onchainPayment().sendToAddress(
                address = address,
                amountSats = sats,
                feeRate = convertVByteToKwu(satsPerVByte),
                utxosToSpend = utxosToSpend,
            )
        }
    }

    suspend fun send(bolt11: String, sats: ULong? = null): PaymentId {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Logger.debug("Paying bolt11: $bolt11")

        val bolt11Invoice = runCatching { Bolt11Invoice.fromStr(bolt11) }
            .getOrElse { e -> throw LdkError(e as NodeException) }

        return ServiceQueue.LDK.background {
            when (sats != null) {
                true -> node.bolt11Payment().sendUsingAmount(bolt11Invoice, sats * 1000u, null)
                else -> node.bolt11Payment().send(bolt11Invoice, null)
            }
        }
    }
    // endregion

    // region utxo selection
    suspend fun listSpendableOutputs(): Result<List<SpendableUtxo>> {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        return ServiceQueue.LDK.background {
            return@background try {
                val result = node.onchainPayment().listSpendableOutputs()
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(
                    if (e is NodeException) LdkError(e) else e
                )
            }
        }
    }

    suspend fun selectUtxosWithAlgorithm(
        targetAmountSats: ULong,
        satsPerVByte: UInt,
        algorithm: CoinSelectionAlgorithm,
        utxos: List<SpendableUtxo>?,
    ): Result<List<SpendableUtxo>> {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        return ServiceQueue.LDK.background {
            return@background try {
                val result = node.onchainPayment().selectUtxosWithAlgorithm(
                    targetAmountSats = targetAmountSats,
                    feeRate = convertVByteToKwu(satsPerVByte),
                    algorithm = algorithm,
                    utxos = utxos,
                )
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(
                    if (e is NodeException) LdkError(e) else e
                )
            }
        }
    }
    // endregion

    // region boost
    suspend fun bumpFeeByRbf(txid: Txid, satsPerVByte: UInt): Txid {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Logger.info("Bumping fee for tx $txid with satsPerVByte=$satsPerVByte")

        return ServiceQueue.LDK.background {
            return@background try {
                node.onchainPayment().bumpFeeByRbf(
                    txid = txid,
                    feeRate = convertVByteToKwu(satsPerVByte),
                )
            } catch (e: NodeException) {
                throw LdkError(e)
            }
        }
    }

    suspend fun accelerateByCpfp(
        txid: Txid,
        satsPerVByte: UInt,
        destinationAddress: Address,
    ): Txid {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Logger.info("Accelerating tx $txid by CPFP, satsPerVByte=$satsPerVByte, destinationAddress=$destinationAddress")

        return ServiceQueue.LDK.background {
            return@background try {
                node.onchainPayment().accelerateByCpfp(
                    txid = txid,
                    feeRate = convertVByteToKwu(satsPerVByte),
                    destinationAddress = destinationAddress,
                )
            } catch (e: NodeException) {
                throw LdkError(e)
            }
        }
    }
    // endregion

    // region events
    private var shouldListenForEvents = true

    suspend fun listenForEvents(onEvent: NodeEventHandler? = null) {
        while (shouldListenForEvents) {
            val node = this.node ?: let {
                Logger.error(ServiceError.NodeNotStarted.message.orEmpty())
                return
            }
            val event = node.nextEventAsync()

            try {
                node.eventHandled()
                Logger.debug("LDK eventHandled: $event")
            } catch (e: NodeException) {
                Logger.error("LDK eventHandled error", LdkError(e))
            }

            logEvent(event)
            onEvent?.invoke(event)
        }
    }

    private fun logEvent(event: Event) {
        when (event) {
            is Event.PaymentSuccessful -> {
                val paymentId = event.paymentId ?: "?"
                val paymentHash = event.paymentHash
                val feePaidMsat = event.feePaidMsat ?: 0
                Logger.info("✅ Payment successful: paymentId: $paymentId paymentHash: $paymentHash feePaidMsat: $feePaidMsat")
            }

            is Event.PaymentFailed -> {
                val paymentId = event.paymentId ?: "?"
                val paymentHash = event.paymentHash
                val reason = event.reason
                Logger.info("❌ Payment failed: paymentId: $paymentId paymentHash: $paymentHash reason: $reason")
            }

            is Event.PaymentReceived -> {
                val paymentId = event.paymentId ?: "?"
                val paymentHash = event.paymentHash
                val amountMsat = event.amountMsat
                Logger.info("🤑 Payment received: paymentId: $paymentId paymentHash: $paymentHash amountMsat: $amountMsat")
            }

            is Event.PaymentClaimable -> {
                val paymentId = event.paymentId
                val paymentHash = event.paymentHash
                val claimableAmountMsat = event.claimableAmountMsat
                Logger.info("🫰 Payment claimable: paymentId: $paymentId paymentHash: $paymentHash claimableAmountMsat: $claimableAmountMsat")
            }

            is Event.PaymentForwarded -> Unit

            is Event.ChannelPending -> {
                val channelId = event.channelId
                val userChannelId = event.userChannelId
                val formerTemporaryChannelId = event.formerTemporaryChannelId
                val counterpartyNodeId = event.counterpartyNodeId
                val fundingTxo = event.fundingTxo
                Logger.info("⏳ Channel pending: channelId: $channelId userChannelId: $userChannelId formerTemporaryChannelId: $formerTemporaryChannelId counterpartyNodeId: $counterpartyNodeId fundingTxo: $fundingTxo")
            }

            is Event.ChannelReady -> {
                val channelId = event.channelId
                val userChannelId = event.userChannelId
                val counterpartyNodeId = event.counterpartyNodeId ?: "?"
                Logger.info("👐 Channel ready: channelId: $channelId userChannelId: $userChannelId counterpartyNodeId: $counterpartyNodeId")
            }

            is Event.ChannelClosed -> {
                val channelId = event.channelId
                val userChannelId = event.userChannelId
                val counterpartyNodeId = event.counterpartyNodeId ?: "?"
                val reason = event.reason
                Logger.info("⛔ Channel closed: channelId: $channelId userChannelId: $userChannelId counterpartyNodeId: $counterpartyNodeId reason: $reason")
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

    fun syncFlow(): Flow<Unit> = flow {
        while (currentCoroutineContext().isActive) {
            emit(Unit)
            delay(Env.ldkNodeSyncIntervalSecs.toLong().seconds)
        }
    }.flowOn(bgDispatcher)
    // endregion
}

// region helpers

/** Returns only `open` channels, filtering out pending ones. */
fun List<ChannelDetails>.filterOpen(): List<ChannelDetails> {
    return this.filter { it.isChannelReady }
}

private fun generateLogFilePath(): String {
    val dateFormatter = SimpleDateFormat(DatePattern.LOG_FILE, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val timestamp = dateFormatter.format(Date())

    val sessionLogFilePath = File(Env.logDir).resolve("ldk_$timestamp.log").path

    Logger.debug("Generated LDK log file path: $sessionLogFilePath")
    return sessionLogFilePath
}

private fun convertVByteToKwu(satsPerVByte: UInt): FeeRate {
    // 1 vbyte = 4 weight units, so 1 sats/vbyte = 250 sats/kwu
    val satPerKwu = satsPerVByte.toULong() * 250u
    // Ensure we're above the minimum relay fee
    return FeeRate.fromSatPerKwu(maxOf(satPerKwu, 253u)) // FEERATE_FLOOR_SATS_PER_KW is 253 in LDK
}

private suspend fun extractBolt11String(bip21: String): String {
    return (decode(bip21.lowercase()) as? Scanner.OnChain)
        ?.let { onchainScan -> onchainScan.invoice.params?.get("lightning") }
        ?: error("Invalid bip21 string format")
}
// endregion
