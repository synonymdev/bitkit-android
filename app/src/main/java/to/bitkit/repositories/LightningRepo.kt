package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
import to.bitkit.di.BgDispatcher
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
    private val addressChecker: AddressChecker
) {
    private val _nodeLifecycleState: MutableStateFlow<NodeLifecycleState> = MutableStateFlow(NodeLifecycleState.Stopped)
    val nodeLifecycleState = _nodeLifecycleState.asStateFlow()

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

        if (nodeLifecycleState.value.isRunning()) {
            return@withContext executeOperation(operationName, operation)
        }

        // If node is not in a state that can become running, fail fast
        if (!nodeLifecycleState.value.canRun()) {
            return@withContext Result.failure(
                Exception("Cannot execute $operationName: Node is ${nodeLifecycleState.value} and not starting")
            )
        }

        val nodeRunning = withTimeoutOrNull(waitTimeout) {
            if (nodeLifecycleState.value.isRunning()) {
                return@withTimeoutOrNull true
            }

            // Otherwise, wait for it to transition to running state
            Logger.debug("Waiting for node runs to execute $operationName", context = TAG)
            _nodeLifecycleState.first { it.isRunning() }
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
    ): Result<Unit> =
        withContext(bgDispatcher) {
            if (nodeLifecycleState.value.isRunningOrStarting()) {
                return@withContext Result.success(Unit)
            }

            try {
                _nodeLifecycleState.value = NodeLifecycleState.Starting

                // Setup if not already setup
                if (lightningService.node == null) {
                    val setupResult = setup(walletIndex)
                    if (setupResult.isFailure) {
                        _nodeLifecycleState.value = NodeLifecycleState.ErrorStarting(
                            setupResult.exceptionOrNull() ?: Exception("Unknown setup error")
                        )
                        return@withContext setupResult
                    }
                }

                // Start the node service
                lightningService.start(timeout) { event ->
                    eventHandler?.invoke(event)
                    ldkNodeEventBus.emit(event)
                }

                _nodeLifecycleState.value = NodeLifecycleState.Running
                Result.success(Unit)
            } catch (e: Throwable) {
                Logger.error("Node start error", e, context = TAG)
                _nodeLifecycleState.value = NodeLifecycleState.ErrorStarting(e)
                Result.failure(e)
            }
        }

    suspend fun stop(): Result<Unit> = withContext(bgDispatcher) {
        if (nodeLifecycleState.value.isStoppedOrStopping()) {
            return@withContext Result.success(Unit)
        }

        try {
            executeWhenNodeRunning("stop") {
                _nodeLifecycleState.value = NodeLifecycleState.Stopping
                lightningService.stop()
                _nodeLifecycleState.value = NodeLifecycleState.Stopped
                Result.success(Unit)
            }
        } catch (e: Throwable) {
            Logger.error("Node stop error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun sync(): Result<Unit> = executeWhenNodeRunning("Sync") {
        lightningService.sync()
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
            Result.success(paymentId)
        }

    suspend fun sendOnChain(address: Address, sats: ULong): Result<Txid> =
        executeWhenNodeRunning("Send on-chain") {
            val txId = lightningService.send(address = address, sats = sats)
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
        lightningService.openChannel(peer, channelAmountSats, pushToCounterpartySats)
    }

    suspend fun closeChannel(userChannelId: String, counterpartyNodeId: String): Result<Unit> =
        executeWhenNodeRunning("Close channel") {
            lightningService.closeChannel(userChannelId, counterpartyNodeId)
            Result.success(Unit)
        }

    fun canSend(amountSats: ULong): Boolean =
        nodeLifecycleState.value.isRunning() && lightningService.canSend(amountSats)

    fun getSyncFlow(): Flow<Unit> = lightningService.syncFlow()

    fun getNodeId(): String? = if (nodeLifecycleState.value.isRunning()) lightningService.nodeId else null

    fun getBalances(): BalanceDetails? = if (nodeLifecycleState.value.isRunning()) lightningService.balances else null

    fun getStatus(): NodeStatus? = if (nodeLifecycleState.value.isRunning()) lightningService.status else null

    fun getPeers(): List<LnPeer>? = if (nodeLifecycleState.value.isRunning()) lightningService.peers else null

    fun getChannels(): List<ChannelDetails>? =
        if (nodeLifecycleState.value.isRunning()) lightningService.channels else null

    fun hasChannels(): Boolean = nodeLifecycleState.value.isRunning() && lightningService.channels?.isNotEmpty() == true

    private companion object {
        const val TAG = "LightningRepo"
    }
}
