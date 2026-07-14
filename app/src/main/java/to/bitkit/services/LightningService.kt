package to.bitkit.services

import com.synonym.bitkitcore.AddressType
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
import org.lightningdevkit.ldknode.AddressTypeBalance
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
import org.lightningdevkit.ldknode.KeychainKind
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.PeerDetails
import org.lightningdevkit.ldknode.PublicKey
import org.lightningdevkit.ldknode.ScoringFeeParameters
import org.lightningdevkit.ldknode.SpendableUtxo
import org.lightningdevkit.ldknode.Txid
import org.lightningdevkit.ldknode.defaultConfig
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.data.SettingsStore
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Defaults
import to.bitkit.env.Env
import to.bitkit.ext.uByteList
import to.bitkit.ext.uri
import to.bitkit.models.OpenChannelResult
import to.bitkit.models.msatFloorOf
import to.bitkit.models.toAddressType
import to.bitkit.utils.AppError
import to.bitkit.utils.LdkError
import to.bitkit.utils.LdkLogWriter
import to.bitkit.utils.Logger
import to.bitkit.utils.LoggerLdk
import to.bitkit.utils.ServiceError
import to.bitkit.utils.jsonLogOf
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.time.Duration
import org.lightningdevkit.ldknode.AddressType as LdkAddressType

typealias NodeEventHandler = suspend (Event) -> Unit

data class AddressDerivationInfo(
    val address: String,
    val index: Int,
)

@Suppress("LargeClass", "TooManyFunctions")
@Singleton
class LightningService @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val vssStoreIdProvider: VssStoreIdProvider,
    private val settingsStore: SettingsStore,
    private val loggerLdk: LoggerLdk,
) : BaseCoroutineScope(bgDispatcher) {

    companion object {
        private const val TAG = "LightningService"
        private const val NODE_ID_PREVIEW_LEN = 20
        private const val SCORING_BASE_PENALTY_MSAT = 50_000uL
        private const val SCORING_LIQUIDITY_PENALTY_MULTIPLIER_MSAT = 10_000uL
        private const val SCORING_LIQUIDITY_PENALTY_AMOUNT_MULTIPLIER_MSAT = 10_000uL
        private const val SCORING_HISTORICAL_LIQUIDITY_PENALTY_AMOUNT_MULTIPLIER_MSAT = 20_000uL
        private const val SCORING_CONSIDERED_IMPOSSIBLE_PENALTY_MSAT = 1_000_000_000_000uL
        private const val SCORING_PROBING_DIVERSITY_PENALTY_MSAT = 60_000uL

        private val DEFAULT_SCORING_FEE_PARAMETERS = ScoringFeeParameters(
            basePenaltyMsat = 1_024uL,
            basePenaltyAmountMultiplierMsat = 131_072uL,
            liquidityPenaltyMultiplierMsat = 0uL,
            liquidityPenaltyAmountMultiplierMsat = 0uL,
            historicalLiquidityPenaltyMultiplierMsat = 10_000uL,
            historicalLiquidityPenaltyAmountMultiplierMsat = 1_250uL,
            antiProbingPenaltyMsat = 250uL,
            consideredImpossiblePenaltyMsat = 100_000_000_000uL,
            linearSuccessProbability = false,
            probingDiversityPenaltyMsat = 0uL,
        )
    }

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
            probingLiquidityLimitMultiplier = 1uL,
            includeUntrustedPendingInSpendable = true,
        )
    }

    @Suppress("ForbiddenComment", "LongMethod")
    private suspend fun build(
        walletIndex: Int,
        customServerUrl: String?,
        customRgsServerUrl: String?,
        config: Config,
        channelMigration: ChannelDataMigration? = null,
    ): Node = ServiceQueue.LDK.background {
        val settings = settingsStore.data.first()
        val selectedType = settings.selectedAddressType.toAddressType()?.toLdkAddressType()
            ?: LdkAddressType.NATIVE_SEGWIT
        val monitoredTypes = settings.addressTypesToMonitor
            .mapNotNull { it.toAddressType() }
            .filter { it.toLdkAddressType() != selectedType }
            .map { it.toLdkAddressType() }

        val builder = Builder.fromConfig(config).apply {
            setCustomLogger(LdkLogWriter())
            configureChainSource(customServerUrl)
            configureGossipSource(customRgsServerUrl)
            configureScorerSource()
            setScoringFeeParams(scorerFeeParameters())
            setAddressType(selectedType)
            setAddressTypesToMonitor(monitoredTypes)

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

            fun buildNode() = runCatching {
                if (lnurlAuthServerUrl.isNotEmpty()) {
                    builder.buildWithVssStore(vssUrl, vssStoreId, lnurlAuthServerUrl, fixedHeaders)
                } else {
                    builder.buildWithVssStoreAndFixedHeaders(vssUrl, vssStoreId, fixedHeaders)
                }
            }

            buildNode().recoverCatching { error ->
                if (error !is BuildException.DangerousValue) throw error
                Logger.warn(
                    "Retrying build failed with 'DangerousValue' using 'setAcceptStaleChannelMonitors' for recovery.",
                    error,
                    context = TAG,
                )
                builder.setAcceptStaleChannelMonitors(true)
                buildNode()
                    .onFailure {
                        Logger.error("Failed recovery retry using 'setAcceptStaleChannelMonitors'.", it, context = TAG)
                    }
                    .getOrThrow()
            }.getOrThrow()
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

    private fun Builder.configureScorerSource() {
        val scorerUrl = Env.ldkScorerUrl ?: return
        Logger.info("Using pathfinding scores source: '$scorerUrl'", context = TAG)
        setPathfindingScoresSource(scorerUrl)
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
                connectionTimeoutSecs = Env.walletSyncTimeoutSecs,
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

    fun resetNetworkGraph(walletIndex: Int) {
        if (node != null) throw ServiceError.NodeStillRunning()
        Logger.warn("Resetting network graph cache…", context = TAG)
        val ldkPath = Path(Env.ldkStoragePath(walletIndex)).toFile()
        val graphFile = ldkPath.resolve("network_graph_cache")
        if (graphFile.exists()) {
            graphFile.delete()
            Logger.info("Network graph cache deleted", context = TAG)
        } else {
            Logger.info("No network graph cache found", context = TAG)
        }
    }

    @Suppress("ReturnCount")
    fun aresRequiredPeersInNetworkGraph(): Boolean {
        val node = this.node ?: return true
        val graph = node.networkGraph()

        val nodes = graph.listNodes().toSet()
        if (nodes.isEmpty()) {
            val rgsTimestamp = node.status().latestRgsSnapshotTimestamp
            if (rgsTimestamp != null) {
                Logger.warn("Network graph is empty despite RGS timestamp $rgsTimestamp", context = TAG)
                return false
            }
            Logger.debug("Network graph is empty, skipping validation", context = TAG)
            return true
        }

        // reset graph if missing trusted peers
        val missing = trustedPeers.filter { it.nodeId !in nodes }
        if (missing.size == trustedPeers.size) {
            val rgsTimestamp = node.status().latestRgsSnapshotTimestamp
            val missingIds = missing.joinToString { it.nodeId.take(NODE_ID_PREVIEW_LEN) }
            Logger.warn(
                "Network graph missing all ${trustedPeers.size} trusted peers: [$missingIds] " +
                    "(graphNodes=${nodes.size}, channels=${graph.listChannels().size}, " +
                    "rgsTimestamp=$rgsTimestamp)",
                context = TAG,
            )
            return false
        }

        // reset graph if missing channel counterparty
        val channels = node.listChannels().filter { it.isUsable }
        val missingCounterparties = channels.filter { it.counterpartyNodeId !in nodes }
        if (missingCounterparties.isNotEmpty()) {
            val ids = missingCounterparties.joinToString { it.counterpartyNodeId.take(NODE_ID_PREVIEW_LEN) }
            Logger.warn(
                "Network graph missing ${missingCounterparties.size} active channel counterparties: [$ids] " +
                    "(graphNodes=${nodes.size}, channels=${graph.listChannels().size})",
                context = TAG,
            )
            return false
        }

        if (missing.isNotEmpty()) {
            val ids = missing.joinToString { it.nodeId }
            Logger.debug(
                "Network graph missing ${missing.size}/${trustedPeers.size} trusted peers: [$ids]",
                context = TAG,
            )
        }

        val total = trustedPeers.size
        val count = total - missing.size
        Logger.debug(
            "Network graph validated: $count/$total trusted peers present",
            context = TAG,
        )
        return true
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

    suspend fun newAddressForType(addressType: AddressType): String {
        val addressInfo = newAddressInfoForType(addressType)
        return addressInfo.address
    }

    suspend fun newAddressInfoForType(addressType: AddressType): AddressDerivationInfo {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            val addressInfo = node.onchainPayment().newAddressInfoForType(addressType.toLdkAddressType())
            AddressDerivationInfo(address = addressInfo.address, index = addressInfo.index.toInt())
        }
    }

    suspend fun addressInfoForType(addressType: AddressType, receiveIndex: Int): AddressDerivationInfo {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            val addressInfo = node.onchainPayment().addressInfoForTypeAtIndex(
                addressType.toLdkAddressType(),
                KeychainKind.EXTERNAL,
                receiveIndex.toUInt(),
            )
            AddressDerivationInfo(address = addressInfo.address, index = addressInfo.index.toInt())
        }
    }

    suspend fun addressInfosForType(
        addressType: AddressType,
        isChange: Boolean,
        startIndex: Int,
        count: Int,
    ): List<AddressDerivationInfo> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()
        val keychain = if (isChange) KeychainKind.INTERNAL else KeychainKind.EXTERNAL

        return ServiceQueue.LDK.background {
            node.onchainPayment()
                .addressInfosForType(
                    addressType.toLdkAddressType(),
                    keychain,
                    startIndex.toUInt(),
                    count.toUInt(),
                )
                .map { AddressDerivationInfo(address = it.address, index = it.index.toInt()) }
        }
    }

    suspend fun revealReceiveAddresses(toReceiveIndex: Int, forType: AddressType) {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        ServiceQueue.LDK.background {
            node.onchainPayment().revealReceiveAddressesTo(forType.toLdkAddressType(), toReceiveIndex.toUInt())
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
            Logger.error("Failed to initiate channel close for '$channelId' with force '$force'", error, context = TAG)
            logCloseChannelPeerState(node, channel)
            throw LdkError(e)
        }
    }

    private fun logCloseChannelPeerState(node: Node, channel: ChannelDetails) {
        runCatching {
            val peer = node.listPeers().firstOrNull { it.nodeId == channel.counterpartyNodeId }
            Logger.info(
                "Collected close peer state for channel '${channel.channelId}': " +
                    "counterparty='${channel.counterpartyNodeId}', " +
                    "peerFound='${peer != null}', " +
                    "peerAddress='${peer?.address}', " +
                    "peerConnected='${peer?.isConnected}', " +
                    "peerPersisted='${peer?.isPersisted}'",
                context = TAG,
            )
        }.onFailure {
            Logger.warn("Failed to collect close peer state for channel '${channel.channelId}'", it, context = TAG)
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

        if (channels.none { it.isUsable }) {
            Logger.warn("canReceive = false: Found no LN channel usable to enable receive: '$channels'", context = TAG)
            return false
        }

        return true
    }

    suspend fun receive(
        sat: ULong? = null,
        description: String,
        expirySecs: UInt = Defaults.bolt11ExpirySec,
    ): String {
        return receiveMsats(amountMsat = sat?.let { it * 1000u }, description = description, expirySecs = expirySecs)
    }

    suspend fun receiveMsats(
        amountMsat: ULong? = null,
        description: String,
        expirySecs: UInt = Defaults.bolt11ExpirySec,
    ): String {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        val message = description

        return ServiceQueue.LDK.background {
            val bolt11Invoice: Bolt11Invoice = if (amountMsat != null) {
                node.bolt11Payment()
                    .receive(
                        amountMsat = amountMsat,
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
            loggerLdk.dumpNetworkGraphInfo(node, trustedPeers, bolt11)
        }.getOrThrow()
    }

    suspend fun estimateRoutingFees(bolt11: String): Result<ULong> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            return@background runCatching {
                val invoice = Bolt11Invoice.fromStr(bolt11)
                val feesMsat = node.bolt11Payment().estimateRoutingFees(invoice)
                val feeSat = msatFloorOf(feesMsat)
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
                val feeSat = msatFloorOf(feesMsat)
                Result.success(feeSat)
            }.getOrElse {
                Result.failure(if (it is NodeException) LdkError(it) else it)
            }
        }
    }
    // endregion

    // region probing
    suspend fun sendProbes(bolt11: String): Result<Set<PaymentId>> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        val bolt11Invoice = runCatching { Bolt11Invoice.fromStr(bolt11) }
            .getOrElse { throw LdkError(it as NodeException) }

        val invoiceAmountMsat = bolt11Invoice.amountMilliSatoshis()
        Logger.debug(
            "sendProbes: invoiceAmountMsat=$invoiceAmountMsat (${invoiceAmountMsat?.let { msatFloorOf(it) }} sats)",
            context = TAG
        )

        return ServiceQueue.LDK.background {
            runCatching {
                val handles = node.bolt11Payment().sendProbes(bolt11Invoice, null)
                Result.success(handles.map { it.paymentId }.toSet())
            }.getOrElse {
                dumpNetworkGraphInfo(bolt11)
                Result.failure(if (it is NodeException) LdkError(it) else it)
            }
        }
    }

    suspend fun sendProbesUsingAmount(bolt11: String, amountMsat: ULong): Result<Set<PaymentId>> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        val bolt11Invoice = runCatching { Bolt11Invoice.fromStr(bolt11) }
            .getOrElse { throw LdkError(it as NodeException) }

        val invoiceAmountMsat = bolt11Invoice.amountMilliSatoshis()
        Logger.debug(
            "sendProbesUsingAmount: customAmountMsat=$amountMsat (${msatFloorOf(amountMsat)} sats), " +
                "invoiceAmountMsat=$invoiceAmountMsat (${invoiceAmountMsat?.let { msatFloorOf(it) }} sats)",
            context = TAG
        )

        return ServiceQueue.LDK.background {
            runCatching {
                val handles = node.bolt11Payment().sendProbesUsingAmount(bolt11Invoice, amountMsat, null)
                Result.success(handles.map { it.paymentId }.toSet())
            }.getOrElse {
                dumpNetworkGraphInfo(bolt11)
                Result.failure(if (it is NodeException) LdkError(it) else it)
            }
        }
    }

    suspend fun sendKeysendProbe(nodeId: PublicKey, amountMsat: ULong): Result<Set<PaymentId>> {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        Logger.debug(
            "Sending keysend probe to nodeId='$nodeId' amountMsat='$amountMsat' (${msatFloorOf(amountMsat)} sats)",
            context = TAG,
        )

        return ServiceQueue.LDK.background {
            runCatching {
                val handles = node.spontaneousPayment().sendProbes(amountMsat, nodeId)
                Result.success(handles.map { it.paymentId }.toSet())
            }.getOrElse {
                Result.failure(if (it is NodeException) LdkError(it) else it)
            }
        }
    }
    // endregion

    private fun scorerFeeParameters(): ScoringFeeParameters = DEFAULT_SCORING_FEE_PARAMETERS.copy(
        basePenaltyMsat = SCORING_BASE_PENALTY_MSAT,
        liquidityPenaltyMultiplierMsat = SCORING_LIQUIDITY_PENALTY_MULTIPLIER_MSAT,
        liquidityPenaltyAmountMultiplierMsat = SCORING_LIQUIDITY_PENALTY_AMOUNT_MULTIPLIER_MSAT,
        historicalLiquidityPenaltyAmountMultiplierMsat =
            SCORING_HISTORICAL_LIQUIDITY_PENALTY_AMOUNT_MULTIPLIER_MSAT,
        consideredImpossiblePenaltyMsat = SCORING_CONSIDERED_IMPOSSIBLE_PENALTY_MSAT,
        probingDiversityPenaltyMsat = SCORING_PROBING_DIVERSITY_PENALTY_MSAT,
    )

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

        return ServiceQueue.LDK.background {
            return@background runCatching {
                node.onchainPayment().calculateTotalFee(
                    address = address,
                    amountSats = amountSats,
                    feeRate = FeeRate.fromSatPerVbUnchecked(satsPerVByte),
                    utxosToSpend = utxosToSpend,
                ).also {
                    Logger.debug(
                        "Calculated fee='$it' for $amountSats sats to $address, satsPerVByte=$satsPerVByte",
                        context = TAG,
                    )
                }
            }.getOrElse {
                Logger.warn(
                    "Error calculating fee for $amountSats sats to $address, " +
                        "${utxosToSpend?.size} UTXOs, satsPerVByte=$satsPerVByte",
                    context = TAG,
                    e = it,
                )

                throw if (it is NodeException) LdkError(it) else it
            }
        }
    }

    /** Estimates the fee for a send-all (drain) transaction */
    suspend fun estimateSendAllFee(
        address: Address,
        satsPerVByte: ULong,
    ): ULong {
        val node = this.node ?: throw ServiceError.NodeNotSetup()

        return ServiceQueue.LDK.background {
            node.onchainPayment().calculateSendAllFee(
                address = address,
                retainReserves = true,
                feeRate = FeeRate.fromSatPerVbUnchecked(satsPerVByte),
            )
        }
    }
    // endregion

    // region events
    private var shouldListenForEvents = true

    suspend fun startEventListener(onEvent: NodeEventHandler? = null): Result<Unit> = runCatching {
        val node = this.node ?: throw ServiceError.NodeNotSetup()
        listenerJob?.cancelAndJoin()
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

    suspend fun getBalanceForAddressType(addressType: AddressType): AddressTypeBalance =
        ServiceQueue.LDK.background {
            val n = node ?: throw ServiceError.NodeNotSetup()
            n.getBalanceForAddressType(addressType.toLdkAddressType())
        }

    suspend fun setPrimaryAddressType(addressType: AddressType) = ServiceQueue.LDK.background {
        val n = node ?: throw ServiceError.NodeNotSetup()
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        n.setPrimaryAddressTypeWithMnemonic(addressType.toLdkAddressType(), mnemonic, passphrase)
    }

    suspend fun addAddressTypeToMonitor(addressType: AddressType) = ServiceQueue.LDK.background {
        val n = node ?: throw ServiceError.NodeNotSetup()
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        n.addAddressTypeToMonitorWithMnemonic(addressType.toLdkAddressType(), mnemonic, passphrase)
    }

    suspend fun removeAddressTypeFromMonitor(addressType: AddressType) = ServiceQueue.LDK.background {
        val n = node ?: throw ServiceError.NodeNotSetup()
        n.removeAddressTypeFromMonitor(addressType.toLdkAddressType())
    }

    suspend fun listMonitoredAddressTypes(): List<AddressType> = ServiceQueue.LDK.background {
        val n = node ?: throw ServiceError.NodeNotSetup()
        n.listMonitoredAddressTypes().map { it.toBitkitAddressType() }
    }

    private fun LdkAddressType.toBitkitAddressType(): AddressType = when (this) {
        LdkAddressType.LEGACY -> AddressType.P2PKH
        LdkAddressType.NESTED_SEGWIT -> AddressType.P2SH
        LdkAddressType.NATIVE_SEGWIT -> AddressType.P2WPKH
        LdkAddressType.TAPROOT -> AddressType.P2TR
    }

    private fun AddressType.toLdkAddressType(): LdkAddressType = when (this) {
        AddressType.P2PKH -> LdkAddressType.LEGACY
        AddressType.P2SH -> LdkAddressType.NESTED_SEGWIT
        AddressType.P2WPKH -> LdkAddressType.NATIVE_SEGWIT
        AddressType.P2TR -> LdkAddressType.TAPROOT
        else -> LdkAddressType.NATIVE_SEGWIT
    }

    // region state
    val nodeId: String? get() = node?.nodeId()
    val balances: BalanceDetails? get() = node?.listBalances()
    val status: NodeStatus? get() = node?.status()
    val config: Config? get() = node?.config()
    val peers: List<PeerDetails>? get() = node?.listPeers()
    val channels: List<ChannelDetails>? get() = node?.listChannels()

    suspend fun listPayments(): List<PaymentDetails>? {
        val node = this.node ?: return null
        return ServiceQueue.LDK.background {
            node.listPayments()
        }
    }
    // endregion

    // region debug

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

    private fun dumpNetworkGraphInfo(bolt11: String) {
        val node = this.node ?: return
        loggerLdk.dumpNetworkGraphInfo(node, trustedPeers, bolt11)
    }

    suspend fun exportNetworkGraphToFile(
        outputDir: String,
        fileName: String = "network_graph_nodes.txt",
    ): Result<File> {
        val node = this.node ?: return Result.failure(ServiceError.NodeNotSetup())
        return loggerLdk.exportNetworkGraphToFile(node, outputDir, fileName)
    }

    // endregion
}

@Serializable
data class NetworkGraphInfo(
    val nodeCount: Int,
    val channelCount: Int,
    val latestRgsSyncTimestamp: ULong?,
)

class TrustedPeerForceCloseException : AppError(
    "Cannot force close channel with trusted peer. Force close is disabled for Blocktank LSP channels."
)
