package to.bitkit.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.AnchorChannelsConfig
import org.lightningdevkit.ldknode.BackgroundSyncConfig
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Bolt11InvoiceDescription
import org.lightningdevkit.ldknode.BuildException
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.ChannelConfig
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.CoinSelectionAlgorithm
import org.lightningdevkit.ldknode.Config
import org.lightningdevkit.ldknode.ElectrumSyncConfig
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.FeeRate
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.PeerDetails
import org.lightningdevkit.ldknode.SpendableUtxo
import org.lightningdevkit.ldknode.TransactionDetails
import org.lightningdevkit.ldknode.Txid
import org.lightningdevkit.ldknode.defaultConfig
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.data.SettingsStore
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.ext.uByteList
import to.bitkit.ext.uri
import to.bitkit.models.OpenChannelResult
import to.bitkit.utils.LdkError
import to.bitkit.utils.LdkLogWriter
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import to.bitkit.utils.jsonLogOf
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.Path
import kotlin.time.Duration

typealias NodeEventHandler = suspend (Event) -> Unit

@Suppress("LargeClass")
@Singleton
class LightningService @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val vssStoreIdProvider: VssStoreIdProvider,
    private val settingsStore: SettingsStore,
) : BaseCoroutineScope(bgDispatcher) {

    @Volatile
    var node: Node? = null

    private val _syncStatusChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncStatusChanged: SharedFlow<Unit> = _syncStatusChanged.asSharedFlow()

    private lateinit var trustedPeers: List<PeerDetails>

    suspend fun setup(
        walletIndex: Int,
        customServerUrl: String? = null,
        customRgsServerUrl: String? = null,
    ) {
        Logger.debug("Building node…")

        val config = config(walletIndex)
        node = build(
            walletIndex,
            customServerUrl,
            customRgsServerUrl,
            config,
        )

        Logger.info("LDK node setup")
    }

    private fun config(
        walletIndex: Int,
    ): Config {
        val dirPath = Env.ldkStoragePath(walletIndex)

        // TODO get trustedLnPeers from blocktank info
        this.trustedPeers = Env.trustedLnPeers
        val trustedPeerNodeIds = trustedPeers.map { it.nodeId }

        return defaultConfig().copy(
            storageDirPath = dirPath,
            network = Env.network,
            trustedPeers0conf = trustedPeerNodeIds,
            anchorChannelsConfig = AnchorChannelsConfig(
                trustedPeersNoReserve = trustedPeerNodeIds,
                perChannelReserveSats = 1u,
            ),
        )
    }

    private suspend fun build(
        walletIndex: Int,
        customServerUrl: String?,
        customRgsServerUrl: String?,
        config: Config,
    ): Node = ServiceQueue.LDK.background {
        val builder = Builder.fromConfig(config).apply {
            setCustomLogger(LdkLogWriter())
            configureChainSource(customServerUrl)
            configureGossipSource(customRgsServerUrl)
            setEntropyBip39Mnemonic(
                mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound,
                passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name),
            )
        }
        try {
            val vssStoreId = vssStoreIdProvider.getVssStoreId(walletIndex)
            val vssUrl = Env.vssServerUrl
            val lnurlAuthServerUrl = Env.lnurlAuthServerUrl
            val fixedHeaders = emptyMap<String, String>()
            Logger.verbose(
                "Building ldk-node with \n\t vssUrl: '$vssUrl'\n\t lnurlAuthServerUrl: '$lnurlAuthServerUrl'"
            )
            if (lnurlAuthServerUrl.isNotEmpty()) {
                builder.buildWithVssStore(vssUrl, vssStoreId, lnurlAuthServerUrl, fixedHeaders)
            } else {
                builder.buildWithVssStoreAndFixedHeaders(vssUrl, vssStoreId, fixedHeaders)
            }
        } catch (e: BuildException) {
            throw LdkError(e)
        } finally {
            // TODO: cleanup sensitive data after implementing a `SecureString` value holder for Keychain return values
        }
    }

    private suspend fun Builder.configureGossipSource(customRgsServerUrl: String?) {
        val rgsServerUrl = customRgsServerUrl ?: settingsStore.data.first().rgsServerUrl
        if (rgsServerUrl != null) {
            Logger.info("Using gossip source: RGS server '$rgsServerUrl'")
            setGossipSourceRgs(rgsServerUrl)
        } else {
            Logger.info("Using gossip source: P2P")
            setGossipSourceP2p()
        }
    }

    private suspend fun Builder.configureChainSource(customServerUrl: String? = null) {
        val serverUrl = customServerUrl ?: settingsStore.data.first().electrumServer
        Logger.info("Using onchain source Electrum Sever url: $serverUrl")
        setChainSourceElectrum(
            serverUrl = serverUrl,
            config = ElectrumSyncConfig(
                BackgroundSyncConfig(
                    onchainWalletSyncIntervalSecs = Env.walletSyncIntervalSecs,
                    lightningWalletSyncIntervalSecs = Env.walletSyncIntervalSecs,
                    feeRateCacheUpdateIntervalSecs = Env.walletSyncIntervalSecs,
                ),
            ),
        )
    }

    suspend fun start(timeout: Duration? = null, onEvent: NodeEventHandler? = null) {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Logger.debug("Starting node…")

        ServiceQueue.LDK.background {
            try {
                node.start()
            } catch (e: NodeException) {
                throw LdkError(e)
            }
        }

        // start event listener after node started
        onEvent?.let { eventHandler ->
            shouldListenForEvents = true
            launch {
                try {
                    Logger.debug("LDK event listener started")
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

        Logger.info("Node started")
    }

    suspend fun stop() {
        shouldListenForEvents = false
        val node = this.node ?: throw ServiceError.NodeNotStarted

        Logger.debug("Stopping node…")
        ServiceQueue.LDK.background {
            try {
                node.stop()
                this@LightningService.node = null
            } catch (_: NodeException.NotRunning) {
                // Node is not running, clear the reference
                this@LightningService.node = null
            }
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

        Logger.verbose("Syncing LDK…")
        ServiceQueue.LDK.background {
            node.syncWallets()
            // launch { setMaxDustHtlcExposureForCurrentChannels() }
        }

        _syncStatusChanged.tryEmit(Unit)

        Logger.debug("LDK synced")
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
    //             config.maxDustHtlcExposure = MaxDustHtlcExposure.FixedLimit(limitMsat = 999_999_UL * 1000u)
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
            for (peer in trustedPeers) {
                try {
                    node.connect(peer.nodeId, peer.address, persist = true)
                    Logger.info("Connected to trusted peer: $peer")
                } catch (e: NodeException) {
                    Logger.error("Peer connect error: $peer", LdkError(e))
                }
            }
        }
    }

    suspend fun connectPeer(peer: PeerDetails): Result<Unit> {
        val node = this.node ?: throw ServiceError.NodeNotSetup
        val uri = peer.uri
        return ServiceQueue.LDK.background {
            try {
                Logger.debug("Connecting peer: $uri")

                node.connect(peer.nodeId, peer.address, persist = true)

                Logger.info("Peer connected: $uri")

                Result.success(Unit)
            } catch (e: NodeException) {
                val error = LdkError(e)
                Logger.error("Peer connect error: $uri", error)
                Result.failure(error)
            }
        }
    }

    suspend fun disconnectPeer(peer: PeerDetails) {
        val node = this.node ?: throw ServiceError.NodeNotSetup
        val uri = peer.uri
        Logger.debug("Disconnecting peer: $uri")
        try {
            ServiceQueue.LDK.background {
                node.disconnect(peer.nodeId)
            }
            Logger.info("Peer disconnected: $uri")
        } catch (e: NodeException) {
            Logger.warn("Peer disconnect error: $uri", LdkError(e))
        }
    }

    private fun getLspPeers(): List<PeerDetails> {
        val lspPeers = Env.trustedLnPeers
        // TODO get from blocktank info.nodes[] when setup uses it to set trustedPeers0conf
        // pseudocode idea:
        // val lspPeers = getInfo(true)?.nodes?.map { PeerDetails.from(nodeId = it.pubkey, address = "TO DO") }
        return lspPeers
    }

    fun hasExternalPeers(): Boolean {
        val ourPeers = this.peers.orEmpty().map { it.uri }
        val lspPeers = getLspPeers().map { it.uri }.toSet()
        return ourPeers.any { p -> p !in lspPeers }
    }

    // endregion

    // region channels
    suspend fun openChannel(
        peer: PeerDetails,
        channelAmountSats: ULong,
        pushToCounterpartySats: ULong? = null,
        channelConfig: ChannelConfig? = null,
    ): Result<OpenChannelResult> {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        return ServiceQueue.LDK.background {
            try {
                val pushToCounterpartyMsat = pushToCounterpartySats?.let { it * 1000u }
                Logger.debug("Initiating channel open (sats: $channelAmountSats) with peer: ${peer.uri}")

                val userChannelId = node.openChannel(
                    peer.nodeId,
                    peer.address,
                    channelAmountSats,
                    pushToCounterpartyMsat,
                    channelConfig,
                )

                val result = OpenChannelResult(
                    userChannelId,
                    peer,
                    channelAmountSats,
                    pushToCounterpartySats,
                    channelConfig,
                )

                Logger.info("Channel open initiated, result: $result")

                Result.success(result)
            } catch (e: NodeException) {
                val error = LdkError(e)
                Logger.error("Error initiating channel open", error)
                Result.failure(error)
            }
        }
    }

    suspend fun closeChannel(
        channel: ChannelDetails,
        force: Boolean = false,
        forceCloseReason: String? = null,
    ) {
        val node = this.node ?: throw ServiceError.NodeNotStarted
        val channelId = channel.channelId
        val userChannelId = channel.userChannelId
        val counterpartyNodeId = channel.counterpartyNodeId
        try {
            ServiceQueue.LDK.background {
                Logger.debug("Initiating channel close (force=$force): '$channelId'", context = TAG)
                if (force) {
                    node.forceCloseChannel(userChannelId, counterpartyNodeId, forceCloseReason.orEmpty())
                } else {
                    node.closeChannel(userChannelId, counterpartyNodeId)
                }
            }
            Logger.info("Channel close initiated (force=$force): '$channelId'", context = TAG)
        } catch (e: NodeException) {
            val error = LdkError(e)
            Logger.error("Error initiating channel close (force=$force): '$channelId'", error, context = TAG)
            throw LdkError(e)
        }
    }
    // endregion

    // region payments
    fun canReceive(): Boolean {
        val channels = this.channels
        if (channels == null) {
            Logger.warn("canReceive = false: Channels not available")
            return false
        }

        if (channels.none { it.isChannelReady }) {
            Logger.warn("canReceive = false: Found no LN channel ready to enable receive: $channels")
            return false
        }

        return true
    }

    suspend fun receive(sat: ULong? = null, description: String, expirySecs: UInt = 3600u): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        val message = description.ifBlank { Env.DEFAULT_INVOICE_MESSAGE }

        return ServiceQueue.LDK.background {
            val bolt11Invoice: Bolt11Invoice = if (sat != null) {
                node.bolt11Payment()
                    .receive(
                        amountMsat = sat * 1000u,
                        description = Bolt11InvoiceDescription.Direct(description = message),
                        expirySecs = expirySecs,
                    )
            } else {
                node.bolt11Payment()
                    .receiveVariableAmount(
                        description = Bolt11InvoiceDescription.Direct(description = message),
                        expirySecs = expirySecs,
                    )
            }

            return@background bolt11Invoice.toString()
        }
    }

    fun canSend(amountSats: ULong): Boolean {
        val channels = this.channels
        if (channels == null) {
            Logger.warn("Channels not available")
            return false
        }

        val totalNextOutboundHtlcLimitSats = channels.totalNextOutboundHtlcLimitSats()

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
        isMaxAmount: Boolean = false,
    ): Txid {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Logger.info("Sending $sats sats to $address, satsPerVByte=$satsPerVByte, isMaxAmount = $isMaxAmount")

        return ServiceQueue.LDK.background {
            if (isMaxAmount) {
                node.onchainPayment().sendAllToAddress(
                    address = address,
                    retainReserve = true,
                    feeRate = FeeRate.fromSatPerVbUnchecked(satsPerVByte.toULong()),
                )
            } else {
                node.onchainPayment().sendToAddress(
                    address = address,
                    amountSats = sats,
                    feeRate = convertVByteToKwu(satsPerVByte),
                    utxosToSpend = utxosToSpend,
                )
            }
        }
    }

    suspend fun send(bolt11: String, sats: ULong? = null): PaymentId {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Logger.debug("Paying bolt11: $bolt11")

        val bolt11Invoice = runCatching { Bolt11Invoice.fromStr(bolt11) }
            .getOrElse { e -> throw LdkError(e as NodeException) }

        return ServiceQueue.LDK.background {
            runCatching {
                when (sats != null) {
                    true -> node.bolt11Payment().sendUsingAmount(bolt11Invoice, sats * 1000u, null)
                    else -> node.bolt11Payment().send(bolt11Invoice, null)
                }
            }
        }.getOrThrow()
    }

    suspend fun estimateRoutingFees(bolt11: String): Result<ULong> {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        return ServiceQueue.LDK.background {
            return@background try {
                val invoice = Bolt11Invoice.fromStr(bolt11)
                val feesMsat = node.bolt11Payment().estimateRoutingFees(invoice)
                val feeSat = feesMsat / 1000u
                Result.success(feeSat)
            } catch (e: Exception) {
                Result.failure(
                    if (e is NodeException) LdkError(e) else e
                )
            }
        }
    }

    suspend fun estimateRoutingFeesForAmount(bolt11: String, amountSats: ULong): Result<ULong> {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        return ServiceQueue.LDK.background {
            return@background try {
                val invoice = Bolt11Invoice.fromStr(bolt11)
                val amountMsat = amountSats * 1000u
                val feesMsat = node.bolt11Payment().estimateRoutingFeesUsingAmount(invoice, amountMsat)
                val feeSat = feesMsat / 1000u
                Result.success(feeSat)
            } catch (e: Exception) {
                Result.failure(
                    if (e is NodeException) LdkError(e) else e
                )
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

    // region fee
    suspend fun calculateCpfpFeeRate(parentTxid: Txid): FeeRate {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Logger.info("Calculating CPFP fee for parentTxid $parentTxid")

        return ServiceQueue.LDK.background {
            return@background try {
                node.onchainPayment().calculateCpfpFeeRate(
                    parentTxid = parentTxid,
                    urgent = true
                )
            } catch (e: NodeException) {
                throw LdkError(e)
            }
        }
    }

    suspend fun calculateTotalFee(
        address: Address,
        amountSats: ULong,
        satsPerVByte: UInt,
        utxosToSpend: List<SpendableUtxo>? = null,
    ): ULong {
        val node = this.node ?: throw ServiceError.NodeNotSetup

        Logger.verbose(
            "Calculating fee for $amountSats sats to $address, UTXOs=${utxosToSpend?.size}, satsPerVByte=$satsPerVByte"
        )

        return ServiceQueue.LDK.background {
            return@background try {
                val fee = node.onchainPayment().calculateTotalFee(
                    address = address,
                    amountSats = amountSats,
                    feeRate = convertVByteToKwu(satsPerVByte),
                    utxosToSpend = utxosToSpend,
                )
                Logger.verbose("Calculated fee=$fee for $amountSats sats to $address, satsPerVByte=$satsPerVByte")
                fee
            } catch (e: NodeException) {
                throw LdkError(e)
            }
        }
    }
    // endregion

    // region events
    private var shouldListenForEvents = true

    suspend fun listenForEvents(onEvent: NodeEventHandler? = null) = withContext(bgDispatcher) {
        while (shouldListenForEvents) {
            val node = this@LightningService.node ?: let {
                Logger.error(ServiceError.NodeNotStarted.message.orEmpty())
                return@withContext
            }
            val event = node.nextEventAsync()
            Logger.debug("LDK-node event fired: ${jsonLogOf(event)}")
            try {
                node.eventHandled()
                Logger.verbose("LDK-node eventHandled: $event")
            } catch (e: NodeException) {
                Logger.verbose("LDK eventHandled error: $event", LdkError(e))
            }
            onEvent?.invoke(event)
        }
    }
    // endregion

    // region transaction details
    suspend fun getTransactionDetails(txid: Txid): TransactionDetails? {
        val node = this.node ?: return null
        return ServiceQueue.LDK.background {
            try {
                node.getTransactionDetails(txid)
            } catch (e: Exception) {
                Logger.error("Error getting transaction details by txid: $txid", e, context = TAG)
                null
            }
        }
    }

    suspend fun getAddressBalance(address: String): ULong {
        val node = this.node ?: throw ServiceError.NodeNotSetup
        return ServiceQueue.LDK.background {
            try {
                node.getAddressBalance(addressStr = address)
            } catch (e: Exception) {
                Logger.error("Error getting address balance for address: $address", e, context = TAG)
                throw e
            }
        }
    }

    // endregion

    // region state
    val nodeId: String? get() = node?.nodeId()
    val balances: BalanceDetails? get() = node?.listBalances()
    val status: NodeStatus? get() = node?.status()
    val config: Config? get() = node?.config()
    val peers: List<PeerDetails>? get() = node?.listPeers()
    val channels: List<ChannelDetails>? get() = node?.listChannels()
    val payments: List<PaymentDetails>? get() = node?.listPayments()
    // endregion

    companion object {
        private const val TAG = "LightningService"
    }
}

// region helpers
/**
 * TODO remove, replace all usages with [FeeRate.fromSatPerVbUnchecked]
 * */
private fun convertVByteToKwu(satsPerVByte: UInt): FeeRate {
    // 1 vbyte = 4 weight units, so 1 sats/vbyte = 250 sats/kwu
    val satPerKwu = satsPerVByte.toULong() * 250u
    // Ensure we're above the minimum relay fee
    return FeeRate.fromSatPerKwu(maxOf(satPerKwu, 253u)) // FEERATE_FLOOR_SATS_PER_KW is 253 in LDK
}
// endregion
