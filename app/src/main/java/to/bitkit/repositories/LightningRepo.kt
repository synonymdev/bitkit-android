package to.bitkit.repositories

import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.getLnurlInvoice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.SpendableUtxo
import org.lightningdevkit.ldknode.Txid
import org.lightningdevkit.ldknode.UserChannelId
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.getSatsPerVByteFor
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.ElectrumServer
import to.bitkit.models.LnPeer
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.toCoinSelectAlgorithm
import to.bitkit.services.BlocktankNotificationsService
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.LightningService
import to.bitkit.services.NodeEventHandler
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
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
    private val cacheStore: CacheStore,
) {
    private val _lightningState = MutableStateFlow(LightningState())
    val lightningState = _lightningState.asStateFlow()

    private var cachedEventHandler: NodeEventHandler? = null

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
        operation: suspend () -> Result<T>,
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
        customServer: ElectrumServer? = null,
        customRgsServerUrl: String? = null,
    ) = withContext(bgDispatcher) {
        return@withContext try {
            lightningService.setup(walletIndex, customServer, customRgsServerUrl)
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
        eventHandler: NodeEventHandler? = null,
        customServer: ElectrumServer? = null,
        customRgsServerUrl: String? = null,
    ): Result<Unit> = withContext(bgDispatcher) {
        val initialLifecycleState = _lightningState.value.nodeLifecycleState
        if (initialLifecycleState.isRunningOrStarting()) {
            Logger.info("LDK node start skipped, lifecycle state: $initialLifecycleState", context = TAG)
            return@withContext Result.success(Unit)
        }

        try {
            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Starting) }

            // Setup if not already setup
            if (lightningService.node == null) {
                val setupResult = setup(walletIndex, customServer, customRgsServerUrl)
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

            this@LightningRepo.cachedEventHandler = eventHandler

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
                _lightningState.update { it.copy(nodeLifecycleState = initialLifecycleState) }

                delay(2.seconds)
                return@withContext start(
                    walletIndex = walletIndex,
                    timeout = timeout,
                    shouldRetry = false,
                    eventHandler = eventHandler,
                    customServer = customServer,
                    customRgsServerUrl = customRgsServerUrl,
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
                _lightningState.update {
                    LightningState(
                        nodeStatus = it.nodeStatus,
                        nodeLifecycleState = it.nodeLifecycleState,
                    )
                }
                Result.success(Unit)
            } catch (e: Throwable) {
                Logger.error("Wipe storage error", e, context = TAG)
                Result.failure(e)
            }
        }.onFailure { e ->
            return@withContext Result.failure(e)
        }
    }

    suspend fun restartWithElectrumServer(newServer: ElectrumServer): Result<Unit> = withContext(bgDispatcher) {
        Logger.info("Changing ldk-node electrum server to: $newServer")

        waitForNodeToStop().onFailure { return@withContext Result.failure(it) }
        stop().onFailure {
            Logger.error("Failed to stop node during electrum server change", it)
            return@withContext Result.failure(it)
        }

        Logger.debug("Starting node with new electrum server: $newServer")

        start(
            eventHandler = cachedEventHandler,
            customServer = newServer,
            shouldRetry = false,
        ).onFailure { startError ->
            Logger.warn("Failed ldk-node config change, attempting recovery…")
            restartWithPreviousConfig()
            return@withContext Result.failure(startError)
        }.onSuccess {
            settingsStore.update { it.copy(electrumServer = newServer) }

            Logger.info("Successfully changed electrum server connection")
            return@withContext Result.success(Unit)
        }
    }

    suspend fun restartWithRgsServer(newRgsUrl: String): Result<Unit> = withContext(bgDispatcher) {
        Logger.info("Changing ldk-node RGS server to: $newRgsUrl")

        waitForNodeToStop().onFailure { return@withContext Result.failure(it) }
        stop().onFailure {
            Logger.error("Failed to stop node during RGS server change", it)
            return@withContext Result.failure(it)
        }

        Logger.debug("Starting node with new RGS server: $newRgsUrl")

        start(
            eventHandler = cachedEventHandler,
            shouldRetry = false,
            customRgsServerUrl = newRgsUrl,
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
            eventHandler = cachedEventHandler,
            shouldRetry = false,
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
        expirySeconds: UInt = 86_400u,
    ): Result<String> = executeWhenNodeRunning("Create invoice") {

        if (coreService.shouldBlockLightning()) {
            return@executeWhenNodeRunning Result.failure(ServiceError.GeoBlocked)
        }

        val invoice = lightningService.receive(amountSats, description, expirySeconds)
        Result.success(invoice)
    }

    suspend fun createLnurlInvoice(
        address: String,
        amountSatoshis: ULong,
    ): Result<String> = executeWhenNodeRunning("getLnUrlInvoice") {
        val invoice = getLnurlInvoice(address, amountSatoshis)
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
     * @param utxosToSpend Manually specify UTXO's to spend if not null.
     * @return A `Result` with the `Txid` of sent transaction, or an error if the transaction fails
     * or the fee rate cannot be retrieved.
     */
    suspend fun sendOnChain(
        address: Address,
        sats: ULong,
        speed: TransactionSpeed? = null,
        utxosToSpend: List<SpendableUtxo>? = null,
    ): Result<Txid> =
        executeWhenNodeRunning("Send on-chain") {
            val transactionSpeed = speed ?: settingsStore.data.map { it.defaultTransactionSpeed }.first()
            val fees = coreService.blocktank.getFees().getOrThrow()
            val satsPerVByte = fees.getSatsPerVByteFor(transactionSpeed)

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
            )
            syncState()
            Result.success(txId)
        }

    private suspend fun determineUtxosToSpend(
        sats: ULong,
        satsPerVByte: UInt,
    ): List<SpendableUtxo>? {
        return runCatching {
            val settings = settingsStore.data.first()
            if (settings.coinSelectAuto) {
                val coinSelectionPreference = settings.coinSelectPreference

                val allSpendableUtxos = lightningService.listSpendableOutputs().getOrThrow()

                if (coinSelectionPreference == CoinSelectionPreference.Consolidate) {
                    Logger.info("Consolidating by spending all ${allSpendableUtxos.size} UTXOs", context = TAG)
                    return allSpendableUtxos
                }

                val coinSelectionAlgorithm = coinSelectionPreference.toCoinSelectAlgorithm().getOrThrow()

                Logger.info("Selecting UTXOs with algorithm: $coinSelectionAlgorithm for sats: $sats", context = TAG)
                Logger.debug("All spendable UTXOs: $allSpendableUtxos", context = TAG)

                lightningService.selectUtxosWithAlgorithm(
                    targetAmountSats = sats,
                    algorithm = coinSelectionAlgorithm,
                    satsPerVByte = satsPerVByte,
                    utxos = allSpendableUtxos,
                ).getOrThrow()
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

    suspend fun listSpendableOutputs(): Result<List<SpendableUtxo>> = executeWhenNodeRunning("List spendable outputs") {
        lightningService.listSpendableOutputs()
    }

    suspend fun calculateTotalFee(
        amountSats: ULong,
        address: Address? = null,
        speed: TransactionSpeed? = null,
        utxosToSpend: List<SpendableUtxo>? = null,
    ): Result<ULong> = withContext(bgDispatcher) {
        return@withContext try {
            val transactionSpeed = speed ?: settingsStore.data.first().defaultTransactionSpeed
            val satsPerVByte = getFeeRateForSpeed(transactionSpeed).getOrThrow().toUInt()

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

    suspend fun getFeeRateForSpeed(speed: TransactionSpeed): Result<ULong> = withContext(bgDispatcher) {
        return@withContext runCatching {
            val fees = coreService.blocktank.getFees().getOrThrow()
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
        peer: LnPeer,
        channelAmountSats: ULong,
        pushToCounterpartySats: ULong? = null,
    ): Result<UserChannelId> = executeWhenNodeRunning("Open channel") {
        val result = lightningService.openChannel(peer, channelAmountSats, pushToCounterpartySats)
        syncState()
        result
    }

    suspend fun closeChannel(
        channel: ChannelDetails,
        force: Boolean = false,
        forceCloseReason: String? = null,
    ): Result<Unit> = executeWhenNodeRunning("Close channel") {
        Logger.info("Closing channel (force=$force): ${channel.channelId}")

        lightningService.closeChannel(
            userChannelId = channel.userChannelId,
            counterpartyNodeId = channel.counterpartyNodeId,
            force = force,
            forceCloseReason = forceCloseReason,
        )
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

    fun getNodeId(): String? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.nodeId else null

    fun getBalances(): BalanceDetails? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.balances else null

    fun getStatus(): NodeStatus? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.status else null

    fun getPeers(): List<LnPeer>? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.peers else null

    fun getChannels(): List<ChannelDetails>? =
        if (_lightningState.value.nodeLifecycleState.isRunning()) lightningService.channels else null

    fun hasChannels(): Boolean =
        _lightningState.value.nodeLifecycleState.isRunning() && lightningService.channels?.isNotEmpty() == true

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
            Logger.debug("bumpFeeByRbf success, replacementTxId: $replacementTxId originalTxId: $originalTxId, satsPerVByte: $satsPerVByte")
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
            Logger.debug("accelerateByCpfp success, newDestinationTxId: $newDestinationTxId originalTxId: $originalTxId, satsPerVByte: $satsPerVByte destinationAddress: $destinationAddress")
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
    val shouldBlockLightning: Boolean = false,
)
