package to.bitkit.repositories

import androidx.compose.runtime.Stable
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.BestBlock
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.ChannelConfig
import org.lightningdevkit.ldknode.ChannelDataMigration
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.ClosureReason
import org.lightningdevkit.ldknode.CoinSelectionAlgorithm
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.OutPoint
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentHash
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.lightningdevkit.ldknode.PeerDetails
import org.lightningdevkit.ldknode.SpendableUtxo
import org.lightningdevkit.ldknode.Txid
import to.bitkit.async.appScope
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.backup.VssBackupClientLdk
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.requireWalletRestoreComplete
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Defaults
import to.bitkit.env.Env
import to.bitkit.ext.calculateRemoteBalance
import to.bitkit.ext.getSatsPerVByteFor
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.models.ALL_ADDRESS_TYPE_STRINGS
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.DEFAULT_ADDRESS_TYPE_STRING
import to.bitkit.models.ElectrumServer
import to.bitkit.models.NATIVE_WITNESS_TYPES
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.OpenChannelResult
import to.bitkit.models.ReceiveLiquidityDecision
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.WalletScope
import to.bitkit.models.safe
import to.bitkit.models.satsToMsat
import to.bitkit.models.toAddressType
import to.bitkit.models.toCoinSelectAlgorithm
import to.bitkit.models.toCoreNetwork
import to.bitkit.models.toSettingsString
import to.bitkit.services.AddressDerivationInfo
import to.bitkit.services.CoreService
import to.bitkit.services.ElectrumProbeService
import to.bitkit.services.LightningService
import to.bitkit.services.LnurlChannelResponse
import to.bitkit.services.LnurlService
import to.bitkit.services.LnurlWithdrawResponse
import to.bitkit.services.NetworkGraphInfo
import to.bitkit.services.NodeEventHandler
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import to.bitkit.utils.UrlValidator
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Singleton
class LightningRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningService: LightningService,
    private val settingsStore: SettingsStore,
    private val coreService: CoreService,
    private val keychain: Keychain,
    private val lnurlService: LnurlService,
    private val cacheStore: CacheStore,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    private val onchainSendAttemptStore: OnchainSendAttemptStore,
    private val connectivityRepo: ConnectivityRepo,
    private val vssBackupClientLdk: VssBackupClientLdk,
    private val urlValidator: UrlValidator,
    private val electrumProbeService: ElectrumProbeService,
    private val sparkRepo: SparkRepo,
) : OnchainReleasing {
    private val _lightningState = MutableStateFlow(LightningState())
    val lightningState = _lightningState.asStateFlow()

    private val _nodeEventUpdates = MutableSharedFlow<NodeEventUpdate>(extraBufferCapacity = 64)
    val nodeEventUpdates = _nodeEventUpdates.asSharedFlow()
    val nodeEvents = nodeEventUpdates.map { it.event }

    /** Whether Spark is connected and can send and receive Lightning payments. */
    val isSparkReady: Flow<Boolean> get() = sparkRepo.state.map { it.isReady }.distinctUntilChanged()

    /** Whether Spark synced its payments since it connected, so its payment list is complete. */
    val isSparkSynced: Flow<Boolean> get() = sparkRepo.state.map { it.hasSynced }.distinctUntilChanged()

    private val scope = appScope(bgDispatcher, TAG)

    private val _eventHandlers = ConcurrentHashMap.newKeySet<NodeEventHandler>()
    private val _isRecoveryMode = MutableStateFlow(false)
    val isRecoveryMode = _isRecoveryMode.asStateFlow()

    private val _lastKnownChannels = MutableStateFlow<ImmutableList<ChannelDetails>>(persistentListOf())

    /** Channels from the last read of a running node, kept while the node stops and restarts. */
    val lastKnownChannels = _lastKnownChannels.asStateFlow()

    @Volatile
    private var isWiping = false

    /** The rail each Lightning send last went out on, by payment hash, so a retry settles only from its own rail. */
    private val sendRails = ConcurrentHashMap<String, LightningRail>()

    /** Node sends under way, kept so a listing that shows only an earlier failed attempt doesn't drop its hash. */
    private val ldkSendsInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Counts node sends recorded, so a listing drops a failed hash only when it was recorded before the listing. */
    private val ldkSendRecords = AtomicLong()
    private val ldkSendRecordedAt = ConcurrentHashMap<String, Long>()

    /** The rail holding each invoice's admitted sends and their count, by send key, guarded by itself. */
    private val admittedSends = HashMap<String, Pair<LightningRail, Int>>()

    @Volatile
    private var lastKnownNodeId: String? = null

    private val channelCache = ConcurrentHashMap<String, ChannelDetails>()
    private val probeOutcomeCache = ConcurrentHashMap<PaymentId, ProbeOutcome>()
    private val probeOutcomeSignal = MutableSharedFlow<ProbeOutcome>(extraBufferCapacity = 64)

    private val syncMutex = Mutex()
    private val syncPending = AtomicBoolean(false)
    private val syncRetryJob = AtomicReference<Job?>(null)
    private val ordinaryFollowupRetryJob = AtomicReference<Job?>(null)
    private val pendingStopJob = AtomicReference<Job?>(null)
    private val pendingStopLock = Any()
    private val lifecycleMutex = Mutex()

    /**
     * Serializes a whole server-change transaction (stop, start, persist) against the background
     * recovery a failed change launches. [lifecycleMutex] is taken separately by `stop()` and
     * `start()`, so without this a detached recovery can restart the previous config between a
     * request's stop and start, leaving the request to report and persist a config that never
     * started. Recovery is cheap to wait on: it skips the release gate, so it is a plain rebuild.
     */
    private val configChangeMutex = Mutex()
    private val isChangingAddressType = AtomicBoolean(false)
    private val refreshFailureLogged = AtomicBoolean(false)

    init {
        observeConnectivityForSyncRetry()
        scope.launch { sparkRepo.nodeEvents.collect { onSparkEvent(it) } }
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
                        scheduleOrdinaryFollowupRetry()
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
                NodeNotRunningError(operationName, nodeLifecycleState)
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

    private fun requireWalletRestoreComplete() {
        keychain.requireWalletRestoreComplete()
    }

    private suspend fun <T> executeOperation(
        operationName: String,
        operation: suspend () -> Result<T>,
    ): Result<T> = runCatching {
        operation().getOrThrow()
    }.onFailure {
        // Cancellation is expected during pull-to-refresh, rethrow per Kotlin best practices
        if (it is CancellationException) throw it
        if (it is PaymentAbortedBeforeSend) {
            Logger.debug("Aborted '$operationName' before dispatch", context = TAG)
        } else {
            Logger.error("Error executing '$operationName'", it, context = TAG)
        }
    }

    private suspend fun setup(
        walletIndex: Int,
        customServerUrl: String? = null,
        customRgsServerUrl: String? = null,
        channelMigration: ChannelDataMigration? = null,
    ) = withContext(bgDispatcher) {
        runSuspendCatching {
            lightningService.setup(
                walletIndex,
                customServerUrl,
                customRgsServerUrl,
                channelMigration,
            )
        }.onFailure {
            Logger.error("Node setup error", it, context = TAG)
        }
    }

    suspend fun start(
        walletIndex: Int = 0,
        timeout: Duration? = null,
        shouldRetry: Boolean = true,
        customServerUrl: String? = null,
        customRgsServerUrl: String? = null,
        eventHandler: NodeEventHandler? = null,
        channelMigration: ChannelDataMigration? = null,
        shouldValidateGraph: Boolean = true,
    ): Result<Unit> = startNode(
        walletIndex = walletIndex,
        timeout = timeout,
        shouldRetry = shouldRetry,
        customServerUrl = customServerUrl,
        customRgsServerUrl = customRgsServerUrl,
        eventHandler = eventHandler,
        channelMigration = channelMigration,
        shouldValidateGraph = shouldValidateGraph,
        shouldRetryYieldToStop = false,
    )

    private suspend fun startNode(
        walletIndex: Int = 0,
        timeout: Duration? = null,
        shouldRetry: Boolean = true,
        customServerUrl: String? = null,
        customRgsServerUrl: String? = null,
        eventHandler: NodeEventHandler? = null,
        channelMigration: ChannelDataMigration? = null,
        shouldValidateGraph: Boolean = true,
        shouldRetryYieldToStop: Boolean = false,
        shouldCancelPendingStop: Boolean = true,
    ): Result<Unit> = withContext(bgDispatcher) {
        if (_isRecoveryMode.value) {
            return@withContext Result.failure(RecoveryModeError())
        }

        if (shouldCancelPendingStop) cancelPendingStop()

        // Spark needs no node, so it starts alongside it and stays up while the node stops in background.
        if (!isWiping) scope.launch { sparkRepo.start(walletIndex) }

        eventHandler?.let { _eventHandlers.add(it) }

        // Track retry state outside mutex to avoid deadlock (Mutex is non-reentrant)
        var shouldRetryStart = false
        var shouldRestartForGraphReset = false
        var initialLifecycleState: NodeLifecycleState

        val result = lifecycleMutex.withLock {
            if (isWiping) return@withLock Result.failure(WipeInProgressError())
            initialLifecycleState = _lightningState.value.nodeLifecycleState
            if (initialLifecycleState.isRunningOrStarting()) {
                return@withLock skipStartForRunningNode(
                    lifecycleState = initialLifecycleState,
                    customServerUrl = customServerUrl,
                    customRgsServerUrl = customRgsServerUrl,
                )
            }

            runCatching {
                _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Starting) }

                // Setup if needed
                if (lightningService.node == null) {
                    val setupResult =
                        setup(walletIndex, customServerUrl, customRgsServerUrl, channelMigration)
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

                if (lightningService.status?.isRunning == true) {
                    Logger.info("LDK node already running", context = TAG)
                    runSuspendCatching { lightningService.reconcileWatchOnlyAccounts() }
                        .onFailure {
                            Logger.warn("Failed to reconcile Paykit Server accounts during startup", it, context = TAG)
                        }
                    return@withLock adoptRunningNode()
                }

                lightningService.start(timeout, ::onEvent)

                _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }
                listPaymentsKeepingSendHashes()

                // Initial state sync
                syncState()
                logNodeSupportSummary("node started")
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
                disconnectIdlePeers().onFailure {
                    Logger.error("Failed to disconnect idle peers", it, context = TAG)
                }
                logNodeSupportSummary("idle peers disconnected")

                sync().onFailure { e ->
                    Logger.warn("Initial sync failed, event-driven sync will retry", e, context = TAG)
                }
                Result.success(Unit)
            }.getOrElse { e ->
                if (e is CancellationException) {
                    withContext(NonCancellable) { reconcileCancelledStart(initialLifecycleState) }
                    throw e
                }

                val currentState = _lightningState.value.nodeLifecycleState
                if (currentState.isRunning()) {
                    Logger.warn("Start error but node is $currentState, skipping retry", e, context = TAG)
                    return@withLock Result.success(Unit)
                }

                if (lightningService.status?.isRunning == true) {
                    Logger.warn("Start error but LDK node is running, adopting it", e, context = TAG)
                    return@withLock adoptRunningNode()
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
            // A stop requested after this start began wins over the retry; a foreground start cancels it
            if (shouldRetryYieldToStop && pendingStopJob.get() != null) {
                Logger.info("Skipped start retry because a stop was requested", context = TAG)
                return@withContext Result.failure(NodeStartYieldedToStopError(result.exceptionOrNull()))
            }
            return@withContext startNode(
                walletIndex = walletIndex,
                timeout = timeout,
                shouldRetry = false,
                customServerUrl = customServerUrl,
                customRgsServerUrl = customRgsServerUrl,
                channelMigration = channelMigration,
                shouldValidateGraph = shouldValidateGraph,
                shouldCancelPendingStop = !shouldRetryYieldToStop,
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

    private suspend fun adoptRunningNode(): Result<Unit> {
        _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }
        scheduleOrdinaryFollowupRetry()
        return lightningService.startEventListener(::onEvent).onFailure {
            Logger.warn("Failed to start event listener", it, context = TAG)
        }
    }

    private suspend fun reconcileCancelledStart(initialLifecycleState: NodeLifecycleState) {
        if (lightningService.status?.isRunning == true) {
            if (_lightningState.value.nodeLifecycleState.isRunning()) {
                // This start already installed the event listener. Re-arming it would cancel and join
                // the live listener, aborting an in-flight handler for an event LDK already acknowledged.
                Logger.info("Kept running LDK node and its event listener after start cancellation", context = TAG)
                return
            }
            Logger.info("Adopted running LDK node after start cancellation", context = TAG)
            adoptRunningNode()
            return
        }
        Logger.info("Restored lifecycle state '$initialLifecycleState' after start cancellation", context = TAG)
        _lightningState.update { it.copy(nodeLifecycleState = initialLifecycleState) }
    }

    private suspend fun skipStartForRunningNode(
        lifecycleState: NodeLifecycleState,
        customServerUrl: String?,
        customRgsServerUrl: String?,
    ): Result<Unit> {
        if (customServerUrl != null || customRgsServerUrl != null) {
            // A node that is already up was not built with this config, so reporting success would
            // let the caller persist a server that never started.
            Logger.warn("Skipped LDK node start with custom config, state: $lifecycleState", context = TAG)
            return Result.failure(NodeConfigNotAppliedError())
        }

        Logger.info("LDK node start skipped, lifecycle state: $lifecycleState", context = TAG)
        lightningService.startEventListener(::onEvent)
        return Result.success(Unit)
    }

    fun removeEventHandler(handler: NodeEventHandler) {
        _eventHandlers.remove(handler)
    }

    private suspend fun onEvent(event: Event) {
        // An earlier channel attempt's failure never fails a retry Spark is paying.
        if (event is Event.PaymentFailed && event.paymentHash?.let { isSparkSend(it) } == true) {
            Logger.info("Skipped node failure of '${event.paymentHash}', its retry went over Spark", context = TAG)
            return
        }
        val observedTxid = when (event) {
            is Event.OnchainTransactionReceived -> event.txid
            is Event.OnchainTransactionConfirmed -> event.txid
            else -> null
        }
        if (observedTxid != null) {
            runSuspendCatching {
                val observed = onchainSendAttemptStore.observeExactTransaction(
                    observedTxid,
                    isConfirmed = event is Event.OnchainTransactionConfirmed,
                )
                val attempt = observed ?: onchainSendAttemptStore.current()
                if (attempt != null && attempt.txid.equals(observedTxid, ignoreCase = true) &&
                    attempt.hasPositiveEvidence && !attempt.localFollowupComplete && attempt.requestId == null
                ) {
                    finishOnchainSendLocally(attempt)
                    onchainSendAttemptStore.markLocalFollowupComplete(attempt.attemptId, attempt.walletIndex)
                }
            }
                .onFailure {
                    Logger.warn("Failed to record exact on-chain transaction observation", it, context = TAG)
                    scheduleOrdinaryFollowupRetry()
                }
        }
        handleLdkEvent(event)
        recordProbeOutcome(event)
        val settledReceiveInvoice: SettledReceiveInvoice?
        val settledReceiveAddress: SettledReceiveAddress?
        when (event) {
            is Event.PaymentReceived -> {
                settledReceiveInvoice = invalidateSettledReceiveInvoice(event)
                settledReceiveAddress = null
                val paymentId = event.paymentId ?: event.paymentHash
                runSuspendCatching { coreService.activity.handlePaymentEvent(paymentId) }
                    .onFailure { Logger.error("Failed to record received payment '$paymentId'", it, context = TAG) }
            }

            is Event.OnchainTransactionReceived -> {
                settledReceiveInvoice = null
                settledReceiveAddress = invalidateSettledReceiveAddress(event)
            }

            else -> {
                settledReceiveInvoice = null
                settledReceiveAddress = null
            }
        }
        _eventHandlers.toList().forEach {
            runCatching { it.invoke(event) }
        }
        _nodeEventUpdates.emit(
            NodeEventUpdate(
                event = event,
                settledReceiveInvoice = settledReceiveInvoice,
                settledReceiveAddress = settledReceiveAddress,
            ),
        )
    }

    private suspend fun onSparkEvent(event: Event) {
        // An earlier Spark attempt's failure never fails a retry the node is paying.
        if (event is Event.PaymentFailed && event.paymentHash?.let { isLdkSend(it) } == true) {
            Logger.info("Skipped Spark failure of '${event.paymentHash}', its retry went over a channel", context = TAG)
            return
        }
        val settledReceiveInvoice = (event as? Event.PaymentReceived)?.let { invalidateSettledReceiveInvoice(it) }
        _eventHandlers.toList().forEach {
            runCatching { it.invoke(event) }
        }
        _nodeEventUpdates.emit(NodeEventUpdate(event = event, settledReceiveInvoice = settledReceiveInvoice))
    }

    private suspend fun invalidateSettledReceiveInvoice(event: Event.PaymentReceived): SettledReceiveInvoice? {
        val cachedReceive = runSuspendCatching {
            cacheStore.data.first()
        }.onFailure {
            Logger.error("Failed to read cached receive invoice", it, context = TAG)
        }.getOrNull() ?: return null
        val cachedBolt11 = cachedReceive.bolt11
        if (cachedBolt11.isEmpty()) return null

        val cachedPaymentHash = cachedReceive.bolt11PaymentHash.takeIf { it.isNotEmpty() } ?: runCatching {
            Bolt11Invoice.fromStr(cachedBolt11).paymentHash()
        }.onFailure {
            Logger.error("Failed to parse cached receive invoice", it, context = TAG)
        }.getOrNull()
        if (cachedPaymentHash != event.paymentHash) return null

        val invalidated = runSuspendCatching {
            cacheStore.invalidateReceiveLightningInvoice(expectedBolt11 = cachedBolt11)
        }.onFailure {
            Logger.error("Failed to invalidate settled receive invoice", it, context = TAG)
        }.getOrDefault(false)

        return if (invalidated) {
            SettledReceiveInvoice(
                bolt11 = cachedBolt11,
            )
        } else {
            null
        }
    }

    private suspend fun invalidateSettledReceiveAddress(
        event: Event.OnchainTransactionReceived,
    ): SettledReceiveAddress? {
        val cachedAddress = runSuspendCatching {
            cacheStore.data.first().onchainAddress
        }.onFailure {
            Logger.error("Failed to read cached receive address", it, context = TAG)
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if (event.details.outputs.none { it.scriptpubkeyAddress == cachedAddress }) return null

        val invalidated = runSuspendCatching {
            cacheStore.invalidateReceiveOnchainAddress(expectedAddress = cachedAddress)
        }.onFailure {
            Logger.error("Failed to invalidate settled receive address", it, context = TAG)
        }.getOrDefault(false)

        return if (invalidated) SettledReceiveAddress(cachedAddress) else null
    }

    fun setRecoveryMode(enabled: Boolean) = _isRecoveryMode.update { enabled }

    fun setWiping(enabled: Boolean) = run {
        isWiping = enabled
        sparkRepo.setWiping(enabled)
    }

    fun setInitNodeLifecycleState() {
        _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Initializing) }
    }

    /**
     * Defers [stop] so a brief background/foreground cycle does not tear the node down and rebuild it.
     * Runs on the repo scope so a cancelled ViewModel cannot drop the pending stop.
     *
     * Scheduling and cancelling are atomic: [cancelPendingStop] runs on the repo dispatcher while this
     * runs on the caller thread, so an interleaved cancel could otherwise miss the job being installed
     * and stop the node after the app is back in the foreground.
     *
     * [BACKGROUND_STOP_DELAY] must stay under the cached-app freezer debounce (~10s once the process
     * drops to `oom_adj` 900) so [stop] is at least entered before the process can be frozen. Past
     * that the process freezes mid-delay and the stop only fires on unfreeze, racing
     * [cancelPendingStop] to tear the node down just as the user returns — and [stop] runs
     * `NonCancellable`, so losing that race is unrecoverable.
     *
     * Whether the stop *completes* in that window is out of scope here: on a wallet with real
     * payment history ldk_node reliably hits its own 30s event-handling deadline, so no delay value
     * makes the teardown fit. That also makes an avoided teardown valuable, since a user returning
     * mid-stop waits it out before the ~8s node rebuild can start.
     */
    fun stopDebounced() = synchronized(pendingStopLock) {
        val job = scope.launch {
            delay(BACKGROUND_STOP_DELAY)
            stop()
        }
        pendingStopJob.getAndSet(job)?.cancel()
    }

    fun cancelPendingStop() = synchronized(pendingStopLock) { pendingStopJob.getAndSet(null)?.cancel() }

    suspend fun stop(): Result<Unit> = withContext(bgDispatcher) {
        lifecycleMutex.withLock { stopLocked() }
    }

    private suspend fun stopLocked(): Result<Unit> {
        if (_lightningState.value.nodeLifecycleState.isStoppedOrStopping() && lightningService.node == null) {
            clearProbeOutcomes()
            return Result.success(Unit)
        }

        return runCatching {
            withContext(NonCancellable) {
                _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Stopping) }
                lightningService.stop()
                clearProbeOutcomes()
                _lightningState.update {
                    LightningState(nodeLifecycleState = NodeLifecycleState.Stopped)
                }
            }
        }.onFailure {
            Logger.error("Node stop error", it, context = TAG)
            // On failure, check actual node state and update accordingly
            // If node is still running, revert to Running state to allow retry
            if (lightningService.node != null && lightningService.status?.isRunning == true) {
                Logger.warn("Stop failed but node is still running, reverting to Running state", context = TAG)
                _lightningState.update { s -> s.copy(nodeLifecycleState = NodeLifecycleState.Running) }
            } else {
                // Node appears stopped, update state
                _lightningState.update {
                    LightningState(nodeLifecycleState = NodeLifecycleState.Stopped)
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
        runSuspendCatching {
            lightningService.resetNetworkGraph(walletIndex)
        }.onFailure {
            Logger.warn("Failed to clear local network graph", it, context = TAG)
            return Result.failure(it)
        }

        return runSuspendCatching {
            vssBackupClientLdk.setup(walletIndex).getOrThrow()
            vssBackupClientLdk.deleteObject("network_graph").getOrThrow()
            Logger.info("Cleared network graph from VSS", context = TAG)
        }.onFailure {
            Logger.warn("Failed to clear network graph from VSS", it, context = TAG)
        }
    }

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
            if (it is CancellationException) {
                // A cancellation is not a sync failure, but a sync requested meanwhile must still run
                if (syncPending.getAndSet(false)) syncAsync()
                throw it
            }
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
            is Event.ProbeSuccessful -> ProbeOutcome.Success(event.paymentId, event.paymentHash, event.routeFeeMsat)
            is Event.ProbeFailed -> ProbeOutcome.Failure(
                event.paymentId,
                event.paymentHash,
                event.shortChannelId?.toString(),
                event.routeFeeMsat,
            )
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
        lifecycleMutex.withLock {
            stopLocked().mapCatching {
                Logger.debug("node stopped, calling wipeStorage", context = TAG)
                lastKnownNodeId = null
                _lastKnownChannels.update { persistentListOf() }
                lightningService.wipeStorage(walletIndex)
                sparkRepo.wipe(walletIndex).getOrThrow()
                clearProbeOutcomes()
                _lightningState.update {
                    LightningState(
                        nodeStatus = it.nodeStatus,
                        nodeLifecycleState = it.nodeLifecycleState,
                    )
                }
                setRecoveryMode(false)
            }
        }.onFailure {
            Logger.error("wipeStorage error", it, context = TAG)
        }
    }

    suspend fun restartWithElectrumServer(newServerUrl: String): Result<Unit> = withContext(bgDispatcher) {
        Logger.info("Changing ldk-node electrum server to: '$newServerUrl'", context = TAG)

        validateElectrumServer(newServerUrl).onFailure {
            Logger.warn("Rejected electrum server '$newServerUrl'", it, context = TAG)
            return@withContext Result.failure(it)
        }

        configChangeMutex.withLock {
            waitForNodeToStop().onFailure { return@withContext Result.failure(it) }

            recoverOnCancellation {
                stop().onFailure {
                    Logger.error("Failed to stop node during electrum server change", it, context = TAG)
                    return@withContext Result.failure(it)
                }

                Logger.debug("Starting node with new electrum server: '$newServerUrl'", context = TAG)

                start(
                    shouldRetry = false,
                    customServerUrl = newServerUrl,
                )
            }.onFailure {
                // Recover in the background: a wedged node's release can gate the rebuild for tens of
                // seconds, and the caller must surface this failure now rather than block on recovery.
                Logger.warn("Failed ldk-node config change, recovering in background…", context = TAG)
                scope.launch { restartWithPreviousConfig() }
            }.onSuccess {
                runSuspendCatching { settingsStore.update { it.copy(electrumServer = newServerUrl) } }
                    .onFailure {
                        Logger.error("Failed to persist electrum server '$newServerUrl'", it, context = TAG)
                        return@withContext Result.failure(it)
                    }

                Logger.info("Successfully changed electrum server", context = TAG)
            }
        }
    }

    suspend fun restartWithRgsServer(newRgsUrl: String): Result<Unit> = withContext(bgDispatcher) {
        Logger.info("Changing ldk-node RGS server to: '$newRgsUrl'", context = TAG)

        validateRgsUrl(newRgsUrl).onFailure {
            Logger.warn("RGS server unreachable at '$newRgsUrl'", it, context = TAG)
            return@withContext Result.failure(it)
        }

        configChangeMutex.withLock {
            waitForNodeToStop().onFailure { return@withContext Result.failure(it) }

            recoverOnCancellation {
                stop().onFailure {
                    Logger.error("Failed to stop node during RGS server change", it, context = TAG)
                    return@withContext Result.failure(it)
                }

                Logger.debug("Starting node with new RGS server: '$newRgsUrl'", context = TAG)

                start(
                    shouldRetry = false,
                    customRgsServerUrl = newRgsUrl,
                )
            }.onFailure {
                // Recover in the background: a wedged node's release can gate the rebuild for tens of
                // seconds, and the caller must surface this failure now rather than block on recovery.
                Logger.warn("Failed ldk-node config change, recovering in background…", context = TAG)
                scope.launch { restartWithPreviousConfig() }
            }.onSuccess {
                settingsStore.update { it.copy(rgsServerUrl = newRgsUrl) }

                Logger.info("Successfully changed RGS server", context = TAG)
            }
        }
    }

    private inline fun <T> recoverOnCancellation(block: () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            // The caller is route-scoped: leaving the screen cancels the change mid-rebuild. stop()
            // and start() rethrow the cancellation instead of reporting a failure, so the onFailure
            // recovery never runs and the node would stay down until the next ON_START. Hand the
            // recovery to the repo scope, which outlives the caller, then preserve the cancellation.
            Logger.warn("Cancelled ldk-node config change, recovering in background…", context = TAG)
            scope.launch { restartWithPreviousConfig() }
            throw e
        }
    }

    suspend fun validateRgsUrl(url: String): Result<Unit> = withContext(bgDispatcher) {
        val initialTimestamp = 0
        val testUrl = "${url.trimEnd('/')}/$initialTimestamp"
        urlValidator.validate(testUrl)
    }

    suspend fun validateElectrumServer(url: String): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching { ElectrumServer.parse(url) }
            .fold(
                onSuccess = { electrumProbeService.probe(it) },
                onFailure = { Result.failure(it) },
            )
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
        val requiredMonitoredTypes = (monitoredTypes + DEFAULT_ADDRESS_TYPE_STRING).distinct()
        val addressType = selectedType.toAddressType() ?: AddressType.P2WPKH

        suspend fun rollback() =
            settingsStore.update { it.copy(selectedAddressType = oldSelected, addressTypesToMonitor = oldMonitored) }

        runCatching {
            settingsStore.update {
                it.copy(selectedAddressType = selectedType, addressTypesToMonitor = requiredMonitoredTypes)
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
        val configurationError = when {
            addressType == AddressType.P2WPKH ->
                AppError("Cannot disable monitoring: Native SegWit is required for Blocktank refunds")
            addressType == settings.selectedAddressType.toAddressType() ->
                AppError("Cannot disable monitoring: address type is currently selected")
            isLastRequiredNativeWitnessWallet(addressType, monitoredTypes) -> AppError(
                "Cannot disable monitoring: at least one Native SegWit or Taproot wallet required for Lightning"
            )
            else -> null
        }
        if (configurationError != null) return configurationError

        val balance = getBalanceForAddressType(addressType).getOrElse {
            return AppError("Cannot disable monitoring: failed to verify balance")
        }
        return if (balance > 0uL) AppError("Cannot disable monitoring: address type has balance") else null
    }

    private suspend fun syncMonitoredTypesFromNode() {
        runCatching {
            val nodeMonitored = lightningService.listMonitoredAddressTypes()
            val settings = settingsStore.data.first()
            val selectedType = settings.selectedAddressType.toAddressType() ?: AddressType.P2WPKH
            val combined = (nodeMonitored + selectedType + AddressType.P2WPKH).distinct()
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
            if (typeStr == DEFAULT_ADDRESS_TYPE_STRING) return@filter false
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
        // Runs detached from the failed change, so it takes the same transaction lock: without it
        // this recovery can restart the previous config between a later change's stop and start.
        configChangeMutex.withLock {
            Logger.debug("Stopping node for recovery attempt", context = TAG)

            stop().onFailure { e ->
                Logger.error("Failed to stop node during recovery", e, context = TAG)
                return@withContext Result.failure(e)
            }

            Logger.debug("Starting node with previous config for recovery", context = TAG)

            start(shouldRetry = false).onSuccess {
                Logger.debug("Successfully started node with previous config", context = TAG)
            }.onFailure {
                Logger.error("Failed starting node with previous config", it, context = TAG)
            }
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

    suspend fun disconnectIdlePeers(): Result<Unit> = executeWhenNodeRunning("disconnectIdlePeers") {
        runSuspendCatching { lightningService.disconnectIdlePeers() }.also {
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
        requireWalletRestoreComplete()
        runCatching { lightningService.newAddress() }
    }

    suspend fun newAddressForType(addressType: AddressType): Result<String> =
        executeWhenNodeRunning("newAddressForType") {
            requireWalletRestoreComplete()
            runCatching { lightningService.newAddressForType(addressType) }
        }

    suspend fun newAddressInfoForType(addressType: AddressType): Result<AddressDerivationInfo> =
        executeWhenNodeRunning("newAddressInfoForType") {
            requireWalletRestoreComplete()
            runSuspendCatching { lightningService.newAddressInfoForType(addressType) }
        }

    suspend fun addressInfoForType(addressType: AddressType, receiveIndex: Int): Result<AddressDerivationInfo> =
        executeWhenNodeRunning("addressInfoForType") {
            runSuspendCatching { lightningService.addressInfoForType(addressType, receiveIndex) }
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
            runSuspendCatching { lightningService.revealReceiveAddresses(toReceiveIndex, forType) }
        }

    /**
     * Creates an invoice on the node or on Spark. A [isContactEndpoint] invoice is published to Paykit contacts, who
     * pay it any amount, so it prefers Spark unless the node's inbound covers [AMOUNTLESS_NODE_INBOUND_SATS].
     */
    suspend fun createInvoice(
        amountSats: ULong? = null,
        description: String,
        expirySeconds: UInt = Defaults.bolt11ExpirySec,
        isContactEndpoint: Boolean = false,
    ): Result<String> = executeWhenNodeRunning("createInvoice") {
        requireWalletRestoreComplete()
        if (shouldReceiveWithSpark(amountSats, isContactEndpoint)) {
            return@executeWhenNodeRunning sparkRepo.createInvoice(amountSats, description, expirySeconds)
        }
        runCatching { lightningService.receive(amountSats, description, expirySeconds) }
    }

    suspend fun createInvoiceMsats(
        amountMsats: ULong,
        description: String,
        expirySeconds: UInt = Defaults.bolt11ExpirySec,
    ): Result<String> = executeWhenNodeRunning("createInvoiceMsats") {
        requireWalletRestoreComplete()
        // An LNURL withdrawal is paid at exactly this amount, which a whole-sat Spark invoice can't hold unless it
        // has no sub-sat part; the channels take such an amount when they can, else it is refused before the callback.
        val amountSats = (amountMsats.safe() + (MSATS_PER_SAT - 1uL).safe()) / MSATS_PER_SAT
        if (shouldReceiveWithSpark(amountSats)) {
            if (amountMsats % MSATS_PER_SAT != 0uL) {
                return@executeWhenNodeRunning Result.failure(SubSatAmountUnsupportedError(amountMsats))
            }
            val invoiceSats = amountSats.takeIf { it > 0uL }
            return@executeWhenNodeRunning sparkRepo.createInvoice(invoiceSats, description, expirySeconds)
        }
        runCatching { lightningService.receiveMsats(amountMsats, description, expirySeconds) }
    }

    /**
     * Receives on Spark when no ready channel has the inbound capacity for [amountSats]. An amountless contact endpoint
     * stays on the node only when its inbound covers [AMOUNTLESS_NODE_INBOUND_SATS].
     */
    private fun shouldReceiveWithSpark(amountSats: ULong?, isContactEndpoint: Boolean = false): Boolean {
        if (!sparkRepo.canReceive()) return false
        val readyChannels = _lightningState.value.channels.filter { it.isChannelReady }
        val neededSats = amountSats?.takeIf { it > 0uL } ?: AMOUNTLESS_NODE_INBOUND_SATS.takeIf { isContactEndpoint }
        return !ReceiveLiquidityDecision.canCreateLightningInvoice(
            hasReadyChannels = readyChannels.isNotEmpty(),
            inboundCapacitySats = readyChannels.calculateRemoteBalance(),
            invoiceAmountSats = neededSats,
        )
    }

    /** Pays with Spark when no channel has the outbound capacity for the invoice amount. */
    private fun shouldPayWithSpark(bolt11: String, sats: ULong?): Boolean {
        val amountSats = invoiceAmountSats(bolt11, sats) ?: return false
        val state = _lightningState.value
        val ldkCanSend = state.nodeLifecycleState.canRun() &&
            state.channels.totalNextOutboundHtlcLimitSats() >= amountSats
        return !ldkCanSend && sparkRepo.canSend(amountSats)
    }

    private fun invoiceAmountSats(bolt11: String, sats: ULong?): ULong? =
        sats ?: runCatching { Bolt11Invoice.fromStr(bolt11).amountMilliSatoshis() }.getOrNull()
            ?.let { it / MSATS_PER_SAT }

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
        requireWalletRestoreComplete()
        val callbackUrl = createWithdrawCallbackUrl(k1, callback, paymentRequest)
        Logger.debug("handleLnurlWithdraw callbackUrl generated: '$callbackUrl'", context = TAG)
        lnurlService.requestLnurlWithdraw(callbackUrl)
    }

    suspend fun requestLnurlChannel(
        k1: String,
        callback: String,
        nodeId: String,
    ): Result<LnurlChannelResponse> = executeWhenNodeRunning("requestLnurlChannel") {
        requireWalletRestoreComplete()
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
    ): Result<PaymentId> = payInvoice(bolt11, sats, onBeforeSend = { true })

    suspend fun payInvoice(
        bolt11: String,
        sats: ULong? = null,
        onBeforeSend: suspend () -> Boolean,
    ): Result<PaymentId> = payInvoice(bolt11, sats, null, onBeforeSend)

    suspend fun payInvoice(
        bolt11: String,
        sats: ULong?,
        paymentDeadlineAt: Instant?,
        onBeforeSend: suspend () -> Boolean,
    ): Result<PaymentId> = payInvoice(bolt11, sats, paymentDeadlineAt, maxTotalRoutingFeeMsat = null, onBeforeSend)

    suspend fun payInvoice(
        bolt11: String,
        sats: ULong?,
        paymentDeadlineAt: Instant?,
        maxTotalRoutingFeeMsat: ULong?,
        onBeforeSend: suspend () -> Boolean,
    ): Result<PaymentId> {
        // The rail is picked again on every attempt, so each one checks the other holds no live send of the invoice.
        val paymentHash = runCatching { Bolt11Invoice.fromStr(bolt11).paymentHash() }.getOrNull()
        if (shouldPayWithSpark(bolt11, sats)) {
            runCatching { requireWalletRestoreComplete() }.onFailure { return Result.failure(it) }
            val key = ldkSendKey(bolt11, paymentHash)
            // Read before the checks, so a node send admitted after them refuses this one at submission.
            val ldkRecordsBefore = ldkSendRecords.get()
            runSuspendCatching { earlierNodeSend(bolt11, paymentHash) }
                .getOrElse { return Result.failure(it) }
                ?.let {
                    // The node paid it, so its caller settles from the node's payment, as its event already fired.
                    if (it is EarlierSend.Paid) recordSendRail(it.paymentHash, LightningRail.Ldk)
                    return it.toResult()
                }
            var isAdmitted = false
            var isRefused = false
            var railBefore: LightningRail? = null
            val authorizeSend: suspend () -> Boolean = {
                val isAuthorized = onBeforeSend().also { if (it) requireWalletRestoreComplete() }
                if (isAuthorized) {
                    isAdmitted = admitSend(key, LightningRail.Spark) {
                        (ldkSendRecordedAt[key] ?: 0L) > ldkRecordsBefore
                    }
                    isRefused = !isAdmitted
                    if (isRefused) throw PaymentPendingException(paymentHash ?: key)
                    // Recorded at admission, so a node failure arriving while Spark sends reads as an older attempt's.
                    railBefore = sendRails[key]
                    recordSendRail(key, LightningRail.Spark)
                }
                isAuthorized
            }
            // Without a caller's limit Spark keeps to the node's default, so a re-quote cannot raise the fee past it.
            val maxTotalFeeMsat = maxTotalRoutingFeeMsat ?: invoiceAmountSats(bolt11, sats)?.let {
                (it.safe() * MSATS_PER_SAT.safe() / DEFAULT_ROUTING_FEE_DIVISOR).safe() +
                    DEFAULT_ROUTING_FEE_BASE_MSAT.safe()
            }
            return try {
                sparkRepo.payInvoice(bolt11, sats, paymentDeadlineAt, maxTotalFeeMsat, authorizeSend)
                    .onSuccess { recordSendRail(it, LightningRail.Spark) }
                    .onFailure {
                        if (it is PaymentPendingException && !isRefused) {
                            recordSendRail(it.paymentHash, LightningRail.Spark)
                        } else if (isAdmitted) {
                            // Spark sent nothing, so the node's attempt stays the latest.
                            railBefore?.let { rail -> recordSendRail(key, rail) } ?: sendRails.remove(key)
                        }
                    }
            } finally {
                if (isAdmitted) releaseSend(key)
            }
        }
        return payInvoiceWithLdk(bolt11, sats, paymentHash, paymentDeadlineAt, maxTotalRoutingFeeMsat, onBeforeSend)
    }

    /** Pays [bolt11] from the node's channels only, for a send quoted on them that must never fall back to Spark. */
    suspend fun payInvoiceWithNode(bolt11: String): Result<PaymentId> {
        val paymentHash = runCatching { Bolt11Invoice.fromStr(bolt11).paymentHash() }.getOrNull()
        return payInvoiceWithLdk(bolt11, null, paymentHash, null, null) { true }
    }

    /**
     * The node's pending or paid send of [bolt11]. While the node can't list its payments (stopped or not started yet),
     * a send it started for the invoice may be live or paid, and its event may never come, so the node not started yet
     * is thrown rather than a Pending nothing resolves. Before the node first listed its payments, the sends it made
     * earlier are unknown, so the same is thrown.
     */
    private suspend fun earlierNodeSend(bolt11: String, paymentHash: String?): EarlierSend? {
        val key = ldkSendKey(bolt11, paymentHash)
        val recordsBefore = ldkSendRecords.get()
        val inFlightBefore = ldkSendsInFlight.toSet()
        val payments = listPaymentsKeepingSendHashes()
        if (payments == null) {
            val cache = cacheStore.data.first()
            if (key in cache.ldkSendHashes) {
                Logger.warn("Refused a Spark send of '$key' until the node lists its earlier send", context = TAG)
                throw ServiceError.NodeNotStarted()
            }
            if (!cache.ldkSendHashesSeeded) {
                Logger.warn("Refused a Spark send until the node lists its earlier payments once", context = TAG)
                throw ServiceError.NodeNotStarted()
            }
            return null
        }
        val send = payments.firstOrNull {
            val kind = it.kind as? PaymentKind.Bolt11 ?: return@firstOrNull false
            it.direction == PaymentDirection.OUTBOUND &&
                (kind.bolt11.equals(bolt11, ignoreCase = true) || kind.hash.equals(paymentHash, ignoreCase = true))
        }
        // A retry in flight, also one that ended while the listing was read, or recorded meanwhile, holds the invoice.
        val isRetried = key in ldkSendsInFlight || key in inFlightBefore ||
            (ldkSendRecordedAt[key] ?: 0L) > recordsBefore
        if (send == null || send.status == PaymentStatus.FAILED) {
            if (isRetried) return EarlierSend.Pending(key)
            cacheStore.removeLdkSendHash(key)
        }
        if (send == null) return null
        val hash = (send.kind as PaymentKind.Bolt11).hash
        return when (send.status) {
            PaymentStatus.PENDING -> EarlierSend.Pending(hash)
            PaymentStatus.SUCCEEDED -> EarlierSend.Paid(hash)
            PaymentStatus.FAILED -> null
        }
    }

    private fun EarlierSend.toResult(): Result<PaymentId> = when (this) {
        is EarlierSend.Pending -> Result.failure(PaymentPendingException(paymentHash))
        is EarlierSend.Paid -> Result.success(paymentHash)
    }

    private suspend fun payInvoiceWithLdk(
        bolt11: String,
        sats: ULong?,
        paymentHash: String?,
        paymentDeadlineAt: Instant?,
        maxTotalRoutingFeeMsat: ULong?,
        onBeforeSend: suspend () -> Boolean,
    ): Result<PaymentId> = executeWhenNodeRunning("payInvoice") {
        requireWalletRestoreComplete()
        waitForUsableChannels()
        requireWalletRestoreComplete()
        // Read before the checks, so a Spark send admitted after them refuses this one at submission.
        val sparkRecordsBefore = sparkRepo.sendRecordMark()
        sparkRepo.earlierLightningSend(bolt11, paymentHash)?.let { return@executeWhenNodeRunning it.toResult() }
        if (!onBeforeSend()) return@executeWhenNodeRunning Result.failure(PaymentAbortedBeforeSend())
        requireWalletRestoreComplete()
        val key = ldkSendKey(bolt11, paymentHash)
        if (!admitSend(key, LightningRail.Ldk) { sparkRepo.hasRecordedSendSince(sparkRecordsBefore, paymentHash) }) {
            Logger.warn("Refused a node send of '$key', Spark sent it meanwhile", context = TAG)
            return@executeWhenNodeRunning Result.failure(PaymentPendingException(paymentHash ?: key))
        }
        try {
            ldkSendsInFlight += key
            ldkSendRecordedAt[key] = ldkSendRecords.incrementAndGet()
            cacheStore.addLdkSendHash(key, invoiceExpiresAt(bolt11))
            runCatching { lightningService.send(bolt11, sats, paymentDeadlineAt, maxTotalRoutingFeeMsat) }
                .also { ldkSendsInFlight -= key }
                .onSuccess { recordSendRail(it, LightningRail.Ldk) }
                .also { syncState() }
        } finally {
            releaseSend(key)
        }
    }

    /**
     * The last check before a send of [key] leaves on [rail], so of two sends of one invoice only the first admitted
     * goes out: refused while the other rail holds one, or [isSentOnOtherRail] says it recorded one since the checks.
     */
    private fun admitSend(key: String, rail: LightningRail, isSentOnOtherRail: () -> Boolean): Boolean =
        synchronized(admittedSends) {
            val held = admittedSends[key]
            val isFree = (held == null || held.first == rail) && !isSentOnOtherRail()
            if (isFree) admittedSends[key] = rail to (held?.second ?: 0) + 1
            isFree
        }

    private fun releaseSend(key: String) = synchronized(admittedSends) {
        val held = admittedSends[key]
        when {
            held == null -> Unit
            held.second <= 1 -> admittedSends.remove(key)
            else -> admittedSends[key] = held.first to held.second - 1
        }
    }

    /** The node's payments after keeping its send hashes from them, or null while it can't list them. */
    private suspend fun listPaymentsKeepingSendHashes(): List<PaymentDetails>? {
        // Read after the records, as a send counts as in flight before it is recorded, so one of the two shows it.
        val recordsBefore = ldkSendRecords.get()
        val inFlightBefore = ldkSendsInFlight.toSet()
        return listPaymentsOrNull()?.also { keepLdkSendHashes(it, recordsBefore, inFlightBefore) }
    }

    /**
     * Keeps the node's pending and paid sends once, so those made before sends were kept hold Spark too, then trims
     * the ones listed failed only or paid with an expired invoice. A send that may still be pending always holds Spark:
     * one in flight, also one in flight before the listing ([inFlightBefore]) that ended while it was read, or recorded
     * after [recordsBefore] meanwhile, can show an earlier failed attempt.
     */
    private suspend fun keepLdkSendHashes(
        payments: List<PaymentDetails>,
        recordsBefore: Long,
        inFlightBefore: Set<String>,
    ) {
        val outbound = payments.filter { it.direction == PaymentDirection.OUTBOUND }
        val sends = outbound.filter { it.status != PaymentStatus.FAILED }.sortedBy { it.latestUpdateTimestamp }
        cacheStore.seedLdkSendHashes(sends.mapNotNull { (it.kind as? PaymentKind.Bolt11)?.hash })
        val hashes = { status: PaymentStatus ->
            outbound.filter { it.status == status }
                .mapNotNullTo(mutableSetOf()) { (it.kind as? PaymentKind.Bolt11)?.hash?.lowercase() }
        }
        val recordedSince = ldkSendRecordedAt.filterValues { it > recordsBefore }.keys
        val live = hashes(PaymentStatus.PENDING) + ldkSendsInFlight + inFlightBefore + recordedSince
        val paid = hashes(PaymentStatus.SUCCEEDED)
        val failed = hashes(PaymentStatus.FAILED) - paid - live
        val listed = outbound.mapNotNullTo(mutableSetOf()) { (it.kind as? PaymentKind.Bolt11)?.hash?.lowercase() }
        cacheStore.trimLdkSendHashes(paid = paid - live, failed = failed, listed = listed, live = live)
        failed.forEach { ldkSendRecordedAt.remove(it) }
    }

    /** When [bolt11] expires, in epoch seconds, or null when it can't be parsed. */
    private fun invoiceExpiresAt(bolt11: String): Long? = runCatching {
        val invoice = Bolt11Invoice.fromStr(bolt11)
        (invoice.secondsSinceEpoch().safe() + invoice.expiryTimeSeconds().safe())
            .coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
    }.getOrNull()

    /** The payment hash a node send is kept under, or the invoice itself when it can't be parsed. */
    private fun ldkSendKey(bolt11: String, paymentHash: String?): String = (paymentHash ?: bolt11).lowercase()

    private fun recordSendRail(paymentHash: String, rail: LightningRail) {
        sendRails[paymentHash.lowercase()] = rail
    }

    /** Whether the latest send of [paymentHash] went out on Spark, so the node's record of it is an older attempt. */
    fun isSparkSend(paymentHash: String): Boolean = sendRails[paymentHash.lowercase()] == LightningRail.Spark

    private fun isLdkSend(paymentHash: String): Boolean = sendRails[paymentHash.lowercase()] == LightningRail.Ldk

    /** Confirmations of an on-chain transaction this wallet sent or received, 0 while unconfirmed. */
    suspend fun onchainConfirmations(txid: String): UInt? = withContext(bgDispatcher) {
        val kind = lightningService.listPayments()
            ?.map { it.kind }
            ?.filterIsInstance<PaymentKind.Onchain>()
            ?.firstOrNull { it.txid.equals(txid, ignoreCase = true) } ?: return@withContext null
        val confirmed = kind.status as? ConfirmationStatus.Confirmed ?: return@withContext 0u
        val tip = _lightningState.value.block()?.height ?: return@withContext null
        if (tip < confirmed.height) 0u else tip - confirmed.height + 1u
    }

    suspend fun listPaymentsOrNull(): List<PaymentDetails>? = withContext(bgDispatcher) {
        lightningService.listPayments()
    }

    suspend fun waitForUsableChannels() = withContext(bgDispatcher) {
        var state = _lightningState.value
        if (!state.nodeLifecycleState.canRun()) {
            delayNoUsableChannelsFeedback()
            return@withContext
        }
        state = waitForChannelsToLoadIfNeeded(state) ?: return@withContext
        if (!state.nodeLifecycleState.canRun()) {
            delayNoUsableChannelsFeedback()
            return@withContext
        }

        // Usability can stay true while a payment changes the outbound HTLC limit.
        // Publish current channels before callers derive send capacity from them.
        refreshChannelsAndPeers()
        state = _lightningState.value
        if (!state.nodeLifecycleState.canRun()) {
            delayNoUsableChannelsFeedback()
            return@withContext
        }
        if (state.hasUsableChannels()) return@withContext

        if (state.channels.isEmpty()) {
            delayNoUsableChannelsFeedback()
            return@withContext
        }

        Logger.info("Waiting for usable channels before sending payment", context = TAG)

        val finalState = awaitUsableChannels() ?: run {
            Logger.warn("Timed out waiting for usable channels", context = TAG)
            return@withContext
        }

        if (!finalState.nodeLifecycleState.canRun() || finalState.channels.isEmpty()) {
            delayNoUsableChannelsFeedback()
        }
    }

    private suspend fun awaitUsableChannels(): LightningState? = withTimeoutOrNull(CHANNELS_USABLE_TIMEOUT) {
        refreshChannelsAndPeers()
        while (!_lightningState.value.shouldStopWaitingForUsableChannels()) {
            delay(CHANNELS_USABLE_POLL_DELAY)
            refreshChannelsAndPeers()
        }
        _lightningState.value
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
        beforeSendAttempt: suspend () -> Unit = {},
        onBroadcast: suspend (Txid) -> Unit = {},
        requestId: PaykitPaymentRequestId? = null,
        orderId: String? = null,
        transferContext: OnchainTransferContext? = null,
        payerIdentity: String? = null,
        paymentDeadlineAt: Instant? = null,
        contactPublicKey: String? = null,
        approvedMiningFeeSats: ULong? = null,
    ): Result<OnchainSendOutcome> = executeWhenNodeRunning("sendOnChain") {
        requireWalletRestoreComplete()
        require(address.isNotEmpty()) { "Send address cannot be empty" }

        // Recovery must remain reachable when observing the original transaction makes sync fail.
        val retainedAttempt = runSuspendCatching { onchainSendAttemptStore.current() }.getOrElse {
            return@executeWhenNodeRunning Result.failure(it)
        }
        if (retainedAttempt?.blocksNextSend == true) {
            return@executeWhenNodeRunning Result.failure(OnchainSendBlockedError(retainedAttempt))
        }

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

        val attempt = runSuspendCatching {
            requireWalletRestoreComplete()
            onchainSendAttemptStore.admit(
                walletId = WalletScope.default,
                requestId = requestId,
                orderId = orderId,
                address = address,
                amountSats = sats,
                isMaxAmount = isMaxAmount,
                feeRateSatsPerVByte = satsPerVByte,
                isTransfer = isTransfer,
                channelId = channelId,
                tags = tags,
                transferContext = transferContext,
                beforeSendAttempt = beforeSendAttempt,
                payerIdentity = payerIdentity,
                contactPublicKey = contactPublicKey,
            )
        }.getOrElse {
            val error = if (it is OnchainSendBlockedError || it is OnchainSendAttemptUnreadableError) {
                it
            } else {
                OnchainSendNotDispatchedError(it)
            }
            return@executeWhenNodeRunning Result.failure(error)
        }
        val preparedResult = try {
            runSuspendCatching {
                requireWalletRestoreComplete()
                lightningService.prepareOnchainSend(
                    address,
                    sats,
                    satsPerVByte,
                    utxosForSend,
                    isMaxAmount,
                    attempt.walletIndex,
                    paymentDeadlineAt,
                )
                    .also { prepared ->
                        if (!isMaxAmount && utxosForSend != null) {
                            check(
                                prepared.receipt.inputs.toSet() == utxosForSend.map {
                                    OnchainSendInput(it.outpoint.txid, it.outpoint.vout)
                                }.toSet()
                            )
                        }
                        if (approvedMiningFeeSats != null) {
                            val miningFee = prepared.receipt.miningFeeSats
                            if (prepared.receipt.amountSats != sats || miningFee == null ||
                                miningFee > approvedMiningFeeSats
                            ) {
                                throw OnchainSendChangedError(miningFee)
                            }
                        }
                        onchainSendAttemptStore.retainPreparedReceipt(
                            attempt.attemptId,
                            attempt.walletIndex,
                            prepared.receipt.copy(feeRateSatsPerVByte = satsPerVByte),
                            isRecovery = false,
                        )
                    }
            }
        } catch (cancelled: CancellationException) {
            // Release only the exact empty pre-dispatch guard. A retained receipt remains protected.
            withContext(NonCancellable) {
                runSuspendCatching {
                    onchainSendAttemptStore.releaseBeforeDispatch(attempt.attemptId, attempt.walletIndex)
                }.onFailure { Logger.warn("Failed to release cancelled preparation", it, context = TAG) }
            }
            throw cancelled
        }
        val prepared = preparedResult.getOrElse {
            // Preparation and receipt persistence never dispatch. A failed save leaves the preguard if cleanup fails.
            runSuspendCatching { onchainSendAttemptStore.releaseBeforeDispatch(attempt.attemptId, attempt.walletIndex) }
            return@executeWhenNodeRunning Result.failure(OnchainSendNotDispatchedError(it))
        }
        var outcomePersisted = true
        var dispatchStarted = false
        val outcome = runSuspendCatching {
            onchainSendAttemptStore.broadcastPreparedCandidate(
                attempt.attemptId,
                attempt.walletIndex,
                prepared.receipt.txid,
                broadcast = { beforeDispatch ->
                    prepared.broadcast {
                        requireWalletRestoreComplete()
                        beforeDispatch()
                        requireWalletRestoreComplete()
                        dispatchStarted = true
                    }
                },
            )
        }.getOrElse { error ->
            if (error is OnchainSendNotDispatchedError) {
                runSuspendCatching {
                    onchainSendAttemptStore.releaseBeforeDispatch(attempt.attemptId, attempt.walletIndex)
                }.onFailure { Logger.warn("Failed to clear unsubmitted preparation", it, context = TAG) }
                return@executeWhenNodeRunning Result.failure(error)
            }
            val winnerResult = runSuspendCatching { onchainSendAttemptStore.current() }
            val winner = winnerResult.getOrNull()
            val releasedWithoutDispatch = !dispatchStarted && winnerResult.isSuccess && winner == null
            if (winner?.attemptId == attempt.attemptId && winner.walletId == attempt.walletId &&
                winner.hasPositiveEvidence
            ) {
                outcomePersisted = false
                OnchainSendOutcome.Accepted(requireNotNull(winner.txid))
            } else if (releasedWithoutDispatch &&
                lightningService.currentWalletIndex == attempt.walletIndex
            ) {
                // current() released the exact local preparation whose dispatch marker was never written.
                return@executeWhenNodeRunning Result.failure(OnchainSendNotDispatchedError(error))
            } else {
                return@executeWhenNodeRunning Result.failure(OnchainSendPendingError(error, prepared.receipt.txid))
            }
        }
        val recorded = runSuspendCatching {
            onchainSendAttemptStore.recordOutcome(attempt.attemptId, outcome, attempt.walletIndex)
        }.getOrElse { error ->
            outcomePersisted = false
            val winner = runSuspendCatching { onchainSendAttemptStore.current() }.getOrNull()
            if (winner?.attemptId == attempt.attemptId && winner.walletId == attempt.walletId &&
                winner.hasPositiveEvidence
            ) {
                winner
            } else {
                // Native acceptance is not durable local completion. Preserve the signed receipt
                // and Pending until the exact winner can be persisted or independently observed.
                return@executeWhenNodeRunning Result.failure(OnchainSendPendingError(error, prepared.receipt.txid))
            }
        }
        val winningOutcome = if (recorded.hasPositiveEvidence) {
            OnchainSendOutcome.Accepted(requireNotNull(recorded.txid))
        } else {
            outcome
        }
        if (winningOutcome !is OnchainSendOutcome.Accepted) return@executeWhenNodeRunning Result.success(winningOutcome)
        val txId = winningOutcome.txid
        runSuspendCatching { onBroadcast(txId) }
            .onFailure { Logger.warn("Failed to continue accepted on-chain send", it, context = TAG) }

        runSuspendCatching { finishOnchainSendLocally(recorded) }
            .onFailure { Logger.warn("Failed to finish accepted on-chain send locally", it, context = TAG) }
        if (!outcomePersisted) {
            runSuspendCatching {
                val durable = requireNotNull(onchainSendAttemptStore.currentDurable())
                check(
                    durable.attemptId == recorded.attemptId && durable.walletId == recorded.walletId &&
                        durable.hasPositiveEvidence && durable.txid.equals(txId, true)
                ) {
                    "Accepted on-chain outcome repair is not durable"
                }
            }.getOrElse {
                return@executeWhenNodeRunning Result.failure(OnchainSendPendingError(it, txId))
            }
        }
        runSuspendCatching { syncState() }
            .onFailure { Logger.warn("Failed to sync after accepted on-chain send", it, context = TAG) }
        Result.success(winningOutcome.copy(amountSats = recorded.amountSats.takeIf { recorded.isMaxAmount }))
    }

    private val recoveryCoordinator by lazy {
        OnchainSendCoordinator(
            onchainSendAttemptStore,
            object : OnchainPreparedSender {
                override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend =
                    error("Initial sends use sendOnChain admission")

                override suspend fun prepareRecovery(
                    attempt: OnchainSendAttempt,
                    feeRateSatsPerVByte: ULong,
                    paymentDeadlineAt: Instant?,
                ): PreparedOnchainSend {
                    val inputs = requireNotNull(attempt.originalInputs)
                    // The saved signed receipt is the input identity. Native preparation resolves
                    // the actual values and spendability; never replace missing inputs from a fresh list.
                    val selected = inputs.map { input ->
                        SpendableUtxo(OutPoint(input.txid, input.vout), 0uL)
                    }
                    requireWalletRestoreComplete()
                    return guardPreparedBroadcast(
                        lightningService.prepareOnchainSend(
                            attempt.address,
                            attempt.amountSats,
                            feeRateSatsPerVByte,
                            selected,
                            false,
                            attempt.walletIndex,
                            paymentDeadlineAt,
                        )
                    )
                }
            },
            bgDispatcher
        )
    }

    suspend fun retryOriginalOnchainSend(
        attemptId: String,
        walletId: String,
        feeRateSatsPerVByte: ULong,
        paymentDeadlineAt: Instant? = null,
        approvePrepared: suspend (OnchainPreparedReceipt) -> Unit = {},
        authorizeOriginal: suspend (OnchainSendAttempt) -> Unit,
    ): Result<OnchainSendOutcome> = executeWhenNodeRunning("retryOriginalOnchainSend") {
        requireWalletRestoreComplete()
        val result = recoveryCoordinator.retryOriginal(
            attemptId,
            walletId,
            feeRateSatsPerVByte,
            paymentDeadlineAt,
            approvePrepared,
            authorizeOriginal,
        )
        val accepted = result.getOrNull() as? OnchainSendOutcome.Accepted
        if (accepted != null) {
            runSuspendCatching {
                onchainSendAttemptStore.current()?.takeIf { it.hasPositiveEvidence }?.let {
                    finishOnchainSendLocally(
                        it
                    )
                }
            }.onFailure { Logger.warn("Failed to finish recovered on-chain send locally", it, context = TAG) }
            runSuspendCatching {
                val durable = requireNotNull(onchainSendAttemptStore.currentDurable())
                check(
                    durable.attemptId == attemptId && durable.walletId == walletId &&
                        durable.hasPositiveEvidence && durable.txid.equals(accepted.txid, true)
                ) {
                    "Recovered on-chain outcome is not durable"
                }
            }.getOrElse {
                return@executeWhenNodeRunning Result.failure(OnchainSendPendingError(it, accepted.txid))
            }
        }
        result
    }

    private suspend fun finishOnchainSendLocally(attempt: OnchainSendAttempt) {
        val originalFollowup = attempt.backupFollowup
        check(
            !attempt.restoredFromBackup || originalFollowup != null
        ) { "Original local follow-up context is unavailable" }
        val originalContact = originalFollowup?.contact?.let {
            require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "Invalid original contact" }
            it.content
        }
        val txId = requireNotNull(attempt.txid) { "On-chain send has no transaction id" }
        val isSuccessor = attempt.candidateTxids.isNotEmpty() && !txId.equals(attempt.candidateTxids.first(), true)
        val needsExactFee = attempt.verifiedWinningFeeSats == null && (
            isSuccessor ||
                (attempt.candidateTxids.isNotEmpty() && originalFollowup?.feeSats?.toULongOrNull() == 0uL)
            )
        val fee = attempt.verifiedWinningFeeSats ?: if (needsExactFee) {
            requireNotNull(winningCandidateFee(attempt)) {
                "Winning transaction fee is unavailable"
            }.also { onchainSendAttemptStore.retainWinningFee(attempt.attemptId, attempt.walletIndex, txId, it) }
        } else {
            originalFollowup?.feeSats?.toULong() ?: 0uL
        }
        val preActivityMetadata = PreActivityMetadata(
            walletId = attempt.walletId,
            paymentId = txId,
            createdAt = originalFollowup?.createdAtMillis?.toULong() ?: nowMillis().toULong(),
            tags = attempt.tags,
            paymentHash = null,
            txId = txId,
            address = attempt.address,
            isReceive = false,
            feeRate = attempt.winningFeeRateSatsPerVByte,
            isTransfer = attempt.isTransfer,
            channelId = attempt.channelId ?: "",
        )
        preActivityMetadataRepo.addPreActivityMetadata(preActivityMetadata).getOrThrow()
        coreService.activity.createSentOnchainActivityFromSendResult(
            txid = txId,
            address = attempt.address,
            amount = attempt.amountSats,
            fee = fee,
            feeRate = attempt.winningFeeRateSatsPerVByte,
            isTransfer = attempt.isTransfer,
            channelId = attempt.channelId,
            walletId = attempt.walletId,
        )
        if (originalContact != null) {
            coreService.activity.restoreSentOnchainContact(txId, attempt.walletId, originalContact)
        }
        if (needsExactFee || attempt.verifiedWinningFeeSats != null) {
            coreService.activity.repairVerifiedSentOnchainFee(
                txId,
                attempt.walletId,
                fee,
                attempt.winningFeeRateSatsPerVByte
            )
        }
        check(coreService.activity.getOnchainActivityByTxId(txId, attempt.walletId) != null) {
            "Accepted on-chain transaction has no durable local activity"
        }
    }

    private suspend fun winningCandidateFee(attempt: OnchainSendAttempt): ULong? =
        attempt.candidateMiningFees[requireNotNull(attempt.txid).lowercase()]
            ?: lightningService.observedOriginalSendFee(attempt)

    private fun scheduleOrdinaryFollowupRetry() {
        val previous = ordinaryFollowupRetryJob.get()
        if (previous?.isActive == true) return
        val retry = scope.launch(start = CoroutineStart.LAZY) {
            repeat(3) {
                delay(1.seconds)
                if (!_lightningState.value.nodeLifecycleState.isRunning()) return@launch
                val attempt = runSuspendCatching { onchainSendAttemptStore.current() }.getOrNull() ?: return@launch
                if (!attempt.hasPositiveEvidence || attempt.localFollowupComplete || attempt.requestId != null) {
                    return@launch
                }
                completeAcceptedOrdinaryFollowup(requireNotNull(attempt.txid))
            }
        }
        if (ordinaryFollowupRetryJob.compareAndSet(previous, retry)) retry.start() else retry.cancel()
    }

    suspend fun completeAcceptedOrdinaryFollowup(txid: String) {
        val attempt = onchainSendAttemptStore.current()
        if (attempt != null && attempt.requestId == null &&
            attempt.txid.equals(txid, ignoreCase = true) && attempt.hasPositiveEvidence
        ) {
            runSuspendCatching {
                finishOnchainSendLocally(attempt)
                onchainSendAttemptStore.markLocalFollowupComplete(attempt.attemptId, attempt.walletIndex)
            }.onFailure { Logger.warn("Failed to finish accepted ordinary send locally", it, context = TAG) }
        }
    }

    /** Finishes an accepted transfer locally; [orderId] is null for a transfer that funds the Spark wallet. */
    suspend fun completeAcceptedTransferFollowup(orderId: String?, txid: String) {
        val attempt = onchainSendAttemptStore.current() ?: return
        if (attempt.isTransfer && attempt.orderId == orderId && attempt.txid.equals(txid, ignoreCase = true) &&
            attempt.hasPositiveEvidence
        ) {
            finishOnchainSendLocally(attempt)
            onchainSendAttemptStore.markLocalFollowupComplete(attempt.attemptId, attempt.walletIndex)
        }
    }

    private val fundingsFollowedOnScreen = ConcurrentHashMap.newKeySet<String>()

    /** The funding screen settles the kept Spark funding [attemptId] itself while it stays open. */
    fun followFundingOnScreen(attemptId: String, isFollowed: Boolean) {
        if (isFollowed) fundingsFollowedOnScreen.add(attemptId) else fundingsFollowedOnScreen.remove(attemptId)
    }

    /** Whether the funding screen settles the kept Spark funding [attemptId], so its recovery leaves it alone. */
    fun isFundingFollowedOnScreen(attemptId: String): Boolean = attemptId in fundingsFollowedOnScreen

    /** Hands an accepted Spark funding recovered from Payment Pending to Spark, which claims it once it matures. */
    fun followRecoveredSparkFunding(attempt: OnchainSendAttempt) {
        if (!attempt.isSparkFunding || !attempt.hasPositiveEvidence) return
        val txid = attempt.txid ?: return
        sparkRepo.onDepositBroadcast(txid, attempt.amountSats)
        // The funding screen may already follow the claim at the speed the user chose.
        if (sparkRepo.depositClaims.value[txid] == null) sparkRepo.waitForMatureClaim(txid)
    }

    // A change signal only: consumers must reread and validate the original attempt.
    val onchainSendAttemptUpdates get() = onchainSendAttemptStore.backupStateVersion

    suspend fun currentOnchainSendAttempt(): OnchainSendAttempt? = onchainSendAttemptStore.current()

    /** Syncs the on-chain wallet, then checks that none of the attempt's candidates reached the mempool or chain. */
    override suspend fun onchainCandidatesNeverReachedNetwork(attempt: OnchainSendAttempt): Boolean =
        onchainCandidatesNeverReachedNetwork(attempt, ABANDON_SYNC_TIMEOUT)

    suspend fun onchainCandidatesNeverReachedNetwork(
        attempt: OnchainSendAttempt,
        timeout: Duration,
    ): Boolean {
        // The requested sync runs on the serial LDK queue after any sync already in flight, so its success proves a
        // fresh view. Failing that, an on-chain sync that finishes later than the one read here also counts.
        val previousSync = lightningService.status?.latestOnchainWalletSyncTimestamp ?: 0uL
        // The sync blocks on the LDK queue and ignores cancellation, so it runs apart and the wait keeps its limit.
        val requestedSyncSucceeded = AtomicBoolean(false)
        scope.launch { if (runSuspendCatching { lightningService.sync() }.isSuccess) requestedSyncSucceeded.set(true) }
        withTimeoutOrNull(timeout) {
            while (true) {
                val latestSync = lightningService.status?.latestOnchainWalletSyncTimestamp
                if (isOnchainSyncAfter(latestSync, previousSync, requestedSyncSucceeded.get())) break
                delay(500.milliseconds)
            }
        } ?: throw OnchainSendAbandonSyncError()
        return lightningService.walletKnowsNoCandidate(attempt)
    }

    override suspend fun walletKnowsTransaction(txid: String): Boolean = lightningService.walletKnowsTransaction(txid)

    override suspend fun originalCoinSpentElsewhere(attempt: OnchainSendAttempt): Boolean =
        lightningService.originalCoinSpentElsewhere(attempt)

    override suspend fun prepareRelease(attempt: OnchainSendAttempt): PreparedOnchainSend {
        requireWalletRestoreComplete()
        return guardPreparedBroadcast(lightningService.prepareRelease(attempt))
    }

    private fun guardPreparedBroadcast(prepared: PreparedOnchainSend): PreparedOnchainSend =
        PreparedOnchainSend(prepared.receipt) { beforeDispatch ->
            requireWalletRestoreComplete()
            prepared.broadcast {
                requireWalletRestoreComplete()
                beforeDispatch()
                requireWalletRestoreComplete()
            }
        }

    suspend fun recordOnchainRelease(expected: OnchainSendAttempt, txid: String): OnchainSendAttempt? =
        onchainSendAttemptStore.recordRelease(expected, txid)

    suspend fun approveOnchainRelease(expected: OnchainSendAttempt): OnchainSendAttempt? =
        onchainSendAttemptStore.approveRelease(expected)

    suspend fun abandonUnbroadcastOnchainSend(
        expected: OnchainSendAttempt,
        removeOriginalProof: suspend (OnchainSendAttempt) -> Boolean,
    ): Boolean = onchainSendAttemptStore.abandonUnbroadcast(expected, removeOriginalProof)

    suspend fun releaseInterruptedShopPreparation(
        removeOriginalProof: suspend (OnchainSendAttempt) -> Boolean,
    ): Boolean = onchainSendAttemptStore.releaseInterruptedShopPreparation(removeOriginalProof)

    suspend fun finishAcceptedShopActivity(requestId: PaykitPaymentRequestId, txid: String) {
        val attempt = onchainSendAttemptStore.current()
        if (attempt?.requestId == requestId && attempt.txid.equals(txid, ignoreCase = true) &&
            attempt.hasPositiveEvidence
        ) {
            finishOnchainSendLocally(attempt)
        }
    }

    suspend fun completeAcceptedShopFollowup(requestId: PaykitPaymentRequestId, txid: String) {
        val attempt = onchainSendAttemptStore.current()
        if (attempt?.requestId == requestId && attempt.txid.equals(txid, ignoreCase = true) &&
            attempt.hasPositiveEvidence
        ) {
            finishOnchainSendLocally(attempt)
            onchainSendAttemptStore.markLocalFollowupComplete(attempt.attemptId, attempt.walletIndex)
        }
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

    suspend fun selectUtxosWithAlgorithm(
        targetAmountSats: ULong,
        satsPerVByte: ULong,
        algorithm: CoinSelectionAlgorithm = CoinSelectionAlgorithm.LARGEST_FIRST,
        utxos: List<SpendableUtxo>? = null,
    ): Result<List<SpendableUtxo>> = executeWhenNodeRunning("selectUtxosWithAlgorithm") {
        lightningService.selectUtxosWithAlgorithm(
            targetAmountSats = targetAmountSats,
            satsPerVByte = satsPerVByte,
            algorithm = algorithm,
            utxos = utxos,
        )
    }

    suspend fun calculateTotalFee(
        amountSats: ULong,
        address: Address? = null,
        speed: TransactionSpeed? = null,
        utxosToSpend: List<SpendableUtxo>? = null,
        feeRates: FeeRates? = null,
        /** False when a placeholder fee would be shown as the real one: any error then fails the call. */
        allowFallback: Boolean = true,
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
            if (it is CancellationException || !allowFallback) throw it
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

    suspend fun getFeeRates(): Result<FeeRates> = withContext(bgDispatcher) {
        coreService.blocktank.getFees()
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
        requireWalletRestoreComplete()
        lightningService.openChannel(peer, channelAmountSats, pushToCounterpartySats, channelConfig).also {
            syncState()
        }
    }

    suspend fun closeChannel(
        channel: ChannelDetails,
        force: Boolean = false,
        forceCloseReason: String? = null,
    ): Result<Unit> = executeWhenNodeRunning("closeChannel") {
        requireWalletRestoreComplete()
        runCatching { lightningService.closeChannel(channel, force, forceCloseReason) }.also {
            syncState()
        }
    }

    /**
     * Re-reads channels and peers from the running node, leaving balances as they are (see [syncState]).
     * A peer reconnecting makes a channel usable again without any node event, so callers poll this.
     * [onRefreshed] runs right after the new channels are published, in the same uninterruptible block,
     * so state derived from them cannot fall behind them.
     */
    suspend fun refreshChannelsAndPeers(onRefreshed: () -> Unit = {}): Result<Unit> = withContext(bgDispatcher) {
        if (!_lightningState.value.nodeLifecycleState.isRunning()) return@withContext Result.success(Unit)
        runCatching {
            val committed = _lightningState.updateAndGet {
                it.copy(
                    peers = getPeers().orEmpty().toImmutableList(),
                    channels = getChannels().orEmpty().toImmutableList(),
                )
            }
            if (committed.nodeLifecycleState.isRunning()) publishLastKnownChannels()
            onRefreshed()
        }.onSuccess {
            refreshFailureLogged.set(false)
        }.onFailure {
            if (refreshFailureLogged.compareAndSet(false, true)) {
                Logger.warn("Failed to re-read channels and peers", it, context = TAG)
            }
        }
    }

    fun syncState() {
        val committed = _lightningState.updateAndGet {
            it.copy(
                nodeId = getNodeId().orEmpty(),
                nodeStatus = getStatus(),
                peers = getPeers().orEmpty().toImmutableList(),
                channels = getChannels().orEmpty().toImmutableList(),
                balances = getBalances(),
            )
        }
        if (committed.nodeLifecycleState.isRunning()) publishLastKnownChannels()
        if (_lightningState.value.nodeLifecycleState.isRunning()) scheduleOrdinaryFollowupRetry()
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

    /** Whether Spark alone can pay [amountSats], which it does whenever the node cannot. */
    fun canSendWithSpark(amountSats: ULong): Boolean = sparkRepo.canSend(amountSats)

    fun canSend(amountSats: ULong): Boolean {
        val state = _lightningState.value
        if (sparkRepo.canSend(amountSats)) return true
        if (!state.nodeLifecycleState.canRun()) return false
        return state.channels.totalNextOutboundHtlcLimitSats() >= amountSats
    }

    fun getNodeId(): String? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) {
            lightningService.nodeId?.also { lastKnownNodeId = it }
        } else {
            null
        }

    /**
     * Node id of the current node, falling back to the one observed while it last ran.
     * The id is derived from the wallet mnemonic and only cleared on storage wipe, which is the
     * single path to another mnemonic, so it always belongs to the active wallet.
     */
    fun getLastKnownNodeId(): String? = getNodeId() ?: lastKnownNodeId

    suspend fun awaitNodeId(): String? = lastKnownNodeId
        ?: executeWhenNodeRunning("awaitNodeId", NODE_ID_WAIT_TIMEOUT) {
            runCatching { requireNotNull(lightningService.nodeId) { "Node id not available" } }
        }.getOrNull()?.also { lastKnownNodeId = it }

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

    private fun publishLastKnownChannels() = _lastKnownChannels.update {
        val state = _lightningState.value
        if (state.nodeLifecycleState.isRunning()) state.channels else it
    }

    /**
     * The completion event of a send that settled before its caller started watching for it, read from the rail it
     * last went out on: a node send only when it succeeded, as one the node paid earlier gets no new event.
     */
    suspend fun settledSparkSendEvent(paymentHash: String): Event? =
        if (isLdkSend(paymentHash)) paidNodeSendEvent(paymentHash) else sparkRepo.settledSendEvent(paymentHash)

    private suspend fun paidNodeSendEvent(paymentHash: String): Event? {
        val payments = runSuspendCatching { lightningService.listPayments() }
            .onFailure { Logger.warn("Failed to list node payments", it, context = TAG) }
            .getOrNull()
        val payment = payments?.firstOrNull {
            it.direction == PaymentDirection.OUTBOUND && it.status == PaymentStatus.SUCCEEDED &&
                (it.kind as? PaymentKind.Bolt11)?.hash.equals(paymentHash, ignoreCase = true)
        } ?: return null
        val kind = payment.kind as PaymentKind.Bolt11
        return Event.PaymentSuccessful(payment.id, kind.hash, kind.preimage, payment.feePaidMsat)
    }

    /** Spark's record of the Lightning send with [paymentHash], for recovery after its completion event was missed. */
    suspend fun sparkLightningSend(paymentHash: String): SparkLightningSend = sparkRepo.lightningSend(paymentHash)

    /** False while Spark is supported but its payment list is not complete yet, so a missing payment proves nothing. */
    fun canListSparkPayments(): Boolean = sparkRepo.canListPayments()

    /**
     * Whether Lightning can be received, through a usable channel or Spark; Paykit publishes a Lightning endpoint
     * only then.
     */
    fun canReceive(): Boolean {
        val isRunning = _lightningState.value.nodeLifecycleState.isRunning()
        return isRunning && (lightningService.canReceive() || sparkRepo.canReceive())
    }

    suspend fun bumpFeeByRbf(
        originalTxId: Txid,
        satsPerVByte: ULong,
    ): Result<Txid> = executeWhenNodeRunning("bumpFeeByRbf") {
        requireWalletRestoreComplete()
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
        requireWalletRestoreComplete()
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
        if (shouldPayWithSpark(bolt11, null)) return@executeWhenNodeRunning sparkRepo.estimateFeeSats(bolt11, null)
        Logger.info("Estimating routing fees for bolt11: $bolt11", context = TAG)
        lightningService.estimateRoutingFees(bolt11).onSuccess {
            Logger.info("Routing fees estimated: '$it'", context = TAG)
        }.onFailure {
            Logger.error("estimateRoutingFees error", it, context = TAG)
        }
    }

    suspend fun estimateRoutingFeesForAmount(bolt11: String, amountSats: ULong): Result<ULong> =
        executeWhenNodeRunning("estimateRoutingFeesForAmount") {
            if (shouldPayWithSpark(bolt11, amountSats)) {
                return@executeWhenNodeRunning sparkRepo.estimateFeeSats(bolt11, amountSats)
            }
            Logger.info("Estimating routing fees for amount: '$amountSats'", context = TAG)
            lightningService.estimateRoutingFeesForAmount(bolt11, amountSats).onSuccess {
                Logger.info("Routing fees estimated: '$it'", context = TAG)
            }.onFailure {
                Logger.error("estimateRoutingFeesForAmount error", it, context = TAG)
            }
        }

    /**
     * What a Max send of the amountless [bolt11] keeps below [maxSendSats] for its fees: Spark's quote for sending its
     * whole balance when Spark pays it, otherwise the routing fee for [maxSendSats] with a small buffer.
     */
    suspend fun estimateMaxSendFee(bolt11: String, maxSendSats: ULong): Result<ULong> {
        if (shouldPayWithSpark(bolt11, maxSendSats)) {
            sparkRepo.maxSendReserveSats(bolt11)
                .onSuccess { return Result.success(it) }
                .onFailure { Logger.warn("Failed to price a Max Spark send", it, context = TAG) }
        }
        val amountSats = maxSendSats.safe() - MAX_SEND_FEE_BUFFER_SATS.safe()
        return estimateRoutingFeesForAmount(bolt11, amountSats).map { it.safe() + MAX_SEND_FEE_BUFFER_SATS.safe() }
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
            // Gate the destructive VSS deletes on the previous node's release, so the old node cannot
            // re-persist scores over the delete while it is still draining.
            lightningService.awaitNodeRelease()
            vssBackupClientLdk.setup(walletIndex).getOrThrow()
            vssBackupClientLdk.deleteObject(VSS_KEY_SCORER).getOrThrow()
            vssBackupClientLdk.deleteObject(VSS_KEY_EXTERNAL_SCORES_CACHE).getOrThrow()
        }.onFailure {
            Logger.error("Failed to delete pathfinding scores from VSS", it, context = TAG)
            start(walletIndex = walletIndex, shouldRetry = false, shouldValidateGraph = false).onFailure { startError ->
                Logger.error("Failed to restart node after pathfinding scores reset failure", startError, context = TAG)
            }
            return@withContext Result.failure(it)
        }

        val resetAtSecs = nowMillis() / 1000

        start(walletIndex = walletIndex, shouldRetry = false, shouldValidateGraph = false)
            .map { resetAtSecs }
            .onSuccess {
                Logger.info("Pathfinding scores reset at '$resetAtSecs'", context = TAG)
            }
    }

    suspend fun resetPaymentRoutingCachesAndWait(walletIndex: Int = 0): Result<Unit> = withContext(bgDispatcher) {
        val refreshStartedAtMs = nowMillis()
        val refreshStartedAtSecs = (refreshStartedAtMs / 1000).toULong()
        val requiresRgsRefresh = Env.network != Network.REGTEST &&
            !settingsStore.data.first().rgsServerUrl.isNullOrEmpty()
        val requiresScorerRefresh = Env.ldkScorerUrl != null
        val resetErrors = mutableListOf<Throwable>()

        Logger.info(
            "Started payment routing refresh rgs='$requiresRgsRefresh' scorer='$requiresScorerRefresh'",
            context = TAG,
        )

        val resetResult = withContext(NonCancellable) reset@{
            stop().onFailure {
                start(
                    walletIndex = walletIndex,
                    shouldRetry = false,
                    shouldValidateGraph = false,
                ).onFailure { startError ->
                    Logger.error(
                        "Failed to restart node after payment routing refresh stop failure",
                        startError,
                        context = TAG,
                    )
                }
                return@reset Result.failure(it)
            }

            clearNetworkGraph(walletIndex).onFailure {
                resetErrors.add(it)
            }

            resetPathfindingScores(walletIndex).onFailure {
                resetErrors.add(it)
            }

            resetErrors.firstOrNull()?.let {
                Result.failure(it)
            } ?: Result.success(Unit)
        }
        resetResult.onFailure {
            return@withContext Result.failure(it)
        }

        val result = waitForPaymentRoutingDataRefresh(
            walletIndex = walletIndex,
            refreshStartedAtMs = refreshStartedAtMs,
            refreshStartedAtSecs = refreshStartedAtSecs,
            requiresRgsRefresh = requiresRgsRefresh,
            requiresScorerRefresh = requiresScorerRefresh,
        )
        result.onSuccess {
            Logger.info(
                "Finished payment routing refresh elapsedMs='${nowMillis() - refreshStartedAtMs}'",
                context = TAG,
            )
        }
    }

    private suspend fun waitForPaymentRoutingDataRefresh(
        walletIndex: Int,
        refreshStartedAtMs: Long,
        refreshStartedAtSecs: ULong,
        requiresRgsRefresh: Boolean,
        requiresScorerRefresh: Boolean,
    ): Result<Unit> = withContext(bgDispatcher) {
        if (!requiresRgsRefresh && !requiresScorerRefresh) {
            Logger.info(
                "Skipped payment routing refresh wait because no routing sources are required",
                context = TAG,
            )
            return@withContext Result.success(Unit)
        }

        var lastStatus: PaymentRoutingRefreshStatus? = null
        val refreshed = withTimeoutOrNull(PAYMENT_ROUTING_REFRESH_TIMEOUT) {
            while (isActive) {
                syncState()
                val status = _lightningState.value.paymentRoutingRefreshStatus(
                    graphCacheModificationDate = lightningService.networkGraphCacheModificationDate(walletIndex),
                    networkGraphInfo = getNetworkGraphInfo(),
                    refreshStartedAtSecs = refreshStartedAtSecs,
                    requiresRgsRefresh = requiresRgsRefresh,
                    requiresScorerRefresh = requiresScorerRefresh,
                )
                lastStatus = status
                if (status.isFresh) {
                    return@withTimeoutOrNull true
                }
                delay(PAYMENT_ROUTING_REFRESH_POLL_DELAY)
            }
            false
        } == true

        if (refreshed) {
            Result.success(Unit)
        } else {
            Logger.warn(
                "Timed out payment routing refresh elapsedMs='${nowMillis() - refreshStartedAtMs}' " +
                    lastStatus?.toLogFields().orEmpty(),
                context = TAG,
            )
            Result.failure(PaymentRoutingRefreshTimeoutError())
        }
    }
    // endregion

    /**
     * Runs [restartNode] on the repo scope, for the same reason [stopDebounced] does: the node
     * lifecycle outlives any screen that asks to change it. A caller cancelled mid-restart — a
     * ViewModel cleared inside the bounded start retry delay — cannot drop the retry and strand the
     * node Stopped.
     *
     * The caller still awaits the [Result], so a live caller keeps reporting the outcome; a cancelled
     * one only loses the reporting, never the restart.
     */
    suspend fun restartNodeDetached(): Result<Unit> = scope.async { restartNode() }.await()

    suspend fun restartNode(
        channelMigration: ChannelDataMigration? = null,
        walletIndex: Int = 0,
    ): Result<Unit> = withContext(bgDispatcher) {
        Logger.info("Restarting node", context = TAG)
        stop().onFailure {
            Logger.error("Failed to stop node during restart", it, context = TAG)
            return@withContext Result.failure(it)
        }
        startNode(
            walletIndex = walletIndex,
            channelMigration = channelMigration,
            shouldRetryYieldToStop = true
        ).onFailure {
            if (it is NodeStartYieldedToStopError) {
                Logger.info("Deferred node restart to a requested stop", context = TAG)
            } else {
                Logger.error("Failed to start node during restart", it, context = TAG)
            }
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
        private const val MSATS_PER_SAT = 1_000uL

        /** LDK's default routing fee limit is 1% of the amount, this divisor, plus [DEFAULT_ROUTING_FEE_BASE_MSAT]. */
        private const val DEFAULT_ROUTING_FEE_DIVISOR = 100uL

        /** The flat 50 sats in LDK's default routing fee limit. */
        private const val DEFAULT_ROUTING_FEE_BASE_MSAT = 50_000uL

        /** Inbound the node's ready channels need before an amountless contact endpoint goes to the node, not Spark. */
        private const val AMOUNTLESS_NODE_INBOUND_SATS = 1_000_000uL

        /** Sats a Max Lightning send keeps beyond its estimated routing fee, as routes can price slightly higher. */
        private const val MAX_SEND_FEE_BUFFER_SATS = 2uL
        private val BACKGROUND_STOP_DELAY = 5.seconds
        private val CHANNELS_USABLE_TIMEOUT = 15.seconds
        private val CHANNELS_USABLE_POLL_DELAY = 1.seconds
        private val ABANDON_SYNC_TIMEOUT = 30.seconds
        private val NO_USABLE_CHANNELS_FEEDBACK_DELAY = 2_500.milliseconds

        /** Max time to wait for a starting node before its id is treated as unavailable. */
        private val NODE_ID_WAIT_TIMEOUT = 15.seconds

        val SEND_LN_TIMEOUT = 10.seconds
        private val PROBE_TIMEOUT = 60.seconds
        private val PAYMENT_ROUTING_REFRESH_TIMEOUT = 20.seconds
        private val PAYMENT_ROUTING_REFRESH_POLL_DELAY = 500.milliseconds
    }
}

private fun LightningState.paymentRoutingRefreshStatus(
    graphCacheModificationDate: Long?,
    networkGraphInfo: NetworkGraphInfo?,
    refreshStartedAtSecs: ULong,
    requiresRgsRefresh: Boolean,
    requiresScorerRefresh: Boolean,
): PaymentRoutingRefreshStatus {
    val status = nodeStatus
    val nodeRunning = nodeLifecycleState.isRunning()
    val graphNodeCount = networkGraphInfo?.nodeCount
    val graphChannelCount = networkGraphInfo?.channelCount
    val hasInMemoryGraph = (graphNodeCount ?: 0) > 0 && (graphChannelCount ?: 0) > 0

    val hasFreshRgs = !requiresRgsRefresh ||
        graphCacheModificationDate != null && (graphCacheModificationDate / 1000).toULong() >= refreshStartedAtSecs ||
        hasInMemoryGraph

    val latestScoresTimestamp = status?.latestPathfindingScoresSyncTimestamp
    val hasFreshScores = !requiresScorerRefresh ||
        latestScoresTimestamp != null && latestScoresTimestamp >= refreshStartedAtSecs

    return PaymentRoutingRefreshStatus(
        nodeRunning = nodeRunning,
        graphFresh = hasFreshRgs,
        scorerFresh = hasFreshScores,
        graphCacheModificationDate = graphCacheModificationDate,
        graphNodeCount = graphNodeCount,
        graphChannelCount = graphChannelCount,
        latestPathfindingScoresSyncTimestamp = latestScoresTimestamp,
        refreshStartedAtSecs = refreshStartedAtSecs,
    )
}

private data class PaymentRoutingRefreshStatus(
    val nodeRunning: Boolean,
    val graphFresh: Boolean,
    val scorerFresh: Boolean,
    val graphCacheModificationDate: Long?,
    val graphNodeCount: Int?,
    val graphChannelCount: Int?,
    val latestPathfindingScoresSyncTimestamp: ULong?,
    val refreshStartedAtSecs: ULong,
) {
    val isFresh: Boolean = nodeRunning && graphFresh && scorerFresh

    fun toLogFields(): String {
        return "nodeRunning='$nodeRunning' graphFresh='$graphFresh' scorerFresh='$scorerFresh' " +
            "graphMtime='${graphCacheModificationDate ?: "-"}' " +
            "graphMtimeSecs='${graphCacheModificationDate?.let { it / 1000 } ?: "-"}' " +
            "graphNodes='${graphNodeCount ?: "-"}' " +
            "graphChannels='${graphChannelCount ?: "-"}' " +
            "scorerTs='${latestPathfindingScoresSyncTimestamp ?: "-"}' " +
            "refreshStartedAtSecs='$refreshStartedAtSecs'"
    }
}

class RecoveryModeError : AppError("App in recovery mode, skipping node start")

class WipeInProgressError : AppError("Wallet wipe in progress, refusing node start")
class NodeSetupError : AppError("Unknown node setup error")
class NodeStopTimeoutError : AppError("Timeout waiting for node to stop")
class NodeStartYieldedToStopError(cause: Throwable?) :
    AppError("Node start retry skipped because a stop was requested", cause)
class NodeConfigNotAppliedError : AppError("Node already running, requested config was not applied")
class NodeRunTimeoutError(opName: String) : AppError("Timeout waiting for node to run and execute: '$opName'")
class NodeNotRunningError(opName: String, state: NodeLifecycleState) :
    AppError("Cannot execute '$opName': node is '$state' and not starting")
class GetPaymentsError : AppError("It wasn't possible get the payments")
class SyncUnhealthyError : AppError("Wallet sync failed before send")
class PaymentAbortedBeforeSend : AppError("Payment aborted before send")
class LnurlPayInvoiceMismatchError : AppError("The invoice did not match the requested payment. Payment cancelled.")
class PaymentRoutingRefreshTimeoutError : AppError("Timeout waiting for payment routing data refresh")

data class NodeEventUpdate(
    val event: Event,
    val settledReceiveInvoice: SettledReceiveInvoice? = null,
    val settledReceiveAddress: SettledReceiveAddress? = null,
)

data class SettledReceiveInvoice(
    val bolt11: String,
)

data class SettledReceiveAddress(
    val address: String,
)

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
        val routeFeeMsat: ULong?,
    ) : ProbeOutcome

    data class Failure(
        override val paymentId: PaymentId,
        override val paymentHash: PaymentHash,
        val shortChannelId: String?,
        val routeFeeMsat: ULong?,
    ) : ProbeOutcome
}

/**
 * The sync requested for a check proves a fresh view once it succeeds. Otherwise only a sync that finished later than
 * the one read before the request counts; one finished in the same second may predate it.
 */
internal fun isOnchainSyncAfter(latest: ULong?, previous: ULong, requestedSyncSucceeded: Boolean = false): Boolean =
    requestedSyncSucceeded || (latest ?: 0uL) > previous

/** The Lightning rail a send went out on. */
enum class LightningRail { Ldk, Spark }

/** A pending or paid send of an invoice on one rail, which a retry routed to the other must not repeat. */
sealed interface EarlierSend {
    val paymentHash: String

    data class Pending(override val paymentHash: String) : EarlierSend

    data class Paid(override val paymentHash: String) : EarlierSend
}

/** Spark invoices hold whole sats, so an exact msat amount the channels can't receive has nowhere to go. */
class SubSatAmountUnsupportedError(amountMsats: ULong) :
    AppError("Spark can only receive whole sats, not '$amountMsats' msat, and the channels can't receive it")
