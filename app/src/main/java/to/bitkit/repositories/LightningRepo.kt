package to.bitkit.repositories

import androidx.compose.runtime.Stable
import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.LnurlException
import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.PreActivityMetadata
import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.createChannelRequestUrl
import com.synonym.bitkitcore.createWithdrawCallbackUrl
import com.synonym.bitkitcore.lnurlAuth
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.BestBlock
import org.lightningdevkit.ldknode.ChannelConfig
import org.lightningdevkit.ldknode.ChannelDataMigration
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.ClosureReason
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentHash
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.PeerDetails
import org.lightningdevkit.ldknode.SpendableUtxo
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.backup.VssBackupClientLdk
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Defaults
import to.bitkit.env.Env
import to.bitkit.ext.getSatsPerVByteFor
import to.bitkit.ext.nowMillis
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.toPeerDetailsList
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.models.ALL_ADDRESS_TYPE_STRINGS
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.NATIVE_WITNESS_TYPES
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.OpenChannelResult
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.safe
import to.bitkit.models.satsToMsat
import to.bitkit.models.toAddressType
import to.bitkit.models.toCoinSelectAlgorithm
import to.bitkit.models.toCoreNetwork
import to.bitkit.models.toSettingsString
import to.bitkit.services.AddressDerivationInfo
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.services.LnurlChannelResponse
import to.bitkit.services.LnurlService
import to.bitkit.services.LnurlWithdrawResponse
import to.bitkit.services.LspNotificationsService
import to.bitkit.services.NodeEventHandler
import to.bitkit.utils.AppError
import to.bitkit.models.WalletScope
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import to.bitkit.utils.UrlValidator
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

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
    private val vssBackupClientLdk: VssBackupClientLdk,
    private val urlValidator: UrlValidator,
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
    private val probeOutcomeCache = ConcurrentHashMap<PaymentId, ProbeOutcome>()
    private val probeOutcomeSignal = MutableSharedFlow<ProbeOutcome>(extraBufferCapacity = 64)

    private val syncMutex = Mutex()
    private val syncPending = AtomicBoolean(false)
    private val syncRetryJob = AtomicReference<Job?>(null)
    private val lifecycleMutex = Mutex()
    private val isChangingAddressType = AtomicBoolean(false)

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

                    if (_lightningState.value.nodeLifecycleState.isRunning()) {
                        connectToTrustedPeers()
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
        shouldValidateGraph: Boolean = true,
    ): Result<Unit> = withContext(bgDispatcher) {
        if (_isRecoveryMode.value) {
            return@withContext Result.failure(RecoveryModeError())
        }

        eventHandler?.let { _eventHandlers.add(it) }

        // Track retry state outside mutex to avoid deadlock (Mutex is non-reentrant)
        var shouldRetryStart = false
        var shouldRestartForGraphReset = false
        var initialLifecycleState: NodeLifecycleState

        val result = lifecycleMutex.withLock {
            initialLifecycleState = _lightningState.value.nodeLifecycleState
            if (initialLifecycleState.isRunningOrStarting()) {
                Logger.info("LDK node start skipped, lifecycle state: $initialLifecycleState", context = TAG)
                lightningService.startEventListener(::onEvent)
                return@withLock Result.success(Unit)
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
                        return@withLock setupResult
                    }
                }

                if (getStatus()?.isRunning == true) {
                    Logger.info("LDK node already running", context = TAG)
                    _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }
                    lightningService.startEventListener(::onEvent).onFailure {
                        Logger.warn("Failed to start event listener", it, context = TAG)
                        return@withLock Result.failure(it)
                    }
                    return@withLock Result.success(Unit)
                }

                lightningService.start(timeout, ::onEvent)

                _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }

                // Initial state sync
                syncState()
                logNodeSupportSummary("node started")
                updateGeoBlockState()
                refreshChannelCache()

                if (shouldValidateGraph && !lightningService.aresRequiredPeersInNetworkGraph()) {
                    Logger.warn("Network graph is stale, resetting and restarting...", context = TAG)

                    lightningService.stop()
                    clearNetworkGraph(walletIndex)

                    _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Stopped) }
                    shouldRestartForGraphReset = true
                    return@withLock Result.success(Unit)
                }

                // Post-startup tasks (non-blocking)
                connectToTrustedPeers().onFailure {
                    Logger.error("Failed to connect to trusted peers", it, context = TAG)
                }
                logNodeSupportSummary("trusted peers connected")

                sync().onFailure { e ->
                    Logger.warn("Initial sync failed, event-driven sync will retry", e, context = TAG)
                }
                scope.launch { registerForNotifications() }
                Result.success(Unit)
            }.getOrElse { e ->
                val currentState = _lightningState.value.nodeLifecycleState
                if (currentState.isRunning()) {
                    Logger.warn("Start error but node is $currentState, skipping retry", e, context = TAG)
                    return@withLock Result.success(Unit)
                }

                if (shouldRetry) {
                    Logger.warn("Start error, will retry...", e, context = TAG)
                    _lightningState.update { it.copy(nodeLifecycleState = initialLifecycleState) }
                    shouldRetryStart = true
                    Result.failure(e)
                } else {
                    _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.ErrorStarting(e)) }
                    Result.failure(e)
                }
            }
        }

        // Retry OUTSIDE the mutex to avoid deadlock (Kotlin Mutex is non-reentrant)
        if (shouldRetryStart) {
            delay(2.seconds)
            return@withContext start(
                walletIndex = walletIndex,
                timeout = timeout,
                shouldRetry = false,
                customServerUrl = customServerUrl,
                customRgsServerUrl = customRgsServerUrl,
                channelMigration = channelMigration,
                shouldValidateGraph = shouldValidateGraph,
            )
        }

        // Restart after graph reset OUTSIDE the mutex to avoid deadlock
        if (shouldRestartForGraphReset) {
            return@withContext start(
                walletIndex = walletIndex,
                timeout = timeout,
                shouldRetry = shouldRetry,
                customServerUrl = customServerUrl,
                customRgsServerUrl = customRgsServerUrl,
                eventHandler = eventHandler,
                channelMigration = channelMigration,
                shouldValidateGraph = false, // Prevent infinite loop
            )
        }

        result
    }

    fun removeEventHandler(handler: NodeEventHandler) {
        _eventHandlers.remove(handler)
    }

    private suspend fun onEvent(event: Event) {
        handleLdkEvent(event)
        recordProbeOutcome(event)
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
        lifecycleMutex.withLock {
            if (_lightningState.value.nodeLifecycleState.isStoppedOrStopping()) {
                clearProbeOutcomes()
                return@withLock Result.success(Unit)
            }

            runCatching {
                _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Stopping) }
                lightningService.stop()
                clearProbeOutcomes()
                _lightningState.update { LightningState(nodeLifecycleState = NodeLifecycleState.Stopped) }
            }.onFailure {
                Logger.error("Node stop error", it, context = TAG)
                // On failure, check actual node state and update accordingly
                // If node is still running, revert to Running state to allow retry
                if (lightningService.node != null && lightningService.status?.isRunning == true) {
                    Logger.warn("Stop failed but node is still running, reverting to Running state", context = TAG)
                    _lightningState.update { s -> s.copy(nodeLifecycleState = NodeLifecycleState.Running) }
                } else {
                    // Node appears stopped, update state
                    _lightningState.update { LightningState(nodeLifecycleState = NodeLifecycleState.Stopped) }
                }
            }
        }
    }

    suspend fun resetNetworkGraph(walletIndex: Int = 0): Result<Unit> = withContext(bgDispatcher) {
        Logger.warn("Resetting network graph (manual)", context = TAG)
        runCatching {
            if (lightningService.node != null) {
                lightningService.stop()
            }
            // Propagate VSS failures: a manual reset that leaves the graph in VSS is ineffective.
            clearNetworkGraph(walletIndex).getOrThrow()
        }
    }

    private suspend fun clearNetworkGraph(walletIndex: Int): Result<Unit> {
        lightningService.resetNetworkGraph(walletIndex)
        return runCatching {
            vssBackupClientLdk.setup(walletIndex).getOrThrow()
            vssBackupClientLdk.deleteObject("network_graph").getOrThrow()
            Logger.info("Cleared network graph from VSS", context = TAG)
        }.onFailure {
            Logger.warn("Failed to clear network graph from VSS", it, context = TAG)
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
            is Event.ChannelPending, is Event.ChannelReady -> scope.launch {
                refreshChannelCache()
                syncState()
            }

            is Event.ChannelClosed -> scope.launch { registerClosedChannel(event.channelId, event.reason) }
            else -> Unit
        }
    }

    private suspend fun recordProbeOutcome(event: Event) {
        val outcome = when (event) {
            is Event.ProbeSuccessful -> ProbeOutcome.Success(event.paymentId, event.paymentHash)
            is Event.ProbeFailed -> ProbeOutcome.Failure(event.paymentId, event.paymentHash, event.shortChannelId)
            else -> return
        }

        probeOutcomeCache[outcome.paymentId] = outcome
        probeOutcomeSignal.emit(outcome)
    }

    private fun clearProbeOutcomes() {
        probeOutcomeCache.clear()
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
            clearProbeOutcomes()
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

        validateRgsUrl(newRgsUrl).onFailure {
            Logger.warn("RGS server unreachable at '$newRgsUrl'", it, context = TAG)
            return@withContext Result.failure(it)
        }

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

    private suspend fun validateRgsUrl(url: String): Result<Unit> = withContext(bgDispatcher) {
        val initialTimestamp = 0
        val testUrl = "${url.trimEnd('/')}/$initialTimestamp"
        urlValidator.validate(testUrl)
    }

    suspend fun getBalanceForAddressType(addressType: AddressType): Result<ULong> = withContext(bgDispatcher) {
        executeWhenNodeRunning("getBalanceForAddressType") {
            runCatching {
                lightningService.getBalanceForAddressType(addressType).totalSats
            }
        }
    }

    suspend fun getChannelFundableBalance(): ULong = withContext(bgDispatcher) {
        val settings = settingsStore.data.first()
        val selectedType = settings.selectedAddressType.toAddressType()
        val monitoredTypes = settings.addressTypesToMonitor.mapNotNull { it.toAddressType() }
        val typesToSum = (listOfNotNull(selectedType) + monitoredTypes).distinct().filter { it != AddressType.P2PKH }

        if (typesToSum.isEmpty()) {
            return@withContext getBalancesAsync().getOrNull()?.spendableOnchainBalanceSats ?: 0uL
        }

        var total = 0uL
        for (type in typesToSum) {
            val balance = executeWhenNodeRunning("getBalanceForAddressType") {
                runCatching { lightningService.getBalanceForAddressType(type).spendableSats }
            }.getOrNull()
            if (balance == null) {
                return@withContext getBalancesAsync().getOrNull()?.spendableOnchainBalanceSats ?: 0uL
            }
            total = total.safe() + balance.safe()
        }
        total
    }

    suspend fun updateAddressType(
        selectedType: String,
        monitoredTypes: List<String>,
    ): Result<Unit> = withContext(bgDispatcher) {
        if (!isChangingAddressType.compareAndSet(false, true)) {
            return@withContext Result.failure(AppError("Address type change already in progress"))
        }

        val previousSettings = settingsStore.data.first()
        val oldSelected = previousSettings.selectedAddressType
        val oldMonitored = previousSettings.addressTypesToMonitor
        val addressType = selectedType.toAddressType() ?: AddressType.P2WPKH

        suspend fun rollback() =
            settingsStore.update { it.copy(selectedAddressType = oldSelected, addressTypesToMonitor = oldMonitored) }

        runCatching {
            settingsStore.update {
                it.copy(selectedAddressType = selectedType, addressTypesToMonitor = monitoredTypes)
            }
            lightningService.setPrimaryAddressType(addressType)
            syncMonitoredTypesFromNode()
            sync().onFailure { Logger.warn("Sync after address type change failed", it, context = TAG) }
            Unit
        }.onFailure {
            rollback()
            Logger.error("updateAddressType failed", it, context = TAG)
        }.also {
            isChangingAddressType.set(false)
        }
    }

    suspend fun setMonitoring(addressType: AddressType, enabled: Boolean): Result<Unit> = withContext(bgDispatcher) {
        if (!isChangingAddressType.compareAndSet(false, true)) {
            return@withContext Result.failure(AppError("Address type change already in progress"))
        }

        val previousSettings = settingsStore.data.first()
        val oldMonitored = previousSettings.addressTypesToMonitor.toList()

        if (!enabled) {
            val validationError = validateDisableMonitoring(addressType, previousSettings, oldMonitored)
            if (validationError != null) {
                isChangingAddressType.set(false)
                return@withContext Result.failure(validationError)
            }
        }

        val typeStr = addressType.toSettingsString()
        val newMonitored = if (enabled) (oldMonitored + typeStr).distinct() else oldMonitored.filter { it != typeStr }

        suspend fun rollback() = settingsStore.update { it.copy(addressTypesToMonitor = oldMonitored) }

        runCatching {
            settingsStore.update { it.copy(addressTypesToMonitor = newMonitored) }
            if (enabled) {
                lightningService.addAddressTypeToMonitor(addressType)
            } else {
                lightningService.removeAddressTypeFromMonitor(addressType)
            }
            sync().onFailure { Logger.warn("Sync after monitoring change failed", it, context = TAG) }
            Unit
        }.onFailure {
            rollback()
            Logger.error("setMonitoring failed", it, context = TAG)
        }.also {
            isChangingAddressType.set(false)
        }
    }

    private suspend fun validateDisableMonitoring(
        addressType: AddressType,
        settings: SettingsData,
        monitoredTypes: List<String>,
    ): AppError? {
        if (addressType == settings.selectedAddressType.toAddressType()) {
            return AppError("Cannot disable monitoring: address type is currently selected")
        }
        if (isLastRequiredNativeWitnessWallet(addressType, monitoredTypes)) {
            return AppError(
                "Cannot disable monitoring: at least one Native SegWit or Taproot wallet required for Lightning"
            )
        }
        val balance = getBalanceForAddressType(addressType).getOrElse {
            return AppError("Cannot disable monitoring: failed to verify balance")
        }
        if (balance > 0uL) {
            return AppError("Cannot disable monitoring: address type has balance")
        }
        return null
    }

    private suspend fun syncMonitoredTypesFromNode() {
        runCatching {
            val nodeMonitored = lightningService.listMonitoredAddressTypes()
            val settings = settingsStore.data.first()
            val selectedType = settings.selectedAddressType.toAddressType() ?: AddressType.P2WPKH
            val combined = (nodeMonitored + selectedType).distinct()
            val allOrdered = ALL_ADDRESS_TYPE_STRINGS
            val newMonitored = allOrdered.filter { typeStr ->
                typeStr.toAddressType() in combined
            }
            settingsStore.update { it.copy(addressTypesToMonitor = newMonitored) }
        }.onFailure {
            Logger.warn("syncMonitoredTypesFromNode failed", it, context = TAG)
        }
    }

    fun isChangingAddressType(): Boolean = isChangingAddressType.get()

    suspend fun pruneEmptyAddressTypesAfterRestore(): Result<Unit> = withContext(bgDispatcher) {
        if (isChangingAddressType.get()) return@withContext Result.success(Unit)

        val settings = settingsStore.data.first()
        val selectedType = settings.selectedAddressType.toAddressType() ?: AddressType.P2WPKH
        val monitored = settings.addressTypesToMonitor.toMutableList()

        val toRemove = monitored.filter { typeStr ->
            if (typeStr == settings.selectedAddressType) return@filter false
            val type = typeStr.toAddressType() ?: return@filter false
            val balance = getBalanceForAddressType(type).getOrNull() ?: return@filter false
            if (balance != 0uL) return@filter false
            val wouldLeaveNativeWitness = (selectedType in NATIVE_WITNESS_TYPES) ||
                monitored.any { it != typeStr && it.toAddressType() in NATIVE_WITNESS_TYPES }
            wouldLeaveNativeWitness
        }

        if (toRemove.isEmpty()) return@withContext Result.success(Unit)

        val newMonitored = monitored.filter { it !in toRemove }
        settingsStore.update { it.copy(addressTypesToMonitor = newMonitored) }
        for (typeStr in toRemove) {
            val type = typeStr.toAddressType() ?: continue
            runCatching { lightningService.removeAddressTypeFromMonitor(type) }.onFailure {
                Logger.error("Failed to remove address type $typeStr from monitor", it, context = TAG)
            }
        }
        sync().onFailure { Logger.warn("Sync after prune failed", it, context = TAG) }
        Result.success(Unit)
    }

    private fun isLastRequiredNativeWitnessWallet(addressType: AddressType, monitoredTypes: List<String>): Boolean {
        if (addressType !in NATIVE_WITNESS_TYPES) return false
        val monitored = monitoredTypes.mapNotNull { it.toAddressType() }
        val remaining = monitored.filter { it != addressType && it in NATIVE_WITNESS_TYPES }
        return remaining.isEmpty()
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
        runCatching { lightningService.connectToTrustedPeers() }.also {
            syncState()
        }
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

    suspend fun newAddressForType(addressType: AddressType): Result<String> =
        executeWhenNodeRunning("newAddressForType") {
            runCatching { lightningService.newAddressForType(addressType) }
        }

    suspend fun newAddressInfoForType(addressType: AddressType): Result<AddressDerivationInfo> =
        executeWhenNodeRunning("newAddressInfoForType") {
            runCatching { lightningService.newAddressInfoForType(addressType) }
        }

    suspend fun addressInfoForType(addressType: AddressType, receiveIndex: Int): Result<AddressDerivationInfo> =
        executeWhenNodeRunning("addressInfoForType") {
            runCatching { lightningService.addressInfoForType(addressType, receiveIndex) }
        }

    suspend fun addressInfosForType(
        addressType: AddressType,
        isChange: Boolean,
        startIndex: Int,
        count: Int,
    ): Result<List<AddressDerivationInfo>> =
        executeWhenNodeRunning("addressInfosForType") {
            runCatching { lightningService.addressInfosForType(addressType, isChange, startIndex, count) }
        }

    suspend fun revealReceiveAddresses(toReceiveIndex: Int, forType: AddressType): Result<Unit> =
        executeWhenNodeRunning("revealReceiveAddresses") {
            runCatching { lightningService.revealReceiveAddresses(toReceiveIndex, forType) }
        }

    suspend fun createInvoice(
        amountSats: ULong? = null,
        description: String,
        expirySeconds: UInt = Defaults.bolt11ExpirySec,
    ): Result<String> = executeWhenNodeRunning("createInvoice") {
        updateGeoBlockState()
        runCatching { lightningService.receive(amountSats, description, expirySeconds) }
    }

    suspend fun createInvoiceMsats(
        amountMsats: ULong,
        description: String,
        expirySeconds: UInt = Defaults.bolt11ExpirySec,
    ): Result<String> = executeWhenNodeRunning("createInvoiceMsats") {
        updateGeoBlockState()
        runCatching { lightningService.receiveMsats(amountMsats, description, expirySeconds) }
    }

    suspend fun fetchLnurlInvoice(
        data: LnurlPayData,
        amountMsats: ULong,
        comment: String? = null,
    ): Result<LightningInvoice> {
        return runCatching {
            val bolt11 = coreService.getLnurlInvoiceForPayData(data, amountMsats, comment)
            val decoded = (coreService.decode(bolt11) as Scanner.Lightning).invoice
            return@runCatching decoded
        }.recoverCatching {
            throw it.toLnurlPayInvoiceError()
        }.onFailure {
            Logger.error(
                "Failed to fetch LNURL invoice, uri: '${data.uri}', amountMsats: '$amountMsats', comment: '$comment'",
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
        waitForUsableChannels()
        runCatching { lightningService.send(bolt11, sats) }.also {
            syncState()
        }
    }

    suspend fun waitForUsableChannels() = withContext(bgDispatcher) {
        var state = _lightningState.value
        if (!state.nodeLifecycleState.canRun()) {
            delayNoUsableChannelsFeedback()
            return@withContext
        }
        if (state.hasUsableChannels()) return@withContext

        state = waitForChannelsToLoadIfNeeded(state) ?: return@withContext
        if (!state.nodeLifecycleState.canRun()) {
            delayNoUsableChannelsFeedback()
            return@withContext
        }

        if (state.channels.isEmpty()) {
            if (state.nodeLifecycleState.isRunning()) {
                syncState()
                state = _lightningState.value
            }

            if (state.channels.isEmpty()) {
                delayNoUsableChannelsFeedback()
                return@withContext
            }
            if (state.hasUsableChannels()) return@withContext
        }

        Logger.info("Waiting for usable channels before sending payment", context = TAG)

        val finalState = withTimeoutOrNull(CHANNELS_USABLE_TIMEOUT) {
            _lightningState.first { it.shouldStopWaitingForUsableChannels() }
        } ?: run {
            Logger.warn("Timed out waiting for usable channels", context = TAG)
            return@withContext
        }

        if (!finalState.nodeLifecycleState.canRun() || finalState.channels.isEmpty()) {
            delayNoUsableChannelsFeedback()
        }
    }

    private suspend fun waitForChannelsToLoadIfNeeded(state: LightningState): LightningState? {
        if (state.channels.isNotEmpty() || state.nodeLifecycleState.isRunning()) return state

        Logger.info("Waiting for node to load channels before sending payment", context = TAG)
        return withTimeoutOrNull(CHANNELS_USABLE_TIMEOUT) {
            _lightningState.first { it.shouldStopWaitingForLoadedChannels() }
        } ?: run {
            Logger.warn("Timed out waiting for node to load channels", context = TAG)
            null
        }
    }

    private fun LightningState.hasUsableChannels() = channels.any { it.isUsable }

    private fun LightningState.shouldStopWaitingForLoadedChannels() =
        !nodeLifecycleState.canRun() || nodeLifecycleState.isRunning() || channels.isNotEmpty()

    private fun LightningState.shouldStopWaitingForUsableChannels() =
        !nodeLifecycleState.canRun() || channels.isEmpty() || hasUsableChannels()

    private suspend fun delayNoUsableChannelsFeedback() {
        delay(NO_USABLE_CHANNELS_FEEDBACK_DELAY)
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

        Logger.debug(
            "sendOnChain: sats=$sats, isTransfer=$isTransfer, isMaxAmount=$isMaxAmount, satsPerVByte=$satsPerVByte",
            context = TAG,
        )

        // transfer send-all: skip UTXO selection to avoid LDK buffer; else use passed or auto-selected
        val utxosForSend = when {
            isTransfer && isMaxAmount -> null
            else -> utxosToSpend ?: determineUtxosToSpend(sats, satsPerVByte)
        }

        Logger.debug("UTXOs selected to spend: $utxosForSend", context = TAG)

        val txId = lightningService.send(address, sats, satsPerVByte, utxosForSend, isMaxAmount)

        val preActivityMetadata = PreActivityMetadata(
            walletId = WalletScope.default,
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

        coreService.activity.createSentOnchainActivityFromSendResult(
            txid = txId,
            address = address,
            amount = sats,
            fee = 0u,
            feeRate = satsPerVByte,
            isTransfer = isTransfer,
            channelId = channelId,
        )

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
        val payments = lightningService.listPayments()
            ?: return@executeWhenNodeRunning Result.failure(GetPaymentsError())
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
            Logger.warn("calculateTotalFee error, using fallback of '$fallbackFee'", it, context = TAG)
            return@recoverCatching fallbackFee
        }
    }

    /** Estimates the fee for a send-all (drain) transaction */
    suspend fun estimateSendAllFee(
        address: Address? = null,
        speed: TransactionSpeed? = null,
        feeRates: FeeRates? = null,
    ): Result<ULong> = withContext(bgDispatcher) {
        runCatching {
            val transactionSpeed = speed ?: settingsStore.data.first().defaultTransactionSpeed
            val satsPerVByte = getFeeRateForSpeed(transactionSpeed, feeRates).getOrThrow()
            val addressOrDefault = address ?: cacheStore.data.first().onchainAddress
            lightningService.estimateSendAllFee(address = addressOrDefault, satsPerVByte = satsPerVByte)
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
                peers = getPeers().orEmpty().toImmutableList(),
                channels = getChannels().orEmpty().toImmutableList(),
                balances = getBalances(),
            )
        }
    }

    private fun logNodeSupportSummary(reason: String) {
        val state = _lightningState.value
        val connectedPeers = state.peers.count { it.isConnected }
        val persistedPeers = state.peers.count { it.isPersisted }
        val readyChannels = state.channels.count { it.isChannelReady }
        val usableChannels = state.channels.count { it.isUsable }

        Logger.info(
            "Collected node support summary for '$reason': " +
                "nodeId='${state.nodeId}', " +
                "lifecycle='${state.nodeLifecycleState}', " +
                "peers='${state.peers.size}', " +
                "connectedPeers='$connectedPeers', " +
                "persistedPeers='$persistedPeers', " +
                "channels='${state.channels.size}', " +
                "readyChannels='$readyChannels', " +
                "usableChannels='$usableChannels'",
            context = TAG,
        )

        state.peers.forEach {
            Logger.info(
                "Collected peer support summary for '$reason': " +
                    "nodeId='${it.nodeId}', " +
                    "address='${it.address}', " +
                    "connected='${it.isConnected}', " +
                    "persisted='${it.isPersisted}'",
                context = TAG,
            )
        }

        state.channels.forEach {
            Logger.info(
                "Collected channel support summary for '$reason': " +
                    "channelId='${it.channelId}', " +
                    "counterparty='${it.counterpartyNodeId}', " +
                    "ready='${it.isChannelReady}', " +
                    "usable='${it.isUsable}', " +
                    "announced='${it.isAnnounced}', " +
                    "outboundMsat='${it.outboundCapacityMsat}', " +
                    "inboundMsat='${it.inboundCapacityMsat}'",
                context = TAG,
            )
        }
    }

    suspend fun awaitPeerConnected(timeout: Duration = 30.seconds) = withContext(bgDispatcher) {
        if (lightningService.peers?.any { it.isConnected } == true) return@withContext
        Logger.debug("Waiting for peer to reconnect (timeout='$timeout')...", context = TAG)
        withTimeoutOrNull(timeout) {
            while (lightningService.peers?.any { it.isConnected } != true) {
                delay(1.seconds)
            }
        }
    }

    fun canSend(amountSats: ULong): Boolean {
        val state = _lightningState.value
        if (!state.nodeLifecycleState.canRun()) return false
        return state.channels.totalNextOutboundHtlcLimitSats() >= amountSats
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

    suspend fun exportNetworkGraphToFile(outputDir: String): Result<File> =
        executeWhenNodeRunning("exportNetworkGraphToFile") {
            lightningService.exportNetworkGraphToFile(outputDir)
        }
    // endregion

    // region probing
    suspend fun sendProbeForInvoice(bolt11: String, amountSats: ULong? = null): Result<ProbeDispatch> =
        executeWhenNodeRunning("sendProbeForInvoice") {
            Logger.debug(
                "sendProbeForInvoice: amountSats='${amountSats ?: "null (using invoice amount)"}'",
                context = TAG,
            )
            val result = if (amountSats != null) {
                val amountMsat = satsToMsat(amountSats)
                lightningService.sendProbesUsingAmount(bolt11, amountMsat)
            } else {
                lightningService.sendProbes(bolt11)
            }

            result.map { ProbeDispatch(paymentIds = it) }
        }

    suspend fun sendProbeForNode(nodeId: String, amountSats: ULong): Result<ProbeDispatch> =
        executeWhenNodeRunning("sendProbeForNode") {
            Logger.debug(
                "Sending keysend probe to nodeId='$nodeId' amountSats='$amountSats'",
                context = TAG,
            )
            val amountMsat = satsToMsat(amountSats)
            lightningService.sendKeysendProbe(nodeId, amountMsat).map {
                ProbeDispatch(paymentIds = it)
            }
        }

    suspend fun waitForProbeOutcome(
        paymentIds: Set<PaymentId>,
        timeout: Duration = PROBE_TIMEOUT,
    ): Result<ProbeOutcome> = withContext(bgDispatcher) {
        if (paymentIds.isEmpty()) {
            return@withContext Result.failure(ProbeError.NoProbeHandles())
        }

        val trackedIds = paymentIds.toSet()
        val outcome = withTimeoutOrNull(timeout) {
            val pending = trackedIds.toMutableSet()
            var lastFailure: ProbeOutcome.Failure? = null

            probeOutcomeSignal
                .onSubscription {
                    trackedIds.forEach { id ->
                        probeOutcomeCache[id]?.let { emit(it) }
                    }
                }
                .filter { it.paymentId in trackedIds }
                .mapNotNull { probeOutcome ->
                    if (!pending.remove(probeOutcome.paymentId)) return@mapNotNull null

                    probeOutcomeCache.remove(probeOutcome.paymentId)
                    when (probeOutcome) {
                        is ProbeOutcome.Success -> probeOutcome
                        is ProbeOutcome.Failure -> {
                            lastFailure = probeOutcome
                            if (pending.isEmpty()) lastFailure else null
                        }
                    }
                }
                .first()
        }

        trackedIds.forEach { probeOutcomeCache.remove(it) }

        outcome?.let { Result.success(it) }
            ?: Result.failure(ProbeError.TimedOut())
    }

    fun probeReadiness(): ProbeReadiness {
        val state = _lightningState.value
        val graph = getNetworkGraphInfo()
        return ProbeReadiness(
            nodeRunning = state.nodeLifecycleState.isRunning(),
            nodeId = state.nodeId.takeIf { it.isNotBlank() },
            lifecycle = state.nodeLifecycleState.toString(),
            peers = state.peers.size,
            connectedPeers = state.peers.count { it.isConnected },
            channels = state.channels.size,
            readyChannels = state.channels.count { it.isChannelReady },
            usableChannels = state.channels.count { it.isUsable },
            outboundCapacitySats = state.channels.totalNextOutboundHtlcLimitSats(),
            graphNodeCount = graph?.nodeCount,
            graphChannelCount = graph?.channelCount,
            latestRgsSyncTimestamp = graph?.latestRgsSyncTimestamp,
            latestPathfindingScoresSyncTimestamp = state.nodeStatus?.latestPathfindingScoresSyncTimestamp,
            syncHealthy = state.isSyncHealthy,
        )
    }

    /**
     * Returns the device epoch seconds captured after the VSS deletes and before the node restart,
     * so callers can require any scores sync timestamp to be strictly newer to prove a post-reset download.
     */
    @OptIn(ExperimentalTime::class)
    suspend fun resetPathfindingScores(walletIndex: Int = 0): Result<Long> = withContext(bgDispatcher) {
        Logger.info("Resetting pathfinding scores", context = TAG)

        waitForNodeToStop().onFailure { return@withContext Result.failure(it) }
        stop().onFailure {
            Logger.error("Failed to stop node during pathfinding scores reset", it, context = TAG)
            return@withContext Result.failure(it)
        }

        runCatching {
            val lifecycleState = _lightningState.value.nodeLifecycleState
            check(lifecycleState == NodeLifecycleState.Stopped) {
                "Node lifecycle changed to '$lifecycleState' during pathfinding scores reset"
            }
            vssBackupClientLdk.setup(walletIndex).getOrThrow()
            vssBackupClientLdk.deleteObject(VSS_KEY_SCORER).getOrThrow()
            vssBackupClientLdk.deleteObject(VSS_KEY_EXTERNAL_SCORES_CACHE).getOrThrow()
        }.onFailure {
            Logger.error("Failed to delete pathfinding scores from VSS", it, context = TAG)
            start(walletIndex = walletIndex, shouldRetry = false).onFailure { startError ->
                Logger.error("Failed to restart node after pathfinding scores reset failure", startError, context = TAG)
            }
            return@withContext Result.failure(it)
        }

        val resetAtSecs = nowMillis() / 1000

        start(walletIndex = walletIndex, shouldRetry = false)
            .map { resetAtSecs }
            .onSuccess {
                Logger.info("Pathfinding scores reset at '$resetAtSecs'", context = TAG)
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
        private const val VSS_KEY_SCORER = "scorer"
        private const val VSS_KEY_EXTERNAL_SCORES_CACHE = "external_pathfinding_scores_cache"
        private const val MS_SYNC_LOOP_DEBOUNCE = 500L
        private const val SYNC_RETRY_DELAY_MS = 15_000L
        private val CHANNELS_USABLE_TIMEOUT = 15.seconds
        private val NO_USABLE_CHANNELS_FEEDBACK_DELAY = 2_500.milliseconds
        val SEND_LN_TIMEOUT = 10.seconds
        private val PROBE_TIMEOUT = 60.seconds
    }
}

class RecoveryModeError : AppError("App in recovery mode, skipping node start")
class NodeSetupError : AppError("Unknown node setup error")
class NodeStopTimeoutError : AppError("Timeout waiting for node to stop")
class NodeRunTimeoutError(opName: String) : AppError("Timeout waiting for node to run and execute: '$opName'")
class GetPaymentsError : AppError("It wasn't possible get the payments")
class SyncUnhealthyError : AppError("Wallet sync failed before send")
class LnurlPayInvoiceMismatchError : AppError("The invoice did not match the requested payment. Payment cancelled.")
sealed class ProbeError(message: String) : AppError(message) {
    class NoProbeHandles : ProbeError("No probe handles returned")
    class TimedOut : ProbeError("Probe timed out")
}

private fun Throwable.toLnurlPayInvoiceError(): Throwable {
    val lnurlPayValidationError = generateSequence(this) { it.cause }
        .firstOrNull { it.isLnurlPayValidationError() }

    return if (lnurlPayValidationError != null) LnurlPayInvoiceMismatchError() else this
}

private fun Throwable.isLnurlPayValidationError(): Boolean = when (this) {
    is LnurlException.InvalidAmount,
    is LnurlException.AmountMismatch -> true

    else -> false
}

@Stable
data class LightningState(
    val nodeId: String = "",
    val nodeStatus: NodeStatus? = null,
    val nodeLifecycleState: NodeLifecycleState = NodeLifecycleState.Stopped,
    val peers: ImmutableList<PeerDetails> = persistentListOf(),
    val channels: ImmutableList<ChannelDetails> = persistentListOf(),
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

data class ProbeDispatch(
    val paymentIds: Set<PaymentId>,
)

data class ProbeReadiness(
    val nodeRunning: Boolean,
    val nodeId: String?,
    val lifecycle: String,
    val peers: Int,
    val connectedPeers: Int,
    val channels: Int,
    val readyChannels: Int,
    val usableChannels: Int,
    val outboundCapacitySats: ULong,
    val graphNodeCount: Int?,
    val graphChannelCount: Int?,
    val latestRgsSyncTimestamp: ULong?,
    val latestPathfindingScoresSyncTimestamp: ULong?,
    val syncHealthy: Boolean,
) {
    val ready: Boolean
        get() = nodeRunning &&
            connectedPeers > 0 &&
            usableChannels > 0 &&
            outboundCapacitySats > 0u &&
            (graphChannelCount ?: 0) > 0 &&
            syncHealthy
}

sealed interface ProbeOutcome {
    val paymentId: PaymentId
    val paymentHash: PaymentHash

    data class Success(
        override val paymentId: PaymentId,
        override val paymentHash: PaymentHash,
    ) : ProbeOutcome

    data class Failure(
        override val paymentId: PaymentId,
        override val paymentHash: PaymentHash,
        val shortChannelId: ULong?,
    ) : ProbeOutcome
}
