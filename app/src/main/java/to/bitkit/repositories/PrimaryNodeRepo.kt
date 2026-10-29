package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PrimaryReceiveQuote
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.WatchResult
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.watchUntil
import to.bitkit.models.PrimaryNode
import to.bitkit.models.PrimaryNodeError
import to.bitkit.models.USat
import to.bitkit.models.toPeerDetails
import to.bitkit.utils.AppError
import to.bitkit.utils.LdkError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Lightning-first wallet operations through the primary node: pairing, just-in-time receives,
 * moving confirmed savings into the primary channel, and paying on-chain from that channel.
 */
@Singleton
class PrimaryNodeRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
) {
    companion object {
        private const val TAG = "PrimaryNodeRepo"

        /** Confirmed savings below this stay on-chain instead of moving into the primary channel. */
        const val MIN_FUNDING_SATS = 25_000uL

        /** Automatic funding waits while its mining fee is above this share of the amount moved. */
        const val MAX_FUNDING_FEE_PERCENT = 5uL

        /** Virtual size of a splice or channel funding before its inputs, for fee estimates. */
        const val FUNDING_BASE_VBYTES = 300uL

        /** Virtual size each wallet input adds to a funding transaction. */
        const val FUNDING_INPUT_VBYTES = 300uL

        /** How long a splice may take to reach its pending funding transaction. */
        private val SPLICE_TIMEOUT = 60.seconds
    }

    private val operationMutex = Mutex()

    /** The primary the wallet uses: the paired one, else the network's default. */
    val primaryNode: Flow<PrimaryNode?> = settingsStore.data
        .map { PrimaryNode.effective(it.primaryNodeUri) }
        .distinctUntilChanged()

    val isFundingPaused: Flow<Boolean> = settingsStore.data.map { it.primaryFundingPaused }.distinctUntilChanged()

    val hasPrimaryNode: Boolean get() = lightningRepo.primaryNode != null

    /** The ready channel with the primary, if the wallet has one. */
    fun primaryChannel(): ChannelDetails? {
        val primary = lightningRepo.primaryNode ?: return null
        return lightningRepo.lightningState.value.channels.firstOrNull {
            it.counterpartyNodeId == primary.nodeId && it.isChannelReady
        }
    }

    fun isPrimaryConnected(): Boolean {
        val primary = lightningRepo.primaryNode ?: return false
        return lightningRepo.lightningState.value.peers.any { it.nodeId == primary.nodeId && it.isConnected }
    }

    /**
     * Pairs [input] as the primary: saves it, restarts the node so it trusts the new primary, connects,
     * and checks the node speaks the primary-wallet protocol. A failure restores the previous primary.
     */
    suspend fun pair(input: String): Result<PrimaryNode> = withContext(bgDispatcher) {
        operationMutex.withLock {
            runSuspendCatching {
                val parsed = PrimaryNode.parse(input)
                val primary = if (parsed.address != null) parsed else parsed.copy(address = knownAddress(parsed.nodeId))
                val current = lightningRepo.primaryNode
                val hasChannelWithCurrent = current != null && current.nodeId != primary.nodeId &&
                    lightningRepo.lightningState.value.channels.any { it.counterpartyNodeId == current.nodeId }
                if (hasChannelWithCurrent) throw PrimaryNodeError.ConnectionInUse()

                val previous = settingsStore.data.first()
                val savedUri = primary.uri.takeIf { it != Env.defaultPrimaryNodeUri }.orEmpty()
                settingsStore.update { it.copy(primaryNodeUri = savedUri, primaryFundingPaused = false) }
                verifyPrimary(primary).onFailure { error ->
                    settingsStore.update {
                        it.copy(
                            primaryNodeUri = previous.primaryNodeUri,
                            primaryFundingPaused = previous.primaryFundingPaused,
                        )
                    }
                    lightningRepo.restartNode()
                    throw error
                }
                primary
            }
        }
    }

    suspend fun quoteReceive(amountSats: ULong): Result<PrimaryReceiveQuote> = withContext(bgDispatcher) {
        lightningRepo.quotePrimaryReceive(amountSats).mapPrimaryReceiveError()
            .mapCatching { quote ->
                if (quote.amountMsat != amountSats * 1000uL || quote.feeMsat >= quote.amountMsat) {
                    throw PrimaryReceiveError.AmountTooSmall()
                }
                quote
            }
    }

    suspend fun createInvoice(
        amountSats: ULong,
        description: String,
        maxFeeMsat: ULong,
    ): Result<String> = withContext(bgDispatcher) {
        lightningRepo.createPrimaryInvoice(amountSats, description, maxFeeMsat).mapPrimaryReceiveError()
    }

    /** How much of the confirmed savings can move into the primary channel now, and at what fee. */
    suspend fun fundingPlan(): Result<PrimaryFundingPlan?> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (lightningRepo.primaryNode == null || !isPrimaryConnected()) return@runSuspendCatching null
            if (lightningRepo.hasPendingFundingOperation().getOrThrow()) return@runSuspendCatching null
            val spendable = lightningRepo.getBalancesAsync().getOrThrow().spendableOnchainBalanceSats
            val inputs = lightningRepo.listSpendableOutputs().getOrThrow().size.toULong()
            val feeRate = lightningRepo.channelFundingFeeRate().getOrThrow()
            primaryFundingPlan(spendable, inputs, feeRate, hasChannel = primaryChannel() != null)
        }
    }

    /** Moves confirmed savings into the primary channel when the plan allows it and funding isn't paused. */
    suspend fun fundIfNeeded(): Result<Unit> = withContext(bgDispatcher) {
        if (settingsStore.data.first().primaryFundingPaused) return@withContext Result.success(Unit)
        val plan = fundingPlan().getOrElse { return@withContext Result.failure(it) }
            ?: return@withContext Result.success(Unit)
        if (!plan.isAutomatic) return@withContext Result.success(Unit)
        Logger.info("Funding primary channel with '${plan.amountSats}' sats, fee '${plan.feeSats}'", context = TAG)
        executeFunding(plan)
    }

    /** Transfer to Spending: moves the planned savings now, below the automatic fee limit too, and resumes funding. */
    suspend fun transferToSpending(plan: PrimaryFundingPlan): Result<Unit> = withContext(bgDispatcher) {
        settingsStore.update { it.copy(primaryFundingPaused = false) }
        executeFunding(plan)
    }

    /** Fee for paying [amountSats] on-chain out of the primary channel, or null when the channel can't cover it. */
    fun spliceOutFee(amountSats: ULong, feeRate: ULong): ULong? {
        val channel = primaryChannel()?.takeIf { it.isUsable } ?: return null
        val fee = feeRate * FUNDING_BASE_VBYTES
        return fee.takeIf { USat(amountSats) + USat(it) <= channel.outboundCapacityMsat / 1000uL }
    }

    /** The most a single on-chain payment can take out of the primary channel at [feeRate]. */
    fun maxSpliceOutSats(feeRate: ULong): ULong {
        val channel = primaryChannel()?.takeIf { it.isUsable } ?: return 0uL
        return USat(channel.outboundCapacityMsat / 1000uL) - USat(feeRate * FUNDING_BASE_VBYTES)
    }

    /** Pays [amountSats] to [address] from the primary channel by splicing it out; returns the splice txid. */
    suspend fun sendOnchain(address: String, amountSats: ULong, feeRate: ULong): Result<String> =
        withContext(bgDispatcher) {
            operationMutex.withLock {
                runSuspendCatching {
                    val channel = primaryChannel()?.takeIf { it.isUsable } ?: throw PrimaryNodeError.NotConfigured()
                    if (lightningRepo.hasPendingFundingOperation().getOrThrow()) throw PrimaryWalletBusyError()
                    coroutineScope {
                        val pending = async(start = CoroutineStart.UNDISPATCHED) { awaitSplice(channel.userChannelId) }
                        lightningRepo.spliceOut(channel, address, amountSats, feeRate).onFailure { pending.cancel() }
                            .getOrThrow()
                        pending.await()
                    }
                }
            }
        }

    private suspend fun awaitSplice(userChannelId: String): String {
        val result = lightningRepo.nodeEvents.watchUntil(SPLICE_TIMEOUT) {
            when (it) {
                is Event.SplicePending if it.userChannelId == userChannelId ->
                    WatchResult.Complete(Result.success(it.newFundingTxo.txid))
                is Event.SpliceFailed if it.userChannelId == userChannelId ->
                    WatchResult.Complete(Result.failure(PrimarySpliceFailedError()))
                else -> WatchResult.Continue()
            }
        } ?: throw PrimaryWalletBusyError()
        return result.getOrThrow()
    }

    /** Transfer to Savings: splices the channel's spendable balance out to the wallet and pauses funding. */
    suspend fun transferToSavings(feeRate: ULong): Result<String> = withContext(bgDispatcher) {
        runSuspendCatching {
            val amount = maxSpliceOutSats(feeRate)
            if (amount == 0uL) throw PrimaryNodeError.NotConfigured()
            val address = lightningRepo.newAddress().getOrThrow()
            settingsStore.update { it.copy(primaryFundingPaused = true) }
            sendOnchain(address, amount, feeRate).onFailure {
                settingsStore.update { settings -> settings.copy(primaryFundingPaused = false) }
            }.getOrThrow()
        }
    }

    private suspend fun executeFunding(plan: PrimaryFundingPlan): Result<Unit> = operationMutex.withLock {
        runSuspendCatching {
            val primary = lightningRepo.primaryNode ?: throw PrimaryNodeError.NotConfigured()
            val channel = primaryChannel()
            if (channel != null) {
                lightningRepo.spliceIn(channel, plan.amountSats).getOrThrow()
            } else {
                val peer = primary.toPeerDetails() ?: throw PrimaryNodeError.AddressUnknown()
                lightningRepo.openChannel(peer, plan.amountSats).getOrThrow()
            }
            Unit
        }
    }

    private suspend fun verifyPrimary(primary: PrimaryNode): Result<Unit> = runSuspendCatching {
        lightningRepo.restartNode().getOrThrow()
        val peer = primary.toPeerDetails() ?: throw PrimaryNodeError.AddressUnknown()
        lightningRepo.connectPeer(peer).mapPrimaryReceiveError().getOrThrow()
        lightningRepo.checkPrimaryConnection(primary.nodeId).mapPrimaryReceiveError().getOrThrow()
    }

    private fun knownAddress(nodeId: String): String = lightningRepo.lightningState.value.peers
        .firstOrNull { it.nodeId == nodeId }?.address
        ?: throw PrimaryNodeError.AddressUnknown()
}

/** A move of confirmed savings into the primary channel: [amountSats] arrives, [feeSats] pays the miners. */
data class PrimaryFundingPlan(
    val amountSats: ULong,
    val feeSats: ULong,
    val opensChannel: Boolean,
    val isAutomatic: Boolean,
)

/**
 * Plans moving [spendableSats] of savings, held in [inputs] outputs, into the primary channel at [feeRate].
 * Returns null when there is nothing worth moving; [PrimaryFundingPlan.isAutomatic] is false when the
 * fee is above [PrimaryNodeRepo.MAX_FUNDING_FEE_PERCENT], so only a manual transfer moves it.
 */
internal fun primaryFundingPlan(
    spendableSats: ULong,
    inputs: ULong,
    feeRate: ULong,
    hasChannel: Boolean,
): PrimaryFundingPlan? {
    if (inputs == 0uL || feeRate == 0uL) return null
    val fee = feeRate * (PrimaryNodeRepo.FUNDING_BASE_VBYTES + PrimaryNodeRepo.FUNDING_INPUT_VBYTES * inputs)
    if (USat(spendableSats) <= USat(fee)) return null
    val amount = USat(spendableSats) - USat(fee)
    return PrimaryFundingPlan(
        amountSats = amount,
        feeSats = fee,
        opensChannel = !hasChannel,
        isAutomatic = spendableSats >= PrimaryNodeRepo.MIN_FUNDING_SATS &&
            fee <= spendableSats / 100uL * PrimaryNodeRepo.MAX_FUNDING_FEE_PERCENT,
    )
}

class PrimarySpliceFailedError : AppError("The primary node could not complete the splice.")

class PrimaryWalletBusyError : AppError("A primary channel operation is still pending. Try again once it confirms.")

/** Why the primary node could not provide a receive, shown to the user. */
sealed class PrimaryReceiveError(message: String, cause: Throwable? = null) : AppError(message, cause) {
    class InsufficientFunds : PrimaryReceiveError("The primary node does not have enough funds for this amount.")
    class Rejected : PrimaryReceiveError("The primary node declined this amount.")
    class TimedOut : PrimaryReceiveError("The primary node did not respond.")
    class AmountTooSmall : PrimaryReceiveError("This amount is too small to cover the receive fee.")
    class Offline(cause: Throwable?) : PrimaryReceiveError("The primary node is offline or unreachable.", cause)
}

internal fun <T> Result<T>.mapPrimaryReceiveError(): Result<T> = recoverCatching { throw it.toPrimaryReceiveError() }

internal fun Throwable.toPrimaryReceiveError(): Throwable = when ((this as? LdkError)?.nodeException) {
    is NodeException.LiquiditySourceInsufficientFunds -> PrimaryReceiveError.InsufficientFunds()
    is NodeException.LiquidityRequestRejected -> PrimaryReceiveError.Rejected()
    is NodeException.LiquidityRequestTimedOut -> PrimaryReceiveError.TimedOut()
    is NodeException.LiquidityReceiveAmountTooSmall -> PrimaryReceiveError.AmountTooSmall()
    is NodeException.ConnectionFailed,
    is NodeException.LiquiditySourceUnavailable,
    is NodeException.NotRunning,
    -> PrimaryReceiveError.Offline(this)
    else -> this
}
