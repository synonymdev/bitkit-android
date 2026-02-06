package to.bitkit.repositories

import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.PreActivityMetadata
import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.createChannelRequestUrl
import com.synonym.bitkitcore.createWithdrawCallbackUrl
import com.synonym.bitkitcore.lnurlAuth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.BestBlock
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.ChannelConfig
import org.lightningdevkit.ldknode.ChannelDataMigration
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.ClosureReason
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.PeerDetails
import org.lightningdevkit.ldknode.SpendableUtxo
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.getSatsPerVByteFor
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.toPeerDetailsList
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.OpenChannelResult
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.toCoinSelectAlgorithm
import to.bitkit.models.toCoreNetwork
import to.bitkit.services.CoreService
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Singleton
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class LightningRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningService: LightningService,
    private val settingsStore: SettingsStore,
    private val coreService: CoreService,
    private val lspNotificationsService: LspNotificationsService,
    private val firebaseMessaging: FirebaseMessaging,
    private val keychain: Keychain,
    private val lnurlService: LnurlService,
    private val cacheStore: CacheStore,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    private val connectivityRepo: ConnectivityRepo,
) {
    private val _lightningState = MutableStateFlow(LightningState())
    val lightningState = _lightningState.asStateFlow()

    private val _nodeEvents = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val nodeEvents = _nodeEvents.asSharedFlow()

    private val scope = CoroutineScope(bgDispatcher + SupervisorJob())

    private val _eventHandlers = ConcurrentHashMap.newKeySet<NodeEventHandler>()
    private val _isRecoveryMode = MutableStateFlow(false)
    val isRecoveryMode = _isRecoveryMode.asStateFlow()

    private val channelCache = ConcurrentHashMap<String, ChannelDetails>()

    private val syncMutex = Mutex()
    private val syncPending = AtomicBoolean(false)
    private val syncRetryJob = AtomicReference<Job?>(null)

    init {
        observeConnectivityForSyncRetry()
    }

    private fun observeConnectivityForSyncRetry() {
        scope.launch {
            connectivityRepo.isOnline
                .map { it == ConnectivityState.CONNECTED }
                .distinctUntilChanged()
                .collect { isConnected ->
                    if (!isConnected) {
                        // Cancel any pending retry when disconnected
                        syncRetryJob.getAndSet(null)?.cancel()
                        return@collect
                    }

                    // Start retry loop if sync is failing
                    startSyncRetryLoopIfNeeded()
                }
        }
    }

    private fun startSyncRetryLoopIfNeeded() {
        val state = _lightningState.value
        if (!state.nodeLifecycleState.isRunning() || state.lastSyncError == null) {
            return
        }

        // Don't start if already retrying
        if (syncRetryJob.get()?.isActive == true) {
            return
        }

        val job = scope.launch {
            // Don't start retry loop if offline
            if (connectivityRepo.isOnline.first() != ConnectivityState.CONNECTED) {
                return@launch
            }

            while (isActive) {
                val currentState = _lightningState.value
                // Stop if no longer running or sync is now healthy
                if (!currentState.nodeLifecycleState.isRunning() || currentState.isSyncHealthy) {
                    Logger.debug("Sync retry loop stopped: node not running or sync healthy", context = TAG)
                    break
                }

                delay(SYNC_RETRY_DELAY_MS)
                Logger.info("Retrying sync after failure", context = TAG)
                sync().onSuccess {
                    Logger.info("Sync retry succeeded", context = TAG)
                }.onFailure {
                    Logger.warn("Sync retry failed, will retry in ${SYNC_RETRY_DELAY_MS / 1000}s", it, context = TAG)
                }
            }
        }
        syncRetryJob.set(job)
    }

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
        Logger.verbose("Operation called: '$operationName'", context = TAG)

        val nodeLifecycleState = _lightningState.value.nodeLifecycleState
        if (nodeLifecycleState.isRunning()) {
            return@withContext executeOperation(operationName, operation)
        }

        // If node is not in a state that can become running, fail fast
        if (!nodeLifecycleState.canRun()) {
            return@withContext Result.failure(
                AppError("Cannot execute '$operationName': node is '$nodeLifecycleState' and not starting")
            )
        }

        val nodeRunning = withTimeoutOrNull(waitTimeout) {
            if (nodeLifecycleState.isRunning()) return@withTimeoutOrNull true

            // Otherwise, wait for it to transition to running state
            Logger.verbose("Waiting for node to run before executing '$operationName'", context = TAG)
            _lightningState.first { it.nodeLifecycleState.isRunning() }
            Logger.debug("Operation executed: '$operationName'", context = TAG)
            true
        } ?: false

        if (!nodeRunning) return@withContext Result.failure(NodeRunTimeoutError(operationName))

        return@withContext executeOperation(operationName, operation)
    }

    private suspend fun <T> executeOperation(
        operationName: String,
        operation: suspend () -> Result<T>,
    ): Result<T> = runCatching {
        operation().getOrThrow()
    }.onFailure {
        // Cancellation is expected during pull-to-refresh, rethrow per Kotlin best practices
        if (it is CancellationException) throw it

        Logger.error("Error executing '$operationName'", it, context = TAG)
    }

    private suspend fun setup(
        walletIndex: Int,
        customServerUrl: String? = null,
        customRgsServerUrl: String? = null,
        channelMigration: ChannelDataMigration? = null,
    ) = withContext(bgDispatcher) {
        runCatching {
            val trustedPeers = fetchTrustedPeers()
            lightningService.setup(
                walletIndex,
                customServerUrl,
                customRgsServerUrl,
                trustedPeers,
                channelMigration,
            )
        }.onFailure {
            Logger.error("Node setup error", it, context = TAG)
        }
    }

    private suspend fun fetchTrustedPeers(): List<PeerDetails>? = runCatching {
        val info = coreService.blocktank.info(refresh = false)
            ?: coreService.blocktank.info(refresh = true)
        info?.nodes?.toPeerDetailsList()?.also {
            Logger.info("Fetched ${it.size} trusted peers from remote", context = TAG)
        }
    }.onFailure {
        Logger.warn("fetchTrustedPeers error", it, context = TAG)
    }.getOrNull()

    @Suppress("LongMethod", "LongParameterList")
    suspend fun start(
        walletIndex: Int = 0,
        timeout: Duration? = null,
        shouldRetry: Boolean = true,
        customServerUrl: String? = null,
        customRgsServerUrl: String? = null,
        eventHandler: NodeEventHandler? = null,
        channelMigration: ChannelDataMigration? = null,
    ): Result<Unit> = withContext(bgDispatcher) {
        if (_isRecoveryMode.value) {
            return@withContext Result.failure(RecoveryModeError())
        }

        eventHandler?.let { _eventHandlers.add(it) }

        val initialLifecycleState = _lightningState.value.nodeLifecycleState
        if (initialLifecycleState.isRunningOrStarting()) {
            Logger.info("LDK node start skipped, lifecycle state: $initialLifecycleState", context = TAG)
            return@withContext Result.success(Unit)
        }

        runCatching {
            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Starting) }

            // Setup if needed
            if (lightningService.node == null) {
                val setupResult = setup(walletIndex, customServerUrl, customRgsServerUrl, channelMigration)
                if (setupResult.isFailure) {
                    _lightningState.update {
                        it.copy(
                            nodeLifecycleState = NodeLifecycleState.ErrorStarting(
                                setupResult.exceptionOrNull() ?: NodeSetupError()
                            )
                        )
                    }
                    return@withContext setupResult
                }
            }

            if (getStatus()?.isRunning == true) {
                Logger.info("LDK node already running", context = TAG)
                _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }
                lightningService.startEventListener(::onEvent).onFailure {
                    Logger.warn("Failed to start event listener", it, context = TAG)
                    return@withContext Result.failure(it)
                }
                return@withContext Result.success(Unit)
            }

            // Start node
            lightningService.start(timeout, ::onEvent)

            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }

            // Initial state sync
            syncState()
            updateGeoBlockState()
            refreshChannelCache()

            // Post-startup tasks (non-blocking)
            connectToTrustedPeers().onFailure {
                Logger.error("Failed to connect to trusted peers", it, context = TAG)
            }

            sync().onFailure { e ->
                Logger.warn("Initial sync failed, event-driven sync will retry", e, context = TAG)
            }
            scope.launch { registerForNotifications() }
            Unit
        }.onFailure { e ->
            val currentLifecycleState = _lightningState.value.nodeLifecycleState
            if (currentLifecycleState.isRunning()) {
                Logger.warn("Start error occurred but node is $currentLifecycleState, skipping retry", e, context = TAG)
                return@withContext Result.success(Unit)
            }

            if (shouldRetry) {
                val retryDelay = 2.seconds
                Logger.warn("Start error, retrying after $retryDelay...", e, context = TAG)
                _lightningState.update { it.copy(nodeLifecycleState = initialLifecycleState) }

                delay(retryDelay)
                return@withContext start(
                    walletIndex = walletIndex,
                    timeout = timeout,
                    shouldRetry = false,
                    customServerUrl = customServerUrl,
                    customRgsServerUrl = customRgsServerUrl,
                    channelMigration = channelMigration,
                )
            } else {
                _lightningState.update {
                    it.copy(nodeLifecycleState = NodeLifecycleState.ErrorStarting(e))
                }
                return@withContext Result.failure(e)
            }
        }
    }

    private suspend fun onEvent(event: Event) {
        handleLdkEvent(event)
        _eventHandlers.toList().forEach {
            runCatching { it.invoke(event) }
        }
        _nodeEvents.emit(event)
    }

    fun setRecoveryMode(enabled: Boolean) = _isRecoveryMode.update { enabled }

    suspend fun updateGeoBlockState() = withContext(bgDispatcher) {
        _lightningState.update {
            it.copy(isGeoBlocked = coreService.isGeoBlocked())
        }
    }

    fun setInitNodeLifecycleState() {
        _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Initializing) }
    }

    suspend fun stop(): Result<Unit> = withContext(bgDispatcher) {
        if (_lightningState.value.nodeLifecycleState.isStoppedOrStopping()) {
            return@withContext Result.success(Unit)
        }

        runCatching {
            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Stopping) }
            lightningService.stop()
            _lightningState.update { LightningState(nodeLifecycleState = NodeLifecycleState.Stopped) }
        }.onFailure {
            Logger.error("Node stop error", it, context = TAG)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun sync(): Result<Unit> = executeWhenNodeRunning("sync") {
        // If sync is in progress, mark pending and skip
        if (!syncMutex.tryLock()) {
            syncPending.set(true)
            Logger.verbose("Sync in progress, pending sync marked", context = TAG)
            return@executeWhenNodeRunning Result.success(Unit)
        }

        runCatching {
            do {
                syncPending.set(false)
                _lightningState.update { it.copy(isSyncingWallet = true) }
                lightningService.sync()
                refreshChannelCache()
                syncState()
                _lightningState.update {
                    it.copy(
                        lastSyncError = null,
                        lastSuccessfulSyncAt = System.currentTimeMillis(),
                    )
                }
                if (syncPending.get()) delay(MS_SYNC_LOOP_DEBOUNCE)
            } while (syncPending.getAndSet(false))
        }.also {
            _lightningState.update { state -> state.copy(isSyncingWallet = false) }
            syncMutex.unlock()
        }.onFailure {
            _lightningState.update { state -> state.copy(lastSyncError = it) }
            startSyncRetryLoopIfNeeded()
        }
    }

    fun syncAsync() = scope.launch {
        sync().onFailure {
            Logger.warn("Sync failed", it, context = TAG)
        }
    }

    private suspend fun ensureSyncedBeforeSend(): Result<Unit> {
        Logger.debug("Ensuring wallet is synced before send", context = TAG)
        return sync().fold(
            onSuccess = { Result.success(Unit) },
            onFailure = {
                Result.failure(SyncUnhealthyError())
            },
        )
    }

    /** Clear pending sync flag. Called when manual pull-to-refresh takes priority. */
    fun clearPendingSync() = syncPending.set(false)

    private suspend fun refreshChannelCache() = withContext(bgDispatcher) {
        lightningService.channels?.forEach {
            channelCache[it.channelId] = it
        }
    }

    private fun handleLdkEvent(event: Event) {
        when (event) {
            is Event.ChannelPending, is Event.ChannelReady -> scope.launch { refreshChannelCache() }
            is Event.ChannelClosed -> scope.launch { registerClosedChannel(event.channelId, event.reason) }
            else -> Unit
        }
    }

    private suspend fun registerClosedChannel(channelId: String, reason: ClosureReason?) = withContext(bgDispatcher) {
        runCatching {
            val channel = channelCache[channelId] ?: run {
                Logger.error("Could not find details for closed channel: channelId='$channelId'", context = TAG)
                return@withContext
            }

            val fundingTxo = channel.fundingTxo
            if (fundingTxo == null) {
                Logger.error(
                    "Channel has no funding transaction, cannot persist closed channel: channelId='$channelId'",
                    context = TAG,
                )
                return@withContext
            }

            val channelName = channel.inboundScidAlias?.toString()
                ?: (channel.channelId.take(LENGTH_CHANNEL_ID_PREVIEW) + "…")

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
                channelClosureReason = reason?.toString().orEmpty(),
            )

            coreService.activity.upsertClosedChannelList(listOf(closedChannel))

            channelCache.remove(channelId)

            Logger.info("Registered closed channel: ${channel.userChannelId}", context = TAG)
        }.onFailure {
            Logger.error("Failed to register closed channel", it, context = TAG)
        }
    }

    suspend fun wipeStorage(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("wipeStorage called, stopping node first", context = TAG)
        stop().mapCatching {
            Logger.debug("node stopped, calling wipeStorage", context = TAG)
            lightningService.wipeStorage(walletIndex)
            _lightningState.update {
                LightningState(
                    nodeStatus = it.nodeStatus,
                    nodeLifecycleState = it.nodeLifecycleState,
                )
            }
            setRecoveryMode(false)
        }.onFailure {
            Logger.error("wipeStorage error", it, context = TAG)
        }
    }

    suspend fun restartWithElectrumServer(newServerUrl: String): Result<Unit> = withContext(bgDispatcher) {
        Logger.info("Changing ldk-node electrum server to: '$newServerUrl'", context = TAG)

        waitForNodeToStop().onFailure { return@withContext Result.failure(it) }
        stop().onFailure {
            Logger.error("Failed to stop node during electrum server change", it, context = TAG)
            return@withContext Result.failure(it)
        }

        Logger.debug("Starting node with new electrum server: '$newServerUrl'", context = TAG)

        start(
            shouldRetry = false,
            customServerUrl = newServerUrl,
        ).onFailure {
            Logger.warn("Failed ldk-node config change, attempting recovery…", context = TAG)
            restartWithPreviousConfig()
        }.onSuccess {
            settingsStore.update { it.copy(electrumServer = newServerUrl) }

            Logger.info("Successfully changed electrum server", context = TAG)
        }
    }

    suspend fun restartWithRgsServer(newRgsUrl: String): Result<Unit> = withContext(bgDispatcher) {
        Logger.info("Changing ldk-node RGS server to: '$newRgsUrl'", context = TAG)

        waitForNodeToStop().onFailure { return@withContext Result.failure(it) }
        stop().onFailure {
            Logger.error("Failed to stop node during RGS server change", it, context = TAG)
            return@withContext Result.failure(it)
        }

        Logger.debug("Starting node with new RGS server: '$newRgsUrl'", context = TAG)

        start(
            shouldRetry = false,
            customRgsServerUrl = newRgsUrl,
        ).onFailure {
            Logger.warn("Failed ldk-node config change, attempting recovery…", context = TAG)
            restartWithPreviousConfig()
        }.onSuccess {
            settingsStore.update { it.copy(rgsServerUrl = newRgsUrl) }

            Logger.info("Successfully changed RGS server", context = TAG)
        }
    }

    private suspend fun restartWithPreviousConfig(): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("Stopping node for recovery attempt", context = TAG)

        stop().onFailure { e ->
            Logger.error("Failed to stop node during recovery", e, context = TAG)
            return@withContext Result.failure(e)
        }

        Logger.debug("Starting node with previous config for recovery", context = TAG)

        start(
            shouldRetry = false,
        ).onSuccess {
            Logger.debug("Successfully started node with previous config", context = TAG)
        }.onFailure {
            Logger.error("Failed starting node with previous config", it, context = TAG)
        }
    }

    private suspend fun waitForNodeToStop(): Result<Unit> = withContext(bgDispatcher) {
        if (_lightningState.value.nodeLifecycleState == NodeLifecycleState.Stopping) {
            Logger.debug("Waiting for node to stop…", context = TAG)
            val stopped = withTimeoutOrNull(30.seconds) {
                _lightningState.first { it.nodeLifecycleState == NodeLifecycleState.Stopped }
            }
            if (stopped == null) {
                val error = NodeStopTimeoutError()
                Logger.warn(error.message, context = TAG)
                return@withContext Result.failure(error)
            }
        }
        return@withContext Result.success(Unit)
    }

    suspend fun connectToTrustedPeers(): Result<Unit> = executeWhenNodeRunning("connectToTrustedPeers") {
        runCatching { lightningService.connectToTrustedPeers() }
    }

    suspend fun connectPeer(peer: PeerDetails): Result<Unit> = executeWhenNodeRunning("connectPeer") {
        lightningService.connectPeer(peer).map {
            syncState()
        }
    }

    suspend fun disconnectPeer(peer: PeerDetails): Result<Unit> = executeWhenNodeRunning("disconnectPeer") {
        lightningService.disconnectPeer(peer).map {
            syncState()
        }
    }

    suspend fun newAddress(): Result<String> = executeWhenNodeRunning("newAddress") {
        runCatching { lightningService.newAddress() }
    }

    suspend fun createInvoice(
        amountSats: ULong? = null,
        description: String,
        expirySeconds: UInt = 86_400u,
    ): Result<String> = executeWhenNodeRunning("createInvoice") {
        updateGeoBlockState()
        runCatching { lightningService.receive(amountSats, description, expirySeconds) }
    }

    @Suppress("ForbiddenComment")
    suspend fun fetchLnurlInvoice(
        callbackUrl: String,
        amountSats: ULong,
        comment: String? = null,
    ): Result<LightningInvoice> {
        return runCatching {
            // TODO use bitkit-core getLnurlInvoice if it works with callbackUrl
            val bolt11 = lnurlService.fetchLnurlInvoice(callbackUrl, amountSats, comment).getOrThrow().pr
            val decoded = (coreService.decode(bolt11) as Scanner.Lightning).invoice
            return@runCatching decoded
        }.onFailure {
            Logger.error(
                "fetchLnurlInvoice error, url: $callbackUrl, amount: $amountSats, comment: $comment",
                it,
                context = TAG,
            )
        }
    }

    suspend fun requestLnurlWithdraw(
        k1: String,
        callback: String,
        paymentRequest: String,
    ): Result<LnurlWithdrawResponse> = executeWhenNodeRunning("requestLnurlWithdraw") {
        val callbackUrl = createWithdrawCallbackUrl(k1, callback, paymentRequest)
        Logger.debug("handleLnurlWithdraw callbackUrl generated: '$callbackUrl'", context = TAG)
        lnurlService.requestLnurlWithdraw(callbackUrl)
    }

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
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

        lnurlAuth(
            k1 = k1,
            callback = callback,
            domain = domain,
            network = Env.network.toCoreNetwork(),
            bip32Mnemonic = mnemonic,
            bip39Passphrase = passphrase,
        ).also {
            Logger.debug("LNURL auth result: '$it'", context = TAG)
        }
    }.onFailure {
        Logger.error("requestLnurlAuth error, k1: $k1, callback: $callback, domain: $domain", it, context = TAG)
    }

    suspend fun payInvoice(
        bolt11: String,
        sats: ULong? = null,
    ): Result<PaymentId> = executeWhenNodeRunning("payInvoice") {
        runCatching { lightningService.send(bolt11, sats) }.also {
            syncState()
        }
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
    ): Result<Txid> = executeWhenNodeRunning("sendOnChain") {
        require(address.isNotEmpty()) { "Send address cannot be empty" }

        // Ensure wallet is synced before sending to have up-to-date state
        ensureSyncedBeforeSend().onFailure {
            return@executeWhenNodeRunning Result.failure(it)
        }

        val transactionSpeed = speed ?: settingsStore.data.first().defaultTransactionSpeed
        val satsPerVByte = getFeeRateForSpeed(transactionSpeed, feeRates).getOrThrow()

        // use passed utxos if specified, otherwise run auto coin select if enabled
        val finalUtxosToSpend = utxosToSpend ?: determineUtxosToSpend(sats, satsPerVByte)

        Logger.debug("UTXOs selected to spend: $finalUtxosToSpend", context = TAG)

        val txId = lightningService.send(address, sats, satsPerVByte, finalUtxosToSpend, isMaxAmount)

        val preActivityMetadata = PreActivityMetadata(
            paymentId = txId,
            createdAt = nowTimestamp().toEpochMilli().toULong(),
            tags = tags,
            paymentHash = null,
            txId = txId,
            address = address,
            isReceive = false,
            feeRate = satsPerVByte,
            isTransfer = isTransfer,
            channelId = channelId ?: "",
        )
        preActivityMetadataRepo.addPreActivityMetadata(preActivityMetadata)

        syncState()
        Result.success(txId)
    }

    suspend fun determineUtxosToSpend(
        sats: ULong,
        satsPerVByte: ULong,
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

    suspend fun getPayments(): Result<List<PaymentDetails>> = executeWhenNodeRunning("getPayments") {
        val payments = lightningService.payments ?: return@executeWhenNodeRunning Result.failure(GetPaymentsError())
        Result.success(payments)
    }

    suspend fun getAddressBalance(address: String): Result<ULong> = executeWhenNodeRunning("getAddressBalance") {
        runCatching {
            lightningService.getAddressBalance(address)
        }
    }

    suspend fun listSpendableOutputs(): Result<List<SpendableUtxo>> = executeWhenNodeRunning("listSpendableOutputs") {
        lightningService.listSpendableOutputs()
    }

    suspend fun calculateTotalFee(
        amountSats: ULong,
        address: Address? = null,
        speed: TransactionSpeed? = null,
        utxosToSpend: List<SpendableUtxo>? = null,
        feeRates: FeeRates? = null,
    ): Result<ULong> = withContext(bgDispatcher) {
        runCatching {
            val transactionSpeed = speed ?: settingsStore.data.first().defaultTransactionSpeed
            val satsPerVByte = getFeeRateForSpeed(transactionSpeed, feeRates).getOrThrow()
            val addressOrDefault = address ?: cacheStore.data.first().onchainAddress

            val fee = lightningService.calculateTotalFee(
                address = addressOrDefault,
                amountSats = amountSats,
                satsPerVByte = satsPerVByte,
                utxosToSpend = utxosToSpend,
            )
            return@runCatching fee
        }.recoverCatching {
            if (it is CancellationException) throw it
            val fallbackFee = 1000uL
            Logger.warn("calculateTotalFee error, using fallback of '$fallbackFee'", e = it, context = TAG)
            return@recoverCatching fallbackFee
        }
    }

    suspend fun getFeeRateForSpeed(
        speed: TransactionSpeed,
        feeRates: FeeRates? = null,
    ): Result<ULong> = withContext(bgDispatcher) {
        runCatching {
            val fees = feeRates ?: coreService.blocktank.getFees().getOrThrow()
            val satsPerVByte = fees.getSatsPerVByteFor(speed)
            satsPerVByte.toULong()
        }.onFailure {
            if (it !is CancellationException) {
                Logger.error("getFeeRateForSpeed error: speed: '$speed'", it, context = TAG)
            }
        }
    }

    suspend fun calculateCpfpFeeRate(
        parentTxId: Txid,
    ): Result<ULong> = executeWhenNodeRunning("calculateCpfpFeeRate") {
        Result.success(lightningService.calculateCpfpFeeRate(parentTxId).toSatPerVbCeil())
    }

    suspend fun openChannel(
        peer: PeerDetails,
        channelAmountSats: ULong,
        pushToCounterpartySats: ULong? = null,
        channelConfig: ChannelConfig? = null,
    ): Result<OpenChannelResult> = executeWhenNodeRunning("openChannel") {
        lightningService.openChannel(peer, channelAmountSats, pushToCounterpartySats, channelConfig).also {
            syncState()
        }
    }

    suspend fun closeChannel(
        channel: ChannelDetails,
        force: Boolean = false,
        forceCloseReason: String? = null,
    ): Result<Unit> = executeWhenNodeRunning("closeChannel") {
        runCatching { lightningService.closeChannel(channel, force, forceCloseReason) }.also {
            syncState()
        }
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

    fun getNodeId(): String? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.nodeId else null

    fun getBalances(): BalanceDetails? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.balances else null

    suspend fun getBalancesAsync(): Result<BalanceDetails> = executeWhenNodeRunning("getBalancesAsync") {
        lightningService.balances?.let { Result.success(it) }
            ?: Result.failure(AppError("Balances not available"))
    }

    suspend fun getChannelsAsync(): Result<List<ChannelDetails>> = executeWhenNodeRunning("getChannelsAsync") {
        lightningService.channels?.let { Result.success(it) }
            ?: Result.failure(AppError("Channels not available"))
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

    fun separateTrustedChannels(channels: List<ChannelDetails>) = lightningService.separateTrustedChannels(channels)

    suspend fun registerForNotifications(token: String? = null) = executeWhenNodeRunning("registerForNotifications") {
        runCatching {
            val token = token ?: firebaseMessaging.token.await()
            val cachedToken = keychain.loadString(Keychain.Key.PUSH_NOTIFICATION_TOKEN.name)

            require(token.isNotEmpty()) { "FCM token is empty or null" }

            if (cachedToken == token) {
                Logger.debug("registerForNotifications skipped, device token already registered")
                return@executeWhenNodeRunning Result.success(Unit)
            }

            lspNotificationsService.registerDevice(token)
        }.onFailure {
            Logger.error("registerForNotifications error", it, context = TAG)
        }
    }

    fun registerForNotificationsAsync(token: String) = scope.launch { registerForNotifications(token) }

    suspend fun bumpFeeByRbf(
        originalTxId: Txid,
        satsPerVByte: ULong,
    ): Result<Txid> = executeWhenNodeRunning("bumpFeeByRbf") {
        runCatching {
            require(!originalTxId.isBlank()) { "originalTxId is null or empty: $originalTxId" }
            require(satsPerVByte > 0u) { "satsPerVByte invalid: $satsPerVByte" }

            val replacementTxId = lightningService.bumpFeeByRbf(
                txid = originalTxId,
                satsPerVByte = satsPerVByte,
            )
            Logger.debug(
                "bumpFeeByRbf success, " +
                    "replacementTxId: $replacementTxId " +
                    "originalTxId: $originalTxId, " +
                    "satsPerVByte: $satsPerVByte",
                context = TAG,
            )
            return@runCatching replacementTxId
        }.onFailure {
            Logger.error(
                "bumpFeeByRbf error originalTxId: $originalTxId, satsPerVByte: $satsPerVByte",
                it,
                context = TAG,
            )
        }
    }

    suspend fun accelerateByCpfp(
        originalTxId: Txid,
        satsPerVByte: ULong,
        destinationAddress: Address,
    ): Result<Txid> = executeWhenNodeRunning("accelerateByCpfp") {
        runCatching {
            require(!originalTxId.isBlank()) { "originalTxId is null or empty: $originalTxId" }
            require(!destinationAddress.isBlank()) { "destinationAddress is null or empty: $destinationAddress" }
            require(satsPerVByte > 0u) { "satsPerVByte invalid: $satsPerVByte" }

            val newDestinationTxId = lightningService.accelerateByCpfp(
                txid = originalTxId,
                satsPerVByte = satsPerVByte,
                toAddress = destinationAddress,
            )
            Logger.debug(
                "accelerateByCpfp success, " +
                    "newDestinationTxId: $newDestinationTxId " +
                    "originalTxId: $originalTxId, " +
                    "satsPerVByte: $satsPerVByte " +
                    "destinationAddress: $destinationAddress"
            )
            return@runCatching newDestinationTxId
        }.onFailure {
            Logger.error(
                "accelerateByCpfp error: " +
                    "originalTxId: $originalTxId, " +
                    "satsPerVByte: $satsPerVByte, " +
                    "destinationAddress: $destinationAddress",
                it,
                context = TAG,
            )
        }
    }

    suspend fun estimateRoutingFees(bolt11: String): Result<ULong> = executeWhenNodeRunning("estimateRoutingFees") {
        Logger.info("Estimating routing fees for bolt11: $bolt11", context = TAG)
        lightningService.estimateRoutingFees(bolt11).onSuccess {
            Logger.info("Routing fees estimated: '$it'", context = TAG)
        }.onFailure {
            Logger.error("estimateRoutingFees error", it, context = TAG)
        }
    }

    suspend fun estimateRoutingFeesForAmount(bolt11: String, amountSats: ULong): Result<ULong> =
        executeWhenNodeRunning("estimateRoutingFeesForAmount") {
            Logger.info("Estimating routing fees for amount: '$amountSats'", context = TAG)
            lightningService.estimateRoutingFeesForAmount(bolt11, amountSats).onSuccess {
                Logger.info("Routing fees estimated: '$it'", context = TAG)
            }.onFailure {
                Logger.error("estimateRoutingFeesForAmount error", it, context = TAG)
            }
        }

    // region debug
    fun getNetworkGraphInfo() = lightningService.getNetworkGraphInfo()

    suspend fun exportNetworkGraphToFile(outputDir: String): Result<java.io.File> =
        executeWhenNodeRunning("exportNetworkGraphToFile") {
            lightningService.exportNetworkGraphToFile(outputDir)
        }
    // endregion

    // region probing
    suspend fun sendProbeForInvoice(bolt11: String, amountSats: ULong? = null): Result<Unit> =
        executeWhenNodeRunning("sendProbeForInvoice") {
            runCatching {
                val invoice = Bolt11Invoice.fromStr(bolt11)
                if (amountSats != null) {
                    val amountMsat = amountSats * 1000u
                    lightningService.sendProbesUsingAmount(invoice, amountMsat)
                } else {
                    lightningService.sendProbes(invoice)
                }
            }.getOrElse {
                Result.failure(it)
            }
        }
    // endregion

    suspend fun restartNode(): Result<Unit> = withContext(bgDispatcher) {
        Logger.info("Restarting node", context = TAG)
        stop().onFailure {
            Logger.error("Failed to stop node during restart", it, context = TAG)
            return@withContext Result.failure(it)
        }
        start(shouldRetry = false).onFailure {
            Logger.error("Failed to start node during restart", it, context = TAG)
            return@withContext Result.failure(it)
        }.onSuccess {
            Logger.info("Node restarted successfully", context = TAG)
        }
    }

    companion object {
        private const val TAG = "LightningRepo"
        private const val LENGTH_CHANNEL_ID_PREVIEW = 10
        private const val MS_SYNC_LOOP_DEBOUNCE = 500L
        private const val SYNC_RETRY_DELAY_MS = 15_000L
    }
}

class RecoveryModeError : AppError("App in recovery mode, skipping node start")
class NodeSetupError : AppError("Unknown node setup error")
class NodeStopTimeoutError : AppError("Timeout waiting for node to stop")
class NodeRunTimeoutError(opName: String) : AppError("Timeout waiting for node to run and execute: '$opName'")
class GetPaymentsError : AppError("It wasn't possible get the payments")
class SyncUnhealthyError : AppError("Wallet sync failed before send")

data class LightningState(
    val nodeId: String = "",
    val nodeStatus: NodeStatus? = null,
    val nodeLifecycleState: NodeLifecycleState = NodeLifecycleState.Stopped,
    val peers: List<PeerDetails> = emptyList(),
    val channels: List<ChannelDetails> = emptyList(),
    val balances: BalanceDetails? = null,
    val isSyncingWallet: Boolean = false,
    val isGeoBlocked: Boolean = false,
    val lastSyncError: Throwable? = null,
    val lastSuccessfulSyncAt: Long? = null,
) {
    fun block(): BestBlock? = nodeStatus?.currentBestBlock

    /**
     * Returns true if the node has synced successfully at least once and the last sync didn't fail.
     * This is used to determine if critical operations like sending should be allowed.
     */
    val isSyncHealthy: Boolean
        get() = lastSyncError == null && lastSuccessfulSyncAt != null
}
