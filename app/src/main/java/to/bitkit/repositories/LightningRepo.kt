package to.bitkit.repositories

import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.PreActivityMetadata
import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.createChannelRequestUrl
import com.synonym.bitkitcore.createWithdrawCallbackUrl
import com.synonym.bitkitcore.decode
import com.synonym.bitkitcore.lnurlAuth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.BestBlock
import org.lightningdevkit.ldknode.ChannelConfig
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.PeerDetails
import org.lightningdevkit.ldknode.SpendableUtxo
import org.lightningdevkit.ldknode.TransactionDetails
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.getSatsPerVByteFor
import to.bitkit.ext.nowTimestamp
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.OpenChannelResult
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.toCoinSelectAlgorithm
import to.bitkit.models.toCoreNetwork
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.LightningService
import to.bitkit.services.LnurlChannelResponse
import to.bitkit.services.LnurlService
import to.bitkit.services.LnurlWithdrawResponse
import to.bitkit.services.LspNotificationsService
import to.bitkit.services.NodeEventHandler
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Singleton
@Suppress("LongParameterList")
class LightningRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningService: LightningService,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val settingsStore: SettingsStore,
    private val coreService: CoreService,
    private val lspNotificationsService: LspNotificationsService,
    private val firebaseMessaging: FirebaseMessaging,
    private val keychain: Keychain,
    private val lnurlService: LnurlService,
    private val cacheStore: CacheStore,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
) {
    private val _lightningState = MutableStateFlow(LightningState())
    val lightningState = _lightningState.asStateFlow()

    private val scope = CoroutineScope(bgDispatcher + SupervisorJob())

    private var _eventHandler: NodeEventHandler? = null
    private val _isRecoveryMode = MutableStateFlow(false)
    val isRecoveryMode = _isRecoveryMode.asStateFlow()

    private val channelCache = ConcurrentHashMap<String, ChannelDetails>()

    /**
     * Executes the provided operation only if the node is running.
     * If the node is not running, waits for it to be running for a specified timeout.
     *
     * @param operationName Name of the operation for logging
     * @param waitTimeout Duration to wait for the node to be running
     * @param operation Lambda to execute when the node is running
     * @return Result of the operation, or failure if node isn't running or operation fails
     */
    suspend fun <T> executeWhenNodeRunning(
        operationName: String,
        waitTimeout: Duration = 1.minutes,
        operation: suspend () -> Result<T>,
    ): Result<T> = withContext(bgDispatcher) {
        Logger.verbose("Operation called: $operationName", context = TAG)

        if (_lightningState.value.nodeLifecycleState.isRunning()) {
            return@withContext executeOperation(operationName, operation)
        }

        // If node is not in a state that can become running, fail fast
        if (!_lightningState.value.nodeLifecycleState.canRun()) {
            return@withContext Result.failure(
                Exception(
                    "Cannot execute $operationName: Node is ${_lightningState.value.nodeLifecycleState} and not starting"
                )
            )
        }

        val nodeRunning = withTimeoutOrNull(waitTimeout) {
            if (_lightningState.value.nodeLifecycleState.isRunning()) {
                return@withTimeoutOrNull true
            }

            // Otherwise, wait for it to transition to running state
            Logger.verbose("Waiting for node runs to execute $operationName", context = TAG)
            _lightningState.first { it.nodeLifecycleState.isRunning() }
            Logger.debug("Operation executed: $operationName", context = TAG)
            true
        } ?: false

        if (!nodeRunning) {
            return@withContext Result.failure(
                Exception("Timeout waiting for node to be running to execute $operationName")
            )
        }

        return@withContext executeOperation(operationName, operation)
    }

    private suspend fun <T> executeOperation(
        operationName: String,
        operation: suspend () -> Result<T>,
    ): Result<T> {
        return try {
            operation()
        } catch (e: Throwable) {
            Logger.error("$operationName error", e, context = TAG)
            Result.failure(e)
        }
    }

    private suspend fun setup(
        walletIndex: Int,
        customServerUrl: String? = null,
        customRgsServerUrl: String? = null,
    ) = withContext(bgDispatcher) {
        return@withContext try {
            lightningService.setup(walletIndex, customServerUrl, customRgsServerUrl)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Node setup error", e, context = TAG)
            Result.failure(e)
        }
    }

    @Suppress("LongMethod", "LongParameterList")
    suspend fun start(
        walletIndex: Int = 0,
        timeout: Duration? = null,
        shouldRetry: Boolean = true,
        customServerUrl: String? = null,
        customRgsServerUrl: String? = null,
        eventHandler: NodeEventHandler? = null,
    ): Result<Unit> = withContext(bgDispatcher) {
        if (_isRecoveryMode.value) {
            return@withContext Result.failure(
                RecoveryModeException("App in recovery mode, skipping node start")
            )
        }

        val initialLifecycleState = _lightningState.value.nodeLifecycleState
        if (initialLifecycleState.isRunningOrStarting()) {
            Logger.info("LDK node start skipped, lifecycle state: $initialLifecycleState", context = TAG)
            return@withContext Result.success(Unit)
        }

        try {
            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Starting) }

            this@LightningRepo._eventHandler = eventHandler

            // Setup if not already setup
            if (lightningService.node == null) {
                val setupResult = setup(walletIndex, customServerUrl, customRgsServerUrl)
                if (setupResult.isFailure) {
                    _lightningState.update {
                        it.copy(
                            nodeLifecycleState = NodeLifecycleState.ErrorStarting(
                                setupResult.exceptionOrNull() ?: Exception("Unknown setup error")
                            )
                        )
                    }
                    return@withContext setupResult
                }
            }

            if (getStatus()?.isRunning == true) {
                Logger.info("LDK node already running", context = TAG)
                _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }
                lightningService.listenForEvents(::onEvent)
                return@withContext Result.success(Unit)
            }

            // Start the node service
            lightningService.start(timeout, ::onEvent)

            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }

            // Initial state sync
            syncState()
            updateGeoBlockState()
            refreshChannelCache()

            // Perform post-startup tasks
            connectToTrustedPeers().onFailure { e ->
                Logger.error("Failed to connect to trusted peers", e)
            }
            sync()
            registerForNotifications()

            Result.success(Unit)
        } catch (e: Throwable) {
            if (shouldRetry) {
                Logger.warn("Start error, retrying after two seconds...", e = e, context = TAG)
                _lightningState.update { it.copy(nodeLifecycleState = initialLifecycleState) }

                delay(2.seconds)
                return@withContext start(
                    walletIndex = walletIndex,
                    timeout = timeout,
                    shouldRetry = false,
                    customServerUrl = customServerUrl,
                    customRgsServerUrl = customRgsServerUrl,
                    eventHandler = eventHandler,
                )
            } else {
                Logger.error("Node start error", e, context = TAG)
                _lightningState.update {
                    it.copy(nodeLifecycleState = NodeLifecycleState.ErrorStarting(e))
                }
                Result.failure(e)
            }
        }
    }

    private suspend fun onEvent(event: Event) {
        handleLdkEvent(event)
        _eventHandler?.invoke(event)
        ldkNodeEventBus.emit(event)
    }

    fun setRecoveryMode(enabled: Boolean) {
        _isRecoveryMode.value = enabled
    }

    suspend fun updateGeoBlockState() = withContext(bgDispatcher) {
        val (isGeoBlocked, shouldBlockLightning) = coreService.checkGeoBlock()
        _lightningState.update {
            it.copy(isGeoBlocked = isGeoBlocked, shouldBlockLightningReceive = shouldBlockLightning)
        }
    }

    fun setInitNodeLifecycleState() {
        _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Initializing) }
    }

    suspend fun stop(): Result<Unit> = withContext(bgDispatcher) {
        if (_lightningState.value.nodeLifecycleState.isStoppedOrStopping()) {
            return@withContext Result.success(Unit)
        }

        try {
            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Stopping) }
            lightningService.stop()
            _lightningState.update { LightningState(nodeLifecycleState = NodeLifecycleState.Stopped) }
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Node stop error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun sync(): Result<Unit> = executeWhenNodeRunning("Sync") {
        syncState()
        if (_lightningState.value.isSyncingWallet) {
            Logger.warn("Sync already in progress, waiting for existing sync.", context = TAG)
        }

        withTimeout(SYNC_TIMEOUT_MS) {
            _lightningState.first { !it.isSyncingWallet }
        }

        _lightningState.update { it.copy(isSyncingWallet = true) }
        lightningService.sync()
        refreshChannelCache()
        syncState()
        _lightningState.update { it.copy(isSyncingWallet = false) }

        Result.success(Unit)
    }

    private suspend fun refreshChannelCache() = withContext(bgDispatcher) {
        val channels = lightningService.channels ?: return@withContext
        channels.forEach { channel ->
            channelCache[channel.channelId] = channel
        }
    }

    private fun handleLdkEvent(event: Event) {
        when (event) {
            is Event.ChannelPending -> {
                scope.launch {
                    refreshChannelCache()
                }
            }

            is Event.ChannelReady -> {
                scope.launch {
                    refreshChannelCache()
                }
            }

            is Event.ChannelClosed -> {
                val channelId = event.channelId
                val reason = event.reason?.toString() ?: ""
                scope.launch {
                    registerClosedChannel(channelId, reason)
                }
            }

            else -> {
                // Other events don't need special handling
            }
        }
    }

    private suspend fun registerClosedChannel(channelId: String, reason: String?) = withContext(bgDispatcher) {
        try {
            val channel = channelCache[channelId] ?: run {
                Logger.error(
                    "Could not find channel details for closed channel: channelId=$channelId",
                    context = TAG
                )
                return@withContext
            }

            val fundingTxo = channel.fundingTxo
            if (fundingTxo == null) {
                Logger.error(
                    "Channel has no funding transaction, cannot persist closed channel: channelId=$channelId",
                    context = TAG
                )
                return@withContext
            }

            val channelName = channel.inboundScidAlias?.toString()
                ?: (channel.channelId.take(CHANNEL_ID_PREVIEW_LENGTH) + "…")

            val closedAt = (System.currentTimeMillis() / 1000L).toULong()

            val closedChannel = ClosedChannelDetails(
                channelId = channel.channelId,
                counterpartyNodeId = channel.counterpartyNodeId,
                fundingTxoTxid = fundingTxo.txid,
                fundingTxoIndex = fundingTxo.vout,
                channelValueSats = channel.channelValueSats,
                closedAt = closedAt,
                outboundCapacityMsat = channel.outboundCapacityMsat,
                inboundCapacityMsat = channel.inboundCapacityMsat,
                counterpartyUnspendablePunishmentReserve = channel.counterpartyUnspendablePunishmentReserve,
                unspendablePunishmentReserve = channel.unspendablePunishmentReserve ?: 0u,
                forwardingFeeProportionalMillionths = channel.config.forwardingFeeProportionalMillionths,
                forwardingFeeBaseMsat = channel.config.forwardingFeeBaseMsat,
                channelName = channelName,
                channelClosureReason = reason.orEmpty()
            )

            coreService.activity.upsertClosedChannelList(listOf(closedChannel))

            channelCache.remove(channelId)

            Logger.info("Registered closed channel: ${channel.userChannelId}", context = TAG)
        } catch (e: Throwable) {
            Logger.error("Failed to register closed channel: $e", e, context = TAG)
        }
    }

    suspend fun wipeStorage(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("wipeStorage called, stopping node first", context = TAG)
        stop().onSuccess {
            return@withContext try {
                Logger.debug("node stopped, calling wipeStorage", context = TAG)
                lightningService.wipeStorage(walletIndex)
                _lightningState.update {
                    LightningState(
                        nodeStatus = it.nodeStatus,
                        nodeLifecycleState = it.nodeLifecycleState,
                    )
                }
                setRecoveryMode(false)
                Result.success(Unit)
            } catch (e: Throwable) {
                Logger.error("Wipe storage error", e, context = TAG)
                Result.failure(e)
            }
        }.onFailure { e ->
            return@withContext Result.failure(e)
        }
    }

    suspend fun restartWithElectrumServer(newServerUrl: String): Result<Unit> = withContext(bgDispatcher) {
        Logger.info("Changing ldk-node electrum server to: '$newServerUrl'")

        waitForNodeToStop().onFailure { return@withContext Result.failure(it) }
        stop().onFailure {
            Logger.error("Failed to stop node during electrum server change", it)
            return@withContext Result.failure(it)
        }

        Logger.debug("Starting node with new electrum server: '$newServerUrl'")

        start(
            shouldRetry = false,
            customServerUrl = newServerUrl,
            eventHandler = _eventHandler,
        ).onFailure { startError ->
            Logger.warn("Failed ldk-node config change, attempting recovery…")
            restartWithPreviousConfig()
            return@withContext Result.failure(startError)
        }.onSuccess {
            settingsStore.update { it.copy(electrumServer = newServerUrl) }

            Logger.info("Successfully changed electrum server")
            return@withContext Result.success(Unit)
        }
    }

    suspend fun restartWithRgsServer(newRgsUrl: String): Result<Unit> = withContext(bgDispatcher) {
        Logger.info("Changing ldk-node RGS server to: '$newRgsUrl'")

        waitForNodeToStop().onFailure { return@withContext Result.failure(it) }
        stop().onFailure {
            Logger.error("Failed to stop node during RGS server change", it)
            return@withContext Result.failure(it)
        }

        Logger.debug("Starting node with new RGS server: '$newRgsUrl'")

        start(
            shouldRetry = false,
            customRgsServerUrl = newRgsUrl,
            eventHandler = _eventHandler,
        ).onFailure { startError ->
            Logger.warn("Failed ldk-node config change, attempting recovery…")
            restartWithPreviousConfig()
            return@withContext Result.failure(startError)
        }.onSuccess {
            settingsStore.update { it.copy(rgsServerUrl = newRgsUrl) }

            Logger.info("Successfully changed RGS server")
            return@withContext Result.success(Unit)
        }
    }

    private suspend fun restartWithPreviousConfig(): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("Stopping node for recovery attempt")

        stop().onFailure { e ->
            Logger.error("Failed to stop node during recovery", e)
            return@withContext Result.failure(e)
        }

        Logger.debug("Starting node with previous config for recovery")

        start(
            shouldRetry = false,
            eventHandler = _eventHandler,
        ).onSuccess {
            Logger.debug("Successfully started node with previous config")
        }.onFailure { e ->
            Logger.error("Failed starting node with previous config", e)
        }
    }

    private suspend fun waitForNodeToStop(): Result<Unit> = withContext(bgDispatcher) {
        if (_lightningState.value.nodeLifecycleState == NodeLifecycleState.Stopping) {
            Logger.debug("Waiting for node to stop…")
            val stopped = withTimeoutOrNull(30.seconds) {
                _lightningState.first { it.nodeLifecycleState == NodeLifecycleState.Stopped }
            }
            if (stopped == null) {
                val error = Exception("Timeout waiting for node to stop")
                Logger.warn(error.message)
                return@withContext Result.failure(error)
            }
        }
        return@withContext Result.success(Unit)
    }

    suspend fun connectToTrustedPeers(): Result<Unit> = executeWhenNodeRunning("Connect to trusted peers") {
        lightningService.connectToTrustedPeers()
        Result.success(Unit)
    }

    suspend fun connectPeer(peer: PeerDetails): Result<Unit> = executeWhenNodeRunning("connectPeer") {
        lightningService.connectPeer(peer).onFailure { e ->
            return@executeWhenNodeRunning Result.failure(e)
        }
        syncState()
        Result.success(Unit)
    }

    suspend fun disconnectPeer(peer: PeerDetails): Result<Unit> = executeWhenNodeRunning("Disconnect peer") {
        lightningService.disconnectPeer(peer)
        syncState()
        Result.success(Unit)
    }

    suspend fun newAddress(): Result<String> = executeWhenNodeRunning("New address") {
        val address = lightningService.newAddress()
        Result.success(address)
    }

    suspend fun createInvoice(
        amountSats: ULong? = null,
        description: String,
        expirySeconds: UInt = 86_400u,
    ): Result<String> = executeWhenNodeRunning("Create invoice") {
        updateGeoBlockState()
        if (lightningState.value.shouldBlockLightningReceive) {
            return@executeWhenNodeRunning Result.failure(ServiceError.GeoBlocked)
        }

        val invoice = lightningService.receive(amountSats, description, expirySeconds)
        Result.success(invoice)
    }

    suspend fun fetchLnurlInvoice(
        callbackUrl: String,
        amountSats: ULong,
        comment: String? = null,
    ): Result<LightningInvoice> {
        return runCatching {
            // TODO use bitkit-core getLnurlInvoice if it works with callbackUrl
            val bolt11 = lnurlService.fetchLnurlInvoice(callbackUrl, amountSats, comment).getOrThrow().pr
            val decoded = (decode(bolt11) as Scanner.Lightning).invoice
            return@runCatching decoded
        }.onFailure {
            Logger.error("Error fetching lnurl invoice, url: $callbackUrl, amount: $amountSats, comment: $comment", it)
        }
    }

    suspend fun requestLnurlWithdraw(
        k1: String,
        callback: String,
        paymentRequest: String,
    ): Result<LnurlWithdrawResponse> = executeWhenNodeRunning("requestLnurlWithdraw") {
        val callbackUrl = createWithdrawCallbackUrl(k1 = k1, callback = callback, paymentRequest = paymentRequest)
        Logger.debug("handleLnurlWithdraw callbackUrl generated: '$callbackUrl'")
        lnurlService.requestLnurlWithdraw(callbackUrl)
    }

    suspend fun fetchLnurlChannelInfo(url: String) = lnurlService.fetchLnurlChannelInfo(url)

    suspend fun requestLnurlChannel(
        k1: String,
        callback: String,
        nodeId: String,
    ): Result<LnurlChannelResponse> = executeWhenNodeRunning("requestLnurlChannel") {
        val url = createChannelRequestUrl(
            k1 = k1,
            callback = callback,
            localNodeId = nodeId,
            isPrivate = true,
            cancel = false,
        )
        lnurlService.requestLnurlChannel(url)
    }

    suspend fun requestLnurlAuth(
        k1: String,
        callback: String,
        domain: String,
    ): Result<String> = runCatching {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

        val result = lnurlAuth(
            k1 = k1,
            callback = callback,
            domain = domain,
            network = Env.network.toCoreNetwork(),
            bip32Mnemonic = mnemonic,
            bip39Passphrase = passphrase,
        )

        Logger.debug("LNURL auth result: '$result'")

        return@runCatching result
    }.onFailure {
        Logger.error("Error requesting lnurl auth, k1: $k1, callback: $callback, domain: $domain", it)
    }

    suspend fun payInvoice(bolt11: String, sats: ULong? = null): Result<PaymentId> =
        executeWhenNodeRunning("Pay invoice") {
            val paymentId = lightningService.send(bolt11 = bolt11, sats = sats)
            syncState()
            Result.success(paymentId)
        }

    @Suppress("LongParameterList")
    suspend fun sendOnChain(
        address: Address,
        sats: ULong,
        speed: TransactionSpeed? = null,
        utxosToSpend: List<SpendableUtxo>? = null,
        feeRates: FeeRates? = null,
        isTransfer: Boolean = false,
        channelId: String? = null,
        isMaxAmount: Boolean = false,
        tags: List<String> = emptyList(),
    ): Result<Txid> =
        executeWhenNodeRunning("sendOnChain") {
            require(address.isNotEmpty()) { "Send address cannot be empty" }

            val transactionSpeed = speed ?: settingsStore.data.first().defaultTransactionSpeed
            val satsPerVByte = getFeeRateForSpeed(transactionSpeed, feeRates).getOrThrow().toUInt()

            // if utxos are manually specified, use them, otherwise run auto coin select if enabled
            val finalUtxosToSpend = utxosToSpend ?: determineUtxosToSpend(
                sats = sats,
                satsPerVByte = satsPerVByte,
            )

            Logger.debug("UTXOs selected to spend: $finalUtxosToSpend", context = TAG)

            val txId = lightningService.send(
                address = address,
                sats = sats,
                satsPerVByte = satsPerVByte,
                utxosToSpend = finalUtxosToSpend,
                isMaxAmount = isMaxAmount
            )

            val preActivityMetadata = PreActivityMetadata(
                paymentId = txId,
                createdAt = nowTimestamp().toEpochMilli().toULong(),
                tags = tags,
                paymentHash = null,
                txId = txId,
                address = address,
                isReceive = false,
                feeRate = satsPerVByte.toULong(),
                isTransfer = isTransfer,
                channelId = channelId ?: "",
            )
            preActivityMetadataRepo.addPreActivityMetadata(preActivityMetadata)

            syncState()
            Result.success(txId)
        }

    suspend fun determineUtxosToSpend(
        sats: ULong,
        satsPerVByte: UInt,
    ): List<SpendableUtxo>? = withContext(bgDispatcher) {
        return@withContext runCatching {
            val settings = settingsStore.data.first()
            if (settings.coinSelectAuto) {
                val coinSelectionPreference = settings.coinSelectPreference

                val allSpendableUtxos = lightningService.listSpendableOutputs().getOrThrow()

                if (coinSelectionPreference == CoinSelectionPreference.Consolidate) {
                    Logger.debug("Consolidating by spending all ${allSpendableUtxos.size} UTXOs", context = TAG)
                    return@withContext allSpendableUtxos
                }

                val coinSelectionAlgorithm = coinSelectionPreference.toCoinSelectAlgorithm().getOrThrow()

                Logger.debug("Selecting UTXOs with algorithm: $coinSelectionAlgorithm for sats: $sats", context = TAG)
                Logger.verbose("All spendable UTXOs(${allSpendableUtxos.size}): $allSpendableUtxos", context = TAG)

                lightningService.selectUtxosWithAlgorithm(
                    targetAmountSats = sats,
                    algorithm = coinSelectionAlgorithm,
                    satsPerVByte = satsPerVByte,
                    utxos = allSpendableUtxos,
                ).onSuccess {
                    Logger.debug("Selected ${it.size} UTXOs", context = TAG)
                }.getOrThrow()
            } else {
                null // let ldk-node handle utxos
            }
        }.getOrNull()
    }

    suspend fun getPayments(): Result<List<PaymentDetails>> = executeWhenNodeRunning("Get payments") {
        val payments = lightningService.payments
            ?: return@executeWhenNodeRunning Result.failure(Exception("It wasn't possible get the payments"))
        Result.success(payments)
    }

    suspend fun getTransactionDetails(txid: Txid): Result<TransactionDetails?> = executeWhenNodeRunning(
        "Get transaction details by txid"
    ) {
        Result.success(lightningService.getTransactionDetails(txid))
    }

    suspend fun getAddressBalance(address: String): Result<ULong> = executeWhenNodeRunning("Get address balance") {
        runCatching {
            lightningService.getAddressBalance(address)
        }
    }

    suspend fun listSpendableOutputs(): Result<List<SpendableUtxo>> = executeWhenNodeRunning("List spendable outputs") {
        lightningService.listSpendableOutputs()
    }

    suspend fun calculateTotalFee(
        amountSats: ULong,
        address: Address? = null,
        speed: TransactionSpeed? = null,
        utxosToSpend: List<SpendableUtxo>? = null,
        feeRates: FeeRates? = null,
    ): Result<ULong> = withContext(bgDispatcher) {
        return@withContext try {
            val transactionSpeed = speed ?: settingsStore.data.first().defaultTransactionSpeed
            val satsPerVByte = getFeeRateForSpeed(transactionSpeed, feeRates).getOrThrow().toUInt()

            val addressOrDefault = address ?: cacheStore.data.first().onchainAddress

            val fee = lightningService.calculateTotalFee(
                address = addressOrDefault,
                amountSats = amountSats,
                satsPerVByte = satsPerVByte,
                utxosToSpend = utxosToSpend,
            )
            Result.success(fee)
        } catch (_: Throwable) {
            val fallbackFee = 1000uL
            Logger.warn("Error calculating fee, using fallback of $fallbackFee", context = TAG)
            Result.success(fallbackFee)
        }
    }

    suspend fun getFeeRateForSpeed(
        speed: TransactionSpeed,
        feeRates: FeeRates? = null,
    ): Result<ULong> = withContext(bgDispatcher) {
        return@withContext runCatching {
            val fees = feeRates ?: coreService.blocktank.getFees().getOrThrow()
            val satsPerVByte = fees.getSatsPerVByteFor(speed)
            satsPerVByte.toULong()
        }.onFailure { e ->
            Logger.error("Error getFeeRateForSpeed. speed:$speed", e, context = TAG)
        }
    }

    suspend fun calculateCpfpFeeRate(
        parentTxId: Txid,
    ): Result<ULong> = executeWhenNodeRunning("Calculate CPFP fee rate") {
        Result.success(lightningService.calculateCpfpFeeRate(parentTxid = parentTxId).toSatPerVbCeil())
    }

    suspend fun openChannel(
        peer: PeerDetails,
        channelAmountSats: ULong,
        pushToCounterpartySats: ULong? = null,
        channelConfig: ChannelConfig? = null,
    ): Result<OpenChannelResult> = executeWhenNodeRunning("Open channel") {
        val result = lightningService.openChannel(peer, channelAmountSats, pushToCounterpartySats, channelConfig)
        syncState()
        result
    }

    suspend fun closeChannel(
        channel: ChannelDetails,
        force: Boolean = false,
        forceCloseReason: String? = null,
    ): Result<Unit> = executeWhenNodeRunning("closeChannel") {
        lightningService.closeChannel(
            channel,
            force,
            forceCloseReason,
        )
        syncState()
        Result.success(Unit)
    }

    fun syncState() {
        _lightningState.update {
            it.copy(
                nodeId = getNodeId().orEmpty(),
                nodeStatus = getStatus(),
                peers = getPeers().orEmpty(),
                channels = getChannels().orEmpty(),
                balances = getBalances(),
            )
        }
    }

    suspend fun canSend(amountSats: ULong, fallbackToCachedBalance: Boolean = true): Boolean {
        return if (!_lightningState.value.nodeLifecycleState.isRunning() && fallbackToCachedBalance) {
            amountSats <= (cacheStore.data.first().balance?.maxSendLightningSats ?: 0u)
        } else {
            lightningService.canSend(amountSats)
        }
    }

    fun getSyncFlow() = lightningService.syncFlow().filter { lightningState.value.nodeLifecycleState.isRunning() }

    fun getNodeId(): String? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.nodeId else null

    fun getBalances(): BalanceDetails? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.balances else null

    suspend fun getBalancesAsync(): Result<BalanceDetails> = executeWhenNodeRunning("getBalancesAsync") {
        Result.success(checkNotNull(lightningService.balances))
    }

    suspend fun getChannelsAsync(): Result<List<ChannelDetails>> = executeWhenNodeRunning("getChannelsAsync") {
        Result.success(checkNotNull(lightningService.channels))
    }

    fun getStatus(): NodeStatus? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.status else null

    fun getPeers(): List<PeerDetails>? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.peers else null

    fun getChannels(): List<ChannelDetails>? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.channels else null

    fun canReceive(): Boolean {
        val isRunning = _lightningState.value.nodeLifecycleState.isRunning()
        return isRunning && lightningService.canReceive()
    }

    suspend fun registerForNotifications(token: String? = null) = executeWhenNodeRunning("registerForNotifications") {
        return@executeWhenNodeRunning try {
            val token = token ?: firebaseMessaging.token.await()
            val cachedToken = keychain.loadString(Keychain.Key.PUSH_NOTIFICATION_TOKEN.name)

            if (cachedToken == token) {
                Logger.debug("Skipped registering for notifications, current device token already registered")
                return@executeWhenNodeRunning Result.success(Unit)
            }

            lspNotificationsService.registerDevice(token)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Register for notifications error", e)
            Result.failure(e)
        }
    }

    fun registerForNotificationsAsync(token: String) = scope.launch { registerForNotifications(token) }

    suspend fun bumpFeeByRbf(
        originalTxId: Txid,
        satsPerVByte: UInt,
    ): Result<Txid> = executeWhenNodeRunning("Bump by RBF") {
        try {
            if (originalTxId.isBlank()) {
                return@executeWhenNodeRunning Result.failure(
                    IllegalArgumentException(
                        "originalTxId is null or empty: $originalTxId"
                    )
                )
            }

            if (satsPerVByte <= 0u) {
                return@executeWhenNodeRunning Result.failure(
                    IllegalArgumentException(
                        "satsPerVByte invalid: $satsPerVByte"
                    )
                )
            }

            val replacementTxId = lightningService.bumpFeeByRbf(
                txid = originalTxId,
                satsPerVByte = satsPerVByte,
            )
            Logger.debug(
                "bumpFeeByRbf success, replacementTxId: $replacementTxId originalTxId: $originalTxId, satsPerVByte: $satsPerVByte"
            )
            Result.success(replacementTxId)
        } catch (e: Throwable) {
            Logger.error(
                "bumpFeeByRbf error originalTxId: $originalTxId, satsPerVByte: $satsPerVByte",
                e,
                context = TAG
            )
            Result.failure(e)
        }
    }

    suspend fun accelerateByCpfp(
        originalTxId: Txid,
        satsPerVByte: UInt,
        destinationAddress: Address,
    ): Result<Txid> = executeWhenNodeRunning("Accelerate by CPFP") {
        try {
            if (originalTxId.isBlank()) {
                return@executeWhenNodeRunning Result.failure(
                    IllegalArgumentException(
                        "originalTxId is null or empty: $originalTxId"
                    )
                )
            }

            if (destinationAddress.isBlank()) {
                return@executeWhenNodeRunning Result.failure(
                    IllegalArgumentException(
                        "destinationAddress is null or empty: $destinationAddress"
                    )
                )
            }

            if (satsPerVByte <= 0u) {
                return@executeWhenNodeRunning Result.failure(
                    IllegalArgumentException(
                        "satsPerVByte invalid: $satsPerVByte"
                    )
                )
            }

            val newDestinationTxId = lightningService.accelerateByCpfp(
                txid = originalTxId,
                satsPerVByte = satsPerVByte,
                destinationAddress = destinationAddress,
            )
            Logger.debug(
                "accelerateByCpfp success, newDestinationTxId: $newDestinationTxId originalTxId: $originalTxId, satsPerVByte: $satsPerVByte destinationAddress: $destinationAddress"
            )
            Result.success(newDestinationTxId)
        } catch (e: Throwable) {
            Logger.error(
                "accelerateByCpfp error originalTxId: $originalTxId, satsPerVByte: $satsPerVByte destinationAddress: $destinationAddress",
                e,
                context = TAG
            )
            Result.failure(e)
        }
    }

    suspend fun estimateRoutingFees(bolt11: String): Result<ULong> =
        executeWhenNodeRunning("estimateRoutingFees") {
            Logger.info("Estimating routing fees for bolt11: $bolt11")
            lightningService.estimateRoutingFees(bolt11)
                .onSuccess {
                    Logger.info("Routing fees estimated: $it")
                }
                .onFailure {
                    Logger.error("Routing fees estimation failed", it)
                }
        }

    suspend fun estimateRoutingFeesForAmount(bolt11: String, amountSats: ULong): Result<ULong> =
        executeWhenNodeRunning("estimateRoutingFeesForAmount") {
            Logger.info("Estimating routing fees for amount: $amountSats")
            lightningService.estimateRoutingFeesForAmount(bolt11, amountSats)
                .onSuccess {
                    Logger.info("Routing fees estimated: $it")
                }
                .onFailure {
                    Logger.error("Routing fees estimation failed", it)
                }
        }

    companion object {
        private const val TAG = "LightningRepo"
        private const val SYNC_TIMEOUT_MS = 20_000L
        private const val CHANNEL_ID_PREVIEW_LENGTH = 10
    }
}

class RecoveryModeException(override val message: String?) : AppError(message = message)

data class LightningState(
    val nodeId: String = "",
    val nodeStatus: NodeStatus? = null,
    val nodeLifecycleState: NodeLifecycleState = NodeLifecycleState.Stopped,
    val peers: List<PeerDetails> = emptyList(),
    val channels: List<ChannelDetails> = emptyList(),
    val balances: BalanceDetails? = null,
    val isSyncingWallet: Boolean = false,
    val shouldBlockLightningReceive: Boolean = false,
    val isGeoBlocked: Boolean = false,
) {
    fun block(): BestBlock? = nodeStatus?.currentBestBlock
}
