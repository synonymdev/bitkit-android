package to.bitkit.repositories

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.Txid
import org.lightningdevkit.ldknode.UserChannelId
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.getSatsPerVByteFor
import to.bitkit.models.LnPeer
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.TransactionSpeed
import to.bitkit.services.BlocktankNotificationsService
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.LightningService
import to.bitkit.services.NodeEventHandler
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import uniffi.bitkitcore.IBtInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Singleton
class LightningRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningService: LightningService,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val settingsStore: SettingsStore,
    private val coreService: CoreService,
    private val blocktankNotificationsService: BlocktankNotificationsService,
    private val firebaseMessaging: FirebaseMessaging,
    private val keychain: Keychain,
) {
    private val _lightningState = MutableStateFlow(LightningState())
    val lightningState = _lightningState.asStateFlow()

    /**
     * Executes the provided operation only if the node is running.
     * If the node is not running, waits for it to be running for a specified timeout.
     *
     * @param operationName Name of the operation for logging
     * @param waitTimeout Duration to wait for the node to be running
     * @param operation Lambda to execute when the node is running
     * @return Result of the operation, or failure if node isn't running or operation fails
     */
    private suspend fun <T> executeWhenNodeRunning(
        operationName: String,
        waitTimeout: Duration = 1.minutes,
        operation: suspend () -> Result<T>
    ): Result<T> = withContext(bgDispatcher) {
        Logger.debug("Operation called: $operationName", context = TAG)

        if (_lightningState.value.nodeLifecycleState.isRunning()) {
            return@withContext executeOperation(operationName, operation)
        }

        // If node is not in a state that can become running, fail fast
        if (!_lightningState.value.nodeLifecycleState.canRun()) {
            return@withContext Result.failure(
                Exception("Cannot execute $operationName: Node is ${_lightningState.value.nodeLifecycleState} and not starting")
            )
        }

        val nodeRunning = withTimeoutOrNull(waitTimeout) {
            if (_lightningState.value.nodeLifecycleState.isRunning()) {
                return@withTimeoutOrNull true
            }

            // Otherwise, wait for it to transition to running state
            Logger.debug("Waiting for node runs to execute $operationName", context = TAG)
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
        operation: suspend () -> Result<T>
    ): Result<T> {
        return try {
            operation()
        } catch (e: Throwable) {
            Logger.error("$operationName error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun setup(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        return@withContext try {
            lightningService.setup(walletIndex)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Node setup error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun start(
        walletIndex: Int = 0,
        timeout: Duration? = null,
        shouldRetry: Boolean = true,
        eventHandler: NodeEventHandler? = null
    ): Result<Unit> = withContext(bgDispatcher) {
        if (_lightningState.value.nodeLifecycleState.isRunningOrStarting()) {
            return@withContext Result.success(Unit)
        }

        try {
            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Starting) }

            // Setup if not already setup
            if (lightningService.node == null) {
                val setupResult = setup(walletIndex)
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
                lightningService.listenForEvents(onEvent = eventHandler)
                return@withContext Result.success(Unit)
            }

            // Start the node service
            lightningService.start(timeout) { event ->
                eventHandler?.invoke(event)
                ldkNodeEventBus.emit(event)
            }

            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }

            // Initial state sync
            syncState()

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
                delay(2.seconds)
                return@withContext start(
                    walletIndex = walletIndex,
                    timeout = timeout,
                    shouldRetry = false,
                    eventHandler = eventHandler
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
            _lightningState.update { LightningState() }
            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Stopped) }
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Node stop error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun sync(): Result<Unit> = executeWhenNodeRunning("Sync") {
        syncState()
        if (_lightningState.value.isSyncingWallet) {
            Logger.warn("Sync already in progress, waiting for existing sync.")
            return@executeWhenNodeRunning Result.success(Unit)
        }

        _lightningState.update { it.copy(isSyncingWallet = true) }
        lightningService.sync()
        syncState()
        _lightningState.update { it.copy(isSyncingWallet = false) }

        Result.success(Unit)
    }

    suspend fun wipeStorage(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("wipeStorage called, stopping node first", context = TAG)
        stop().onSuccess {
            return@withContext try {
                Logger.debug("node stopped, calling wipeStorage", context = TAG)
                lightningService.wipeStorage(walletIndex)
                _lightningState.update { LightningState(nodeStatus = it.nodeStatus, nodeLifecycleState = it.nodeLifecycleState) }
                Result.success(Unit)
            } catch (e: Throwable) {
                Logger.error("Wipe storage error", e, context = TAG)
                Result.failure(e)
            }
        }.onFailure { e ->
            return@withContext Result.failure(e)
        }
    }

    suspend fun connectToTrustedPeers(): Result<Unit> = executeWhenNodeRunning("Connect to trusted peers") {
        lightningService.connectToTrustedPeers()
        Result.success(Unit)
    }

    suspend fun disconnectPeer(peer: LnPeer): Result<Unit> = executeWhenNodeRunning("Disconnect peer") {
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
        expirySeconds: UInt = 86_400u
    ): Result<Bolt11Invoice> = executeWhenNodeRunning("Create invoice") {

        if (coreService.shouldBlockLightning()) {
            return@executeWhenNodeRunning Result.failure(ServiceError.GeoBlocked)
        }

        val invoice = lightningService.receive(amountSats, description, expirySeconds)
        Result.success(invoice)
    }

    suspend fun payInvoice(bolt11: String, sats: ULong? = null): Result<PaymentId> =
        executeWhenNodeRunning("Pay invoice") {
            val paymentId = lightningService.send(bolt11 = bolt11, sats = sats)
            syncState()
            Result.success(paymentId)
        }

    /**
     * Sends bitcoin to an on-chain address
     *
     * @param address The bitcoin address to send to
     * @param sats The amount in  satoshis to send
     * @param speed The desired transaction speed determining the fee rate. If null, the user's default speed is used.
     * @return A `Result` with the `Txid` of sent transaction, or an error if the transaction fails
     * or the fee rate cannot be retrieved.
     */
    suspend fun sendOnChain(address: Address, sats: ULong, speed: TransactionSpeed? = null): Result<Txid> =
        executeWhenNodeRunning("Send on-chain") {
            val transactionSpeed = speed ?: settingsStore.defaultTransactionSpeed.first()

            var fees = coreService.blocktank.getFees().getOrThrow()
            var satsPerVByte = fees.getSatsPerVByteFor(transactionSpeed)

            val txId = lightningService.send(address = address, sats = sats, satsPerVByte = satsPerVByte)
            syncState()
            Result.success(txId)
        }

    suspend fun getPayments(): Result<List<PaymentDetails>> = executeWhenNodeRunning("Get payments") {
        val payments = lightningService.payments
            ?: return@executeWhenNodeRunning Result.failure(Exception("It wasn't possible get the payments"))
        Result.success(payments)
    }

    suspend fun openChannel(
        peer: LnPeer,
        channelAmountSats: ULong,
        pushToCounterpartySats: ULong? = null
    ): Result<UserChannelId> = executeWhenNodeRunning("Open channel") {
        val result = lightningService.openChannel(peer, channelAmountSats, pushToCounterpartySats)
        syncState()
        result
    }

    suspend fun closeChannel(userChannelId: String, counterpartyNodeId: String): Result<Unit> =
        executeWhenNodeRunning("Close channel") {
            lightningService.closeChannel(userChannelId, counterpartyNodeId)
            syncState()
            Result.success(Unit)
        }



    suspend fun syncState() {
        _lightningState.update {
            it.copy(
                nodeId = getNodeId().orEmpty(),
                nodeStatus = getStatus(),
                peers = getPeers().orEmpty(),
                channels = getChannels().orEmpty(),
                shouldBlockLightning = coreService.shouldBlockLightning()
            )
        }
    }

    fun canSend(amountSats: ULong): Boolean =
        _lightningState.value.nodeLifecycleState.isRunning() && lightningService.canSend(amountSats)

    fun getSyncFlow(): Flow<Unit> = lightningService.syncFlow()

    fun getNodeId(): String? = if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.nodeId else null

    fun getBalances(): BalanceDetails? = if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.balances else null

    fun getStatus(): NodeStatus? = if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.status else null

    fun getPeers(): List<LnPeer>? = if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.peers else null

    fun getChannels(): List<ChannelDetails>? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.channels else null

    fun hasChannels(): Boolean = _lightningState.value.nodeLifecycleState.isRunning() && lightningService.channels?.isNotEmpty() == true

    // Notification handling
    suspend fun getFcmToken(): Result<String> = withContext(bgDispatcher) {
        try {
            val token = firebaseMessaging.token.await()
            Result.success(token)
        } catch (e: Throwable) {
            Logger.error("Get FCM token error", e)
            Result.failure(e)
        }
    }

    suspend fun registerForNotifications(): Result<Unit> = executeWhenNodeRunning("Register for notifications") {
        return@executeWhenNodeRunning try {
            val token = firebaseMessaging.token.await()
            val cachedToken = keychain.loadString(Keychain.Key.PUSH_NOTIFICATION_TOKEN.name)

            if (cachedToken == token) {
                Logger.debug("Skipped registering for notifications, current device token already registered")
                return@executeWhenNodeRunning Result.success(Unit)
            }

            blocktankNotificationsService.registerDevice(token)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Register for notifications error", e)
            Result.failure(e)
        }
    }

    suspend fun testNotification(): Result<Unit> = executeWhenNodeRunning("Test notification") {
        try {
            val token = firebaseMessaging.token.await()
            blocktankNotificationsService.testNotification(token)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Test notification error", e)
            Result.failure(e)
        }
    }

    suspend fun getBlocktankInfo(): Result<IBtInfo> = withContext(bgDispatcher) {
        try {
            val info = coreService.blocktank.info(refresh = true)
                ?: return@withContext Result.failure(Exception("Couldn't get info"))
            Result.success(info)
        } catch (e: Throwable) {
            Logger.error("Blocktank info error", e)
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "LightningRepo"
    }
}

data class LightningState(
    val nodeId: String = "",
    val nodeStatus: NodeStatus? = null,
    val nodeLifecycleState: NodeLifecycleState = NodeLifecycleState.Stopped,
    val peers: List<LnPeer> = emptyList(),
    val channels: List<ChannelDetails> = emptyList(),
    val isSyncingWallet: Boolean = false,
    val shouldBlockLightning: Boolean = false
)
