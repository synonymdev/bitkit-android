package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.Address
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.Txid
import org.lightningdevkit.ldknode.UserChannelId
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.BalanceState
import to.bitkit.models.LnPeer
import to.bitkit.models.NodeLifecycleState
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.LightningService
import to.bitkit.services.NodeEventHandler
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Singleton
class LightningRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningService: LightningService,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val addressChecker: AddressChecker,
    private val walletRepo: WalletRepo //TODO REVERSE DEPENDENCY
) {
    private val _lightningState = MutableStateFlow(LightningState())
    val lightningState = _lightningState.asStateFlow()

    val nodeLifecycleState = _lightningState.asStateFlow().map { it.nodeLifecycleState }

    private val _balanceState = MutableStateFlow<BalanceState?>(null)
    val balanceState = _balanceState.asStateFlow()

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
        walletIndex: Int,
        timeout: Duration? = null,
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

            // Start the node service
            lightningService.start(timeout) { event ->
                eventHandler?.invoke(event)
                ldkNodeEventBus.emit(event)
                refreshBip21ForEvent(event)
            }

            _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }

            // Initial state sync
            syncState()

            // Perform post-startup tasks
            connectToTrustedPeers().onFailure { e ->
                Logger.error("Failed to connect to trusted peers", e)
            }

            // Refresh BIP21 and synchronize
            refreshBip21()
            sync()
            registerForNotificationsIfNeeded()

            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Node start error", e, context = TAG)
            _lightningState.update {
                it.copy(nodeLifecycleState = NodeLifecycleState.ErrorStarting(e))
            }
            Result.failure(e)
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
            executeWhenNodeRunning("stop") {
                _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Stopping) }
                lightningService.stop()
                _lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Stopped) }
                Result.success(Unit)
            }
        } catch (e: Throwable) {
            Logger.error("Node stop error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun sync(): Result<Unit> = executeWhenNodeRunning("Sync") {
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
        stop().onSuccess {
            return@withContext try {
                lightningService.wipeStorage(walletIndex)
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

    suspend fun checkAddressUsage(address: String): Result<Boolean> = executeWhenNodeRunning("Check address usage") {
        val addressInfo = addressChecker.getAddressInfo(address)
        val hasTransactions = addressInfo.chain_stats.tx_count > 0 || addressInfo.mempool_stats.tx_count > 0
        Result.success(hasTransactions)
    }

    suspend fun createInvoice(
        amountSats: ULong? = null,
        description: String,
        expirySeconds: UInt = 86_400u
    ): Result<Bolt11Invoice> = executeWhenNodeRunning("Create invoice") {
        val invoice = lightningService.receive(amountSats, description, expirySeconds)
        Result.success(invoice)
    }

    suspend fun payInvoice(bolt11: String, sats: ULong? = null): Result<PaymentId> =
        executeWhenNodeRunning("Pay invoice") {
            val paymentId = lightningService.send(bolt11 = bolt11, sats = sats)
            syncState()
            Result.success(paymentId)
        }

    suspend fun sendOnChain(address: Address, sats: ULong): Result<Txid> =
        executeWhenNodeRunning("Send on-chain") {
            val txId = lightningService.send(address = address, sats = sats)
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

    suspend fun registerForNotificationsIfNeeded(): Result<Unit> = withContext(bgDispatcher) {
        walletRepo.registerForNotifications()
            .onFailure { e ->
                Logger.error("Failed to register device for notifications", e)
            }
    }

    suspend fun refreshBip21(): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("Refreshing bip21", context = "LightningRepo")

        // Check current address or generate new one
        val currentAddress = walletRepo.getOnchainAddress()
        if (currentAddress.isEmpty()) {
            newAddress()
                .onSuccess { address -> walletRepo.setOnchainAddress(address) }
                .onFailure { error -> Logger.error("Error generating new address", error) }
        } else {
            // Check if current address has been used
            checkAddressUsage(currentAddress)
                .onSuccess { hasTransactions ->
                    if (hasTransactions) {
                        // Address has been used, generate a new one
                        newAddress()
                            .onSuccess { address -> walletRepo.setOnchainAddress(address) }
                    }
                }
        }

        updateBip21Invoice()

        return@withContext Result.success(Unit)
    }

    private suspend fun refreshBip21ForEvent(event: Event) {
        when (event) {
            is Event.PaymentReceived, is Event.ChannelReady, is Event.ChannelClosed -> refreshBip21()
            else -> Unit
        }
    }

    suspend fun updateBip21Invoice(
        amountSats: ULong? = null,
        description: String = "",
        generateBolt11IfAvailable: Boolean = true,
        tags: List<String> = emptyList()
    ): Result<Unit> = withContext(bgDispatcher) {
        try {
            // Update state
            _lightningState.update {
                it.copy(
                    bip21AmountSats = amountSats,
                    bip21Description = description
                )
            }

            val hasChannels = hasChannels()

            if (hasChannels && generateBolt11IfAvailable) {
                createInvoice(
                    amountSats = _lightningState.value.bip21AmountSats,
                    description = _lightningState.value.bip21Description
                ).onSuccess { bolt11 ->
                    walletRepo.setBolt11(bolt11)
                }
            } else {
                walletRepo.setBolt11("")
            }

            val newBip21 = walletRepo.buildBip21Url(
                bitcoinAddress = walletRepo.getOnchainAddress(),
                amountSats = _lightningState.value.bip21AmountSats,
                message = description.ifBlank { Env.DEFAULT_INVOICE_MESSAGE },
                lightningInvoice = walletRepo.getBolt11()
            )
            walletRepo.setBip21(newBip21)
            walletRepo.saveInvoiceWithTags(bip21Invoice = newBip21, tags = tags)

            _lightningState.update { it.copy(selectedTags = emptyList(), bip21Description = "") }

            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Update BIP21 invoice error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun syncState() {
        _lightningState.update {
            it.copy(
                nodeId = getNodeId().orEmpty(),
                balanceDetails = getBalances(),
                nodeStatus = getStatus(),
                peers = getPeers().orEmpty(),
                channels = getChannels().orEmpty(),
            )
        }

        syncBalances()
    }

    private suspend fun syncBalances() {
        getBalances()?.let { balance ->
            val totalSats = balance.totalLightningBalanceSats + balance.totalOnchainBalanceSats

            val newBalance = BalanceState(
                totalOnchainSats = balance.totalOnchainBalanceSats,
                totalLightningSats = balance.totalLightningBalanceSats,
                totalSats = totalSats,
            )
            _balanceState.update { newBalance }
            walletRepo.saveBalanceState(newBalance)

            if (totalSats > 0u) {
                walletRepo.setShowEmptyState(false)
            }
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

    fun addTagToSelected(newTag: String) {
        _lightningState.update {
            it.copy(
                selectedTags = (it.selectedTags + newTag).distinct()
            )
        }
    }

    fun removeTag(tag: String) {
        _lightningState.update {
            it.copy(
                selectedTags = it.selectedTags.filterNot { tagItem -> tagItem == tag }
            )
        }
    }

    fun updateBip21Description(newText: String) {
        _lightningState.update { it.copy(bip21Description = newText) }
    }

    fun updateBip21AmountSats(amount: ULong?) {
        _lightningState.update { it.copy(bip21AmountSats = amount) }
    }

    fun toggleReceiveOnSpendingBalance() {
        _lightningState.update { it.copy(receiveOnSpendingBalance = !it.receiveOnSpendingBalance) }
    }

    private companion object {
        const val TAG = "LightningRepo"
    }
}

data class LightningState(
    val nodeId: String = "",
    val balanceDetails: BalanceDetails? = null,
    val nodeStatus: NodeStatus? = null,
    val nodeLifecycleState: NodeLifecycleState = NodeLifecycleState.Stopped,
    val peers: List<LnPeer> = emptyList(),
    val channels: List<ChannelDetails> = emptyList(),
    val isRefreshing: Boolean = false,
    val isSyncingWallet: Boolean = false,
    val receiveOnSpendingBalance: Boolean = true,
    val bip21AmountSats: ULong? = null,
    val bip21Description: String = "",
    val selectedTags: List<String> = listOf()
)
