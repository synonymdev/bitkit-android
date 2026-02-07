package to.bitkit.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.AnchorChannelsConfig
import org.lightningdevkit.ldknode.BackgroundSyncConfig
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Bolt11InvoiceDescription
import org.lightningdevkit.ldknode.BuildException
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.ChannelConfig
import org.lightningdevkit.ldknode.ChannelDataMigration
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.time.Duration

typealias NodeEventHandler = suspend (Event) -> Unit

@Suppress("LargeClass", "TooManyFunctions")
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

    private var trustedPeers: List<PeerDetails> = emptyList()

    private var listenerJob: Job? = null

    suspend fun setup(
        walletIndex: Int,
        customServerUrl: String? = null,
        customRgsServerUrl: String? = null,
        trustedPeers: List<PeerDetails>? = null,
        channelMigration: ChannelDataMigration? = null,
    ) {
        Logger.debug("Building node…", context = TAG)

        val config = config(walletIndex, trustedPeers)
        node = build(
            walletIndex,
            customServerUrl,
            customRgsServerUrl,
            config,
            channelMigration,
        )

        Logger.info("LDK node setup", context = TAG)
    }

    private fun config(
        walletIndex: Int,
        trustedPeers: List<PeerDetails>?,
    ): Config {
        val dirPath = Env.ldkStoragePath(walletIndex)

        this.trustedPeers = trustedPeers?.takeIf { it.isNotEmpty() } ?: Env.trustedLnPeers.also {
            Logger.warn("Missing trusted peers from LSP, falling back to preconfigured env peers", context = TAG)
        }

        val trustedPeerNodeIds = this.trustedPeers.map { it.nodeId }

        return defaultConfig().copy(
            storageDirPath = dirPath,
            network = Env.network,
            trustedPeers0conf = trustedPeerNodeIds,
            anchorChannelsConfig = AnchorChannelsConfig(
                trustedPeersNoReserve = trustedPeerNodeIds,
                perChannelReserveSats = 1u,
            ),
            includeUntrustedPendingInSpendable = true,
        )
    }

    @Suppress("ForbiddenComment")
    private suspend fun build(
        walletIndex: Int,
        customServerUrl: String?,
        customRgsServerUrl: String?,
        config: Config,
        channelMigration: ChannelDataMigration? = null,
    ): Node = ServiceQueue.LDK.background {
        val builder = Builder.fromConfig(config).apply {
            setCustomLogger(LdkLogWriter())
            configureChainSource(customServerUrl)
            configureGossipSource(customRgsServerUrl)

            if (channelMigration != null) {
                setChannelDataMigration(channelMigration)
                Logger.info(
                    "Applied channel migration: ${channelMigration.channelMonitors.size} monitors",
                    context = "Migration"
                )
            }

            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                ?: throw ServiceError.MnemonicNotFound()
            setEntropyBip39Mnemonic(
                mnemonic = mnemonic,
                passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name),
            )
        }
        try {
            val vssStoreId = vssStoreIdProvider.getVssStoreId(walletIndex)
            val vssUrl = Env.vssServerUrl
            val lnurlAuthServerUrl = Env.lnurlAuthServerUrl
            val fixedHeaders = emptyMap<String, String>()
            Logger.verbose(
                "Building node with \n\t vssUrl: '$vssUrl'\n\t lnurlAuthServerUrl: '$lnurlAuthServerUrl'",
                context = TAG,
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
            Logger.info("Using gossip source: RGS server '$rgsServerUrl'", context = TAG)
            setGossipSourceRgs(rgsServerUrl)
        } else {
            Logger.info("Using gossip source: P2P", context = TAG)
            setGossipSourceP2p()
        }
    }

    private suspend fun Builder.configureChainSource(customServerUrl: String? = null) {
        val serverUrl = customServerUrl ?: settingsStore.data.first().electrumServer
        Logger.info("Using onchain source Electrum Sever url: $serverUrl", context = TAG)
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
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        Logger.debug("Starting node…", context = TAG)

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
            listenerJob = launch {
                runCatching {
                    Logger.debug("LDK event listener started", context = TAG)
                    if (timeout != null) {
                        withTimeout(timeout) { listenForEvents(node, eventHandler) }
                    } else {
                        listenForEvents(node, eventHandler)
                    }
                }.onFailure {
                    if (it !is CancellationException) {
                        Logger.error("LDK event listener error", it, context = TAG)
                    }
                }
            }
        }

        Logger.info("Node started", context = TAG)
    }

    suspend fun stop() {
        shouldListenForEvents = false
        listenerJob?.cancelAndJoin()
        listenerJob = null

        val node = this.node ?: run {
            Logger.debug("Node already stopped", context = TAG)
            return
        }

        Logger.debug("Stopping node…", context = TAG)
        ServiceQueue.LDK.background {
            runCatching { node.stop() }
                .onFailure { if (it !is NodeException.NotRunning) throw it }
            this@LightningService.node = null
        }
        Logger.info("Node stopped", context = TAG)
    }

    fun wipeStorage(walletIndex: Int) {
        if (node != null) throw ServiceError.NodeStillRunning()
        Logger.warn("Wiping LDK storage…", context = TAG)
        Path(Env.ldkStoragePath(walletIndex)).toFile().deleteRecursively()
        Logger.info("LDK storage wiped", context = TAG)
    }

    suspend fun sync() {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        Logger.verbose("Syncing LDK…", context = TAG)
        ServiceQueue.LDK.background {
            node.syncWallets()
        }

        _syncStatusChanged.tryEmit(Unit)

        Logger.debug("LDK synced", context = TAG)
    }

    suspend fun sign(message: String): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup()
        val msg = runCatching { message.uByteList }.getOrNull() ?: throw ServiceError.InvalidNodeSigningMessage()

        return ServiceQueue.LDK.background {
            node.signMessage(msg)
        }
    }

    suspend fun newAddress(): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            node.onchainPayment().newAddress()
        }
    }

    // region peers
    suspend fun connectToTrustedPeers() {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        ServiceQueue.LDK.background {
            for (peer in trustedPeers) {
                try {
                    node.connect(peer.nodeId, peer.address, persist = true)
                    Logger.info("Connected to trusted peer: $peer", context = TAG)
                } catch (e: NodeException) {
                    Logger.error("Peer connect error: $peer", LdkError(e), context = TAG)
                }
            }

            verifyTrustedPeersOrFallback(node)
        }
    }

    private fun verifyTrustedPeersOrFallback(node: Node) {
        val connectedPeerIds = node.listPeers().map { it.nodeId }.toSet()
        val trustedConnected = trustedPeers.count { it.nodeId in connectedPeerIds }

        if (trustedConnected == 0 && trustedPeers.isNotEmpty()) {
            Logger.warn("No trusted peers connected, falling back to preconfigured env peers", context = TAG)
            for (peer in Env.trustedLnPeers) {
                try {
                    node.connect(peer.nodeId, peer.address, persist = true)
                    Logger.info("Connected to fallback peer: $peer", context = TAG)
                } catch (e: NodeException) {
                    Logger.error("Fallback peer connect error: $peer", LdkError(e), context = TAG)
                }
            }
        } else {
            Logger.info("Connected to $trustedConnected/${trustedPeers.size} trusted peers", context = TAG)
        }
    }

    suspend fun connectPeer(peer: PeerDetails): Result<Unit> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()
        val uri = peer.uri

        return ServiceQueue.LDK.background {
            try {
                Logger.debug("Connecting peer: $uri", context = TAG)
                node.connect(peer.nodeId, peer.address, persist = true)
                Logger.info("Peer connected: $uri", context = TAG)
                Result.success(Unit)
            } catch (e: NodeException) {
                val error = LdkError(e)
                Logger.error("Peer connect error: $uri", error, context = TAG)
                Result.failure(error)
            }
        }
    }

    suspend fun disconnectPeer(peer: PeerDetails): Result<Unit> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()
        val uri = peer.uri

        return ServiceQueue.LDK.background {
            try {
                Logger.debug("Disconnecting peer: $uri", context = TAG)
                node.disconnect(peer.nodeId)
                Logger.info("Peer disconnected: $uri", context = TAG)
                Result.success(Unit)
            } catch (e: NodeException) {
                Logger.warn("Peer disconnect error: $uri", LdkError(e), context = TAG)
                Result.failure(e)
            }
        }
    }

    fun getLspPeerNodeIds(): Set<String> = trustedPeers.map { it.nodeId }.toSet()

    fun separateTrustedChannels(channels: List<ChannelDetails>): Pair<List<ChannelDetails>, List<ChannelDetails>> {
        val trustedPeerIds = getLspPeerNodeIds()
        val trusted = channels.filter { it.counterpartyNodeId in trustedPeerIds }
        val nonTrusted = channels.filter { it.counterpartyNodeId !in trustedPeerIds }
        return trusted to nonTrusted
    }

    fun isTrustedPeer(nodeId: String): Boolean = nodeId in getLspPeerNodeIds()

    // endregion

    // region channels
    suspend fun openChannel(
        peer: PeerDetails,
        channelAmountSats: ULong,
        pushToCounterpartySats: ULong? = null,
        channelConfig: ChannelConfig? = null,
    ): Result<OpenChannelResult> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            try {
                val pushToCounterpartyMsat = pushToCounterpartySats?.let { it * 1000u }
                Logger.debug("Initiating channel open (sats: '$channelAmountSats') with: '${peer.uri}'", context = TAG)

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

                Logger.info("Channel open initiated, result: $result", context = TAG)

                Result.success(result)
            } catch (e: NodeException) {
                val error = LdkError(e)
                Logger.error("Error initiating channel open", error, context = TAG)
                Result.failure(error)
            }
        }
    }

    @Suppress("ThrowsCount")
    suspend fun closeChannel(
        channel: ChannelDetails,
        force: Boolean = false,
        forceCloseReason: String? = null,
    ) {
        val node = this.node ?: throw ServiceError.NodeNotStarted()
        val channelId = channel.channelId
        val userChannelId = channel.userChannelId
        val counterpartyNodeId = channel.counterpartyNodeId

        // Prevent force closing channels with trusted peers (LSP nodes)
        if (force && isTrustedPeer(counterpartyNodeId)) {
            throw TrustedPeerForceCloseException()
        }

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
            Logger.warn("canReceive = false: Channels not available", context = TAG)
            return false
        }

        if (channels.none { it.isChannelReady }) {
            Logger.warn("canReceive = false: Found no LN channel ready to enable receive: '$channels'", context = TAG)
            return false
        }

        return true
    }

    suspend fun receive(sat: ULong? = null, description: String, expirySecs: UInt = 3600u): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        val message = description

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
            Logger.warn("Channels not available", context = TAG)
            return false
        }

        val totalNextOutboundHtlcLimitSats = channels.totalNextOutboundHtlcLimitSats()

        if (totalNextOutboundHtlcLimitSats < amountSats) {
            Logger.warn("Insufficient outbound capacity: $totalNextOutboundHtlcLimitSats < $amountSats", context = TAG)
            return false
        }

        return true
    }

    suspend fun send(
        address: Address,
        sats: ULong,
        satsPerVByte: ULong,
        utxosToSpend: List<SpendableUtxo>? = null,
        isMaxAmount: Boolean = false,
    ): Txid {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        Logger.info(
            "Sending $sats sats to $address, satsPerVByte=$satsPerVByte, isMaxAmount = $isMaxAmount",
            context = TAG,
        )

        return ServiceQueue.LDK.background {
            if (isMaxAmount) {
                node.onchainPayment().sendAllToAddress(
                    address = address,
                    retainReserve = true,
                    feeRate = FeeRate.fromSatPerVbUnchecked(satsPerVByte),
                )
            } else {
                node.onchainPayment().sendToAddress(
                    address = address,
                    amountSats = sats,
                    feeRate = FeeRate.fromSatPerVbUnchecked(satsPerVByte),
                    utxosToSpend = utxosToSpend,
                )
            }
        }
    }

    suspend fun send(bolt11: String, sats: ULong? = null): PaymentId {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        Logger.debug("Paying bolt11: $bolt11", context = TAG)

        val bolt11Invoice = runCatching { Bolt11Invoice.fromStr(bolt11) }
            .getOrElse { throw LdkError(it as NodeException) }

        return ServiceQueue.LDK.background {
            runCatching {
                when (sats != null) {
                    true -> node.bolt11Payment().sendUsingAmount(bolt11Invoice, sats * 1000u, null)
                    else -> node.bolt11Payment().send(bolt11Invoice, null)
                }
            }
        }.onFailure {
            dumpNetworkGraphInfo(bolt11)
        }.getOrThrow()
    }

    suspend fun estimateRoutingFees(bolt11: String): Result<ULong> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            return@background runCatching {
                val invoice = Bolt11Invoice.fromStr(bolt11)
                val feesMsat = node.bolt11Payment().estimateRoutingFees(invoice)
                val feeSat = feesMsat / 1000u
                Result.success(feeSat)
            }.getOrElse {
                Result.failure(if (it is NodeException) LdkError(it) else it)
            }
        }
    }

    suspend fun estimateRoutingFeesForAmount(bolt11: String, amountSats: ULong): Result<ULong> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            return@background runCatching {
                val invoice = Bolt11Invoice.fromStr(bolt11)
                val amountMsat = amountSats * 1000u
                val feesMsat = node.bolt11Payment().estimateRoutingFeesUsingAmount(invoice, amountMsat)
                val feeSat = feesMsat / 1000u
                Result.success(feeSat)
            }.getOrElse {
                Result.failure(if (it is NodeException) LdkError(it) else it)
            }
        }
    }
    // endregion

    // region probing
    suspend fun sendProbes(invoice: Bolt11Invoice): Result<Unit> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            runCatching {
                node.bolt11Payment().sendProbes(invoice, null)
                Result.success(Unit)
            }.getOrElse {
                Result.failure(if (it is NodeException) LdkError(it) else it)
            }
        }
    }

    suspend fun sendProbesUsingAmount(invoice: Bolt11Invoice, amountMsat: ULong): Result<Unit> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            runCatching {
                node.bolt11Payment().sendProbesUsingAmount(invoice, amountMsat, null)
                Result.success(Unit)
            }.getOrElse {
                Result.failure(if (it is NodeException) LdkError(it) else it)
            }
        }
    }
    // endregion

    // region utxo selection
    suspend fun listSpendableOutputs(): Result<List<SpendableUtxo>> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            return@background runCatching {
                val result = node.onchainPayment().listSpendableOutputs()
                Result.success(result)
            }.getOrElse {
                Result.failure(if (it is NodeException) LdkError(it) else it)
            }
        }
    }

    suspend fun selectUtxosWithAlgorithm(
        targetAmountSats: ULong,
        satsPerVByte: ULong,
        algorithm: CoinSelectionAlgorithm,
        utxos: List<SpendableUtxo>?,
    ): Result<List<SpendableUtxo>> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            runCatching {
                val result = node.onchainPayment().selectUtxosWithAlgorithm(
                    targetAmountSats = targetAmountSats,
                    feeRate = FeeRate.fromSatPerVbUnchecked(satsPerVByte),
                    algorithm = algorithm,
                    utxos = utxos,
                )
                Result.success(result)
            }.getOrElse {
                Result.failure(if (it is NodeException) LdkError(it) else it)
            }
        }
    }
    // endregion

    // region boost
    suspend fun bumpFeeByRbf(txid: Txid, satsPerVByte: ULong): Txid {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        Logger.info("RBF for txid='$txid' using satsPerVByte='$satsPerVByte'", context = TAG)

        return ServiceQueue.LDK.background {
            return@background try {
                node.onchainPayment().bumpFeeByRbf(
                    txid = txid,
                    feeRate = FeeRate.fromSatPerVbUnchecked(satsPerVByte),
                )
            } catch (e: NodeException) {
                throw LdkError(e)
            }
        }
    }

    suspend fun accelerateByCpfp(
        txid: Txid,
        satsPerVByte: ULong,
        toAddress: Address,
    ): Txid {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        Logger.info("CPFP for txid='$txid' using satsPerVByte='$satsPerVByte', to address='$toAddress'", context = TAG)

        return ServiceQueue.LDK.background {
            return@background try {
                node.onchainPayment().accelerateByCpfp(
                    txid = txid,
                    feeRate = FeeRate.fromSatPerVbUnchecked(satsPerVByte),
                    destinationAddress = toAddress,
                )
            } catch (e: NodeException) {
                throw LdkError(e)
            }
        }
    }
    // endregion

    // region fee
    suspend fun calculateCpfpFeeRate(parentTxid: Txid): FeeRate {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        Logger.debug("Calculating CPFP fee for parentTxid $parentTxid", context = TAG)

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
        satsPerVByte: ULong,
        utxosToSpend: List<SpendableUtxo>? = null,
    ): ULong {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        Logger.verbose(
            "Calculating fee for $amountSats sats to $address, ${utxosToSpend?.size} UTXOs, satsPerVByte=$satsPerVByte",
            context = TAG,
        )

        return ServiceQueue.LDK.background {
            return@background try {
                val fee = node.onchainPayment().calculateTotalFee(
                    address = address,
                    amountSats = amountSats,
                    feeRate = FeeRate.fromSatPerVbUnchecked(satsPerVByte),
                    utxosToSpend = utxosToSpend,
                )
                Logger.debug(
                    "Calculated fee='$fee' for $amountSats sats to $address, satsPerVByte=$satsPerVByte",
                    context = TAG,
                )
                fee
            } catch (e: NodeException) {
                throw LdkError(e)
            }
        }
    }
    // endregion

    // region events
    private var shouldListenForEvents = true

    fun startEventListener(onEvent: NodeEventHandler? = null): Result<Unit> = runCatching {
        val node = this.node ?: throw ServiceError.NodeNotSetup()
        shouldListenForEvents = true
        listenerJob = launch {
            runCatching {
                Logger.debug("LDK event listener started", context = TAG)
                listenForEvents(node, onEvent)
            }.onFailure {
                if (it !is CancellationException) {
                    Logger.error("LDK event listener error", it, context = TAG)
                }
            }
        }
    }

    private suspend fun listenForEvents(node: Node, onEvent: NodeEventHandler? = null) = withContext(bgDispatcher) {
        while (shouldListenForEvents) {
            ensureActive()

            val event = runCatching { node.nextEventAsync() }.getOrElse {
                Logger.warn("Event listener stopping: node stopped", it, context = TAG)
                return@withContext
            }

            Logger.debug("LDK event fired: ${jsonLogOf(event)}", context = TAG)
            runCatching { node.eventHandled() }
                .onSuccess { Logger.verbose("LDK eventHandled: '$event'", context = TAG) }
                .onFailure { Logger.verbose("LDK eventHandled error: '$event'", it, context = TAG) }
            onEvent?.invoke(event)
        }
    }
    // endregion

    suspend fun getAddressBalance(address: String): ULong {
        val node = this.node ?: throw ServiceError.NodeNotSetup()
        return ServiceQueue.LDK.background {
            runCatching {
                node.getAddressBalance(addressStr = address)
            }.onFailure {
                Logger.error("Error getting address balance for address: '$address'", it, context = TAG)
            }.getOrThrow()
        }
    }

    // region state
    val nodeId: String? get() = node?.nodeId()
    val balances: BalanceDetails? get() = node?.listBalances()
    val status: NodeStatus? get() = node?.status()
    val config: Config? get() = node?.config()
    val peers: List<PeerDetails>? get() = node?.listPeers()
    val channels: List<ChannelDetails>? get() = node?.listChannels()
    val payments: List<PaymentDetails>? get() = node?.listPayments()
    // endregion

    // region debug
    @Suppress("LongMethod")
    fun dumpNetworkGraphInfo(bolt11: String) {
        val node = this.node ?: run {
            Logger.error("Node not available for network graph dump", context = TAG)
            return
        }
        val nodeIdPreviewLength = 20

        val sb = StringBuilder()
        sb.appendLine("\n\n=== ROUTE NOT FOUND - NETWORK GRAPH DUMP ===\n")

        // 1. Invoice Info
        runCatching {
            val invoice = Bolt11Invoice.fromStr(bolt11)
            sb.appendLine("Invoice Info:")
            sb.appendLine("  - Payment Hash: ${invoice.paymentHash()}")
            sb.appendLine("  - Invoice: $bolt11")
        }.getOrElse {
            sb.appendLine("Failed to parse bolt11 invoice: $it")
        }

        // 2. Our Node Info
        sb.appendLine("\nOur Node Info:")
        sb.appendLine("  - Node ID: ${node.nodeId()}")

        // 3. Our Channels
        sb.appendLine("\nOur Channels:")
        val channels = node.listChannels()
        sb.appendLine("  Total channels: ${channels.size}")

        var totalOutboundMsat = 0UL
        var totalInboundMsat = 0UL
        var usableChannels = 0
        var announcedChannels = 0

        channels.forEachIndexed { index, channel ->
            totalOutboundMsat += channel.outboundCapacityMsat
            totalInboundMsat += channel.inboundCapacityMsat
            if (channel.isUsable) usableChannels++
            if (channel.isAnnounced) announcedChannels++

            sb.appendLine("  Channel ${index + 1}:")
            sb.appendLine("    - Channel ID: ${channel.channelId}")
            sb.appendLine("    - Counterparty: ${channel.counterpartyNodeId}")
            sb.appendLine(
                "    - Ready: ${channel.isChannelReady}, Usable: ${channel.isUsable}, " +
                    "Announced: ${channel.isAnnounced}"
            )
            sb.appendLine(
                "    - Outbound: ${channel.outboundCapacityMsat} msat, " +
                    "Inbound: ${channel.inboundCapacityMsat} msat"
            )
        }

        sb.appendLine("\n  Channel Summary:")
        sb.appendLine("    - Usable channels: $usableChannels/${channels.size}")
        sb.appendLine("    - Announced channels: $announcedChannels/${channels.size}")
        sb.appendLine("    - Total Outbound: $totalOutboundMsat msat")
        sb.appendLine("    - Total Inbound: $totalInboundMsat msat")

        // 4. Our Peers
        sb.appendLine("\nOur Peers:")
        val peers = node.listPeers()
        sb.appendLine("  Total peers: ${peers.size}")

        peers.forEachIndexed { index, peer ->
            sb.appendLine("  Peer ${index + 1}: ${peer.nodeId.take(nodeIdPreviewLength)}... @ ${peer.address}")
            sb.appendLine("    - Connected: ${peer.isConnected}, Persisted: ${peer.isPersisted}")
        }

        // 5. RGS Configuration
        sb.appendLine("\nRGS Configuration:")
        sb.appendLine("  - RGS Server URL: ${Env.ldkRgsServerUrl ?: "Not configured"}")

        val nodeStatus = node.status()
        nodeStatus.latestRgsSnapshotTimestamp?.let { rgsTimestamp ->
            val date = java.util.Date(rgsTimestamp.toLong() * 1000)
            val timeAgoMs = System.currentTimeMillis() - date.time
            val hoursAgo = (timeAgoMs / 3600000).toInt()
            val minutesAgo = ((timeAgoMs % 3600000) / 60000).toInt()

            sb.appendLine("  - Last RGS Snapshot: $date")
            if (hoursAgo > 0) {
                sb.appendLine("  - Time since update: ${hoursAgo}h ${minutesAgo}m ago")
            } else {
                sb.appendLine("  - Time since update: ${minutesAgo}m ago")
            }
            sb.appendLine("  - Timestamp: $rgsTimestamp")
        } ?: run {
            sb.appendLine("  - Last RGS Snapshot: Never synced")
            sb.appendLine("  - WARNING: Network graph may be empty or stale!")
        }

        // 6. Network Graph Data
        sb.appendLine("\nRGS Network Graph Data:")
        val networkGraph = node.networkGraph()
        val allNodes = networkGraph.listNodes()
        val allChannels = networkGraph.listChannels()

        sb.appendLine("  Total nodes: ${allNodes.size}")
        sb.appendLine("  Total channels: ${allChannels.size}")

        // Check for trusted peers in graph
        sb.appendLine("\n  Checking for trusted peers in network graph:")
        var foundTrustedNodes = 0
        trustedPeers.forEach { peer ->
            val nodeId = peer.nodeId
            if (allNodes.any { it == nodeId }) {
                foundTrustedNodes++
                sb.appendLine("    OK: ${nodeId.take(nodeIdPreviewLength)}... found in graph")
            } else {
                sb.appendLine("    MISSING: ${nodeId.take(nodeIdPreviewLength)}... NOT in graph")
            }
        }
        sb.appendLine("  Summary: $foundTrustedNodes/${trustedPeers.size} trusted peers found in graph")

        // Show first 10 nodes
        val nodesToShow = minOf(10, allNodes.size)
        sb.appendLine("\n  First $nodesToShow nodes:")
        allNodes.take(nodesToShow).forEachIndexed { index, nodeId ->
            sb.appendLine("    ${index + 1}. $nodeId")
        }
        if (allNodes.size > nodesToShow) {
            sb.appendLine("    ... and ${allNodes.size - nodesToShow} more nodes")
        }

        sb.appendLine("\n=== END NETWORK GRAPH DUMP ===\n")

        Logger.info(sb.toString(), context = TAG)
    }

    fun getNetworkGraphInfo(): NetworkGraphInfo? {
        val node = this.node ?: return null

        return runCatching {
            val graph = node.networkGraph()
            NetworkGraphInfo(
                nodeCount = graph.listNodes().size,
                channelCount = graph.listChannels().size,
                latestRgsSyncTimestamp = node.status().latestRgsSnapshotTimestamp,
            )
        }.onFailure {
            Logger.error("Failed to get network graph info", it, context = TAG)
        }.getOrNull()
    }

    suspend fun exportNetworkGraphToFile(outputDir: String): Result<File> {
        val node = this.node ?: return Result.failure(ServiceError.NodeNotSetup())

        return withContext(bgDispatcher) {
            runCatching {
                val graph = node.networkGraph()
                val nodes = graph.listNodes()

                val outputFile = File(outputDir, "network_graph_nodes.txt")
                outputFile.bufferedWriter().use { writer ->
                    writer.write("Network Graph Nodes Export\n")
                    writer.write("Total nodes: ${nodes.size}\n")
                    writer.write("Exported at: ${System.currentTimeMillis()}\n")
                    writer.write("---\n")
                    nodes.forEachIndexed { index, nodeId ->
                        writer.write("${index + 1}. $nodeId\n")
                    }
                }

                Logger.info("Exported ${nodes.size} nodes to ${outputFile.absolutePath}", context = TAG)
                outputFile
            }.onFailure {
                Logger.error("Failed to export network graph to file", it, context = TAG)
            }
        }
    }
    // endregion

    companion object {
        private const val TAG = "LightningService"
    }
}

@Serializable
data class NetworkGraphInfo(
    val nodeCount: Int,
    val channelCount: Int,
    val latestRgsSyncTimestamp: ULong?,
)

class TrustedPeerForceCloseException : Exception(
    "Cannot force close channel with trusted peer. Force close is disabled for Blocktank LSP channels."
)
