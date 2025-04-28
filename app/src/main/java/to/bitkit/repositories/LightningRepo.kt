package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

@Singleton
class LightningRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningService: LightningService,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val addressChecker: AddressChecker
) {
    private val _nodeLifecycleState: MutableStateFlow<NodeLifecycleState> = MutableStateFlow(NodeLifecycleState.Stopped)
    val nodeLifecycleState = _nodeLifecycleState.asStateFlow()

    suspend fun setup(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        return@withContext try {
            lightningService.setup(walletIndex)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Node setup error", e)
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
                Logger.error("Node start error", e)
                _nodeLifecycleState.value = NodeLifecycleState.ErrorStarting(e)
                Result.failure(e)
            }
        }

    suspend fun stop(): Result<Unit> = withContext(bgDispatcher) {
        if (nodeLifecycleState.value.isStoppedOrStopping()) {
            return@withContext Result.success(Unit)
        }

        try {
            _nodeLifecycleState.value = NodeLifecycleState.Stopping
            lightningService.stop()
            _nodeLifecycleState.value = NodeLifecycleState.Stopped
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Node stop error", e)
            Result.failure(e)
        }
    }

    suspend fun sync(): Result<Unit> = withContext(bgDispatcher) {
        try {
            lightningService.sync()
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Sync error", e)
            Result.failure(e)
        }
    }

    suspend fun wipeStorage(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        try {
            lightningService.wipeStorage(walletIndex)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Wipe storage error", e)
            Result.failure(e)
        }
    }

    suspend fun connectToTrustedPeers(): Result<Unit> = withContext(bgDispatcher) {
        try {
            lightningService.connectToTrustedPeers()
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Connect to trusted peers error", e)
            Result.failure(e)
        }
    }

    suspend fun disconnectPeer(peer: LnPeer): Result<Unit> = withContext(bgDispatcher) {
        try {
            lightningService.disconnectPeer(peer)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Disconnect peer error", e)
            Result.failure(e)
        }
    }

    suspend fun newAddress(): Result<String> = withContext(bgDispatcher) {
        try {
            val address = lightningService.newAddress()
            Result.success(address)
        } catch (e: Throwable) {
            Logger.error("New address error", e)
            Result.failure(e)
        }
    }

    suspend fun checkAddressUsage(address: String): Result<Boolean> = withContext(bgDispatcher) {
        try {
            val addressInfo = addressChecker.getAddressInfo(address)
            val hasTransactions = addressInfo.chain_stats.tx_count > 0 || addressInfo.mempool_stats.tx_count > 0
            Result.success(hasTransactions)
        } catch (e: Throwable) {
            Logger.error("Check address usage error", e)
            Result.failure(e)
        }
    }

    suspend fun createInvoice(
        amountSats: ULong? = null,
        description: String,
        expirySeconds: UInt = 86_400u
    ): Result<Bolt11Invoice> = withContext(bgDispatcher) {
        try {
            val invoice = lightningService.receive(amountSats, description, expirySeconds)
            Result.success(invoice)
        } catch (e: Throwable) {
            Logger.error("Create invoice error", e)
            Result.failure(e)
        }
    }

    suspend fun payInvoice(bolt11: String, sats: ULong? = null): Result<PaymentId> = withContext(bgDispatcher) {
        try {
            val paymentId = lightningService.send(bolt11 = bolt11, sats = sats)
            Result.success(paymentId)
        } catch (e: Throwable) {
            Logger.error("Pay invoice error", e)
            Result.failure(e)
        }
    }

    suspend fun sendOnChain(address: Address, sats: ULong): Result<Txid> = withContext(bgDispatcher) {
        try {
            val txId = lightningService.send(address = address, sats = sats)
            Result.success(txId)
        } catch (e: Throwable) {
            Logger.error("sendOnChain error", e)
            Result.failure(e)
        }
    }

    suspend fun getPayments(): Result<List<PaymentDetails>> = withContext(bgDispatcher) {
        try {
            val payments = lightningService.payments
                ?: return@withContext Result.failure(Exception("It wasn't possible get the payments"))
            Result.success(payments)
        } catch (e: Throwable) {
            Logger.error("getPayments error", e)
            Result.failure(e)
        }
    }

    suspend fun openChannel(
        peer: LnPeer,
        channelAmountSats: ULong,
        pushToCounterpartySats: ULong? = null
    ): Result<UserChannelId> = withContext(bgDispatcher) {
        try {
            val result = lightningService.openChannel(peer, channelAmountSats, pushToCounterpartySats)
            result
        } catch (e: Throwable) {
            Logger.error("Open channel error", e)
            Result.failure(e)
        }
    }

    suspend fun closeChannel(userChannelId: String, counterpartyNodeId: String): Result<Unit> =
        withContext(bgDispatcher) {
            try {
                lightningService.closeChannel(userChannelId, counterpartyNodeId)
                Result.success(Unit)
            } catch (e: Throwable) {
                Logger.error("Close channel error", e)
                Result.failure(e)
            }
        }

    fun canSend(amountSats: ULong): Boolean = lightningService.canSend(amountSats)

    fun getSyncFlow(): Flow<Unit> = lightningService.syncFlow()

    fun getNodeId(): String? = lightningService.nodeId
    fun getBalances(): BalanceDetails? = lightningService.balances
    fun getStatus(): NodeStatus? = lightningService.status
    fun getPeers(): List<LnPeer>? = lightningService.peers
    fun getChannels(): List<ChannelDetails>? = lightningService.channels

    fun hasChannels(): Boolean = lightningService.channels?.isNotEmpty() == true
}
