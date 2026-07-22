package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.SortDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.PendingSweepBalance
import to.bitkit.data.dao.TransferDao
import to.bitkit.data.entities.TransferEntity
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.channelId
import to.bitkit.ext.latestSpendingTxid
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.TransferType
import to.bitkit.models.WalletScope
import to.bitkit.services.CoreService
import to.bitkit.utils.BlockTimeHelpers
import to.bitkit.utils.Logger
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Singleton
class TransferRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val coreService: CoreService,
    private val transferDao: TransferDao,
    private val clock: Clock,
) {
    val activeTransfers: Flow<List<TransferEntity>> = transferDao.getActiveTransfers()

    val forceCloseRemainingDuration: Flow<String?> = combine(
        activeTransfers,
        lightningRepo.lightningState,
    ) { transfers, lightningState ->
        val forceClose = transfers.firstOrNull { it.type == TransferType.FORCE_CLOSE }
            ?: return@combine null
        val targetHeight = forceClose.claimableAtHeight?.toUInt() ?: return@combine null
        val currentHeight = lightningState.block()?.height ?: return@combine null
        val remaining = BlockTimeHelpers.blocksRemaining(targetHeight, currentHeight)
        if (remaining <= 0) return@combine null
        BlockTimeHelpers.getDurationForBlocks(remaining)
    }

    @Suppress("LongParameterList")
    suspend fun createTransfer(
        type: TransferType,
        amountSats: Long,
        channelId: String? = null,
        fundingTxId: String? = null,
        lspOrderId: String? = null,
        claimableAtHeight: UInt? = null,
        txTotalSats: Long? = null,
        preTransferOnchainSats: Long? = null,
    ): Result<String> = withContext(bgDispatcher) {
        runCatching {
            val id = UUID.randomUUID().toString()
            transferDao.insert(
                TransferEntity(
                    id = id,
                    type = type,
                    amountSats = amountSats,
                    channelId = channelId,
                    fundingTxId = fundingTxId,
                    lspOrderId = lspOrderId,
                    isSettled = false,
                    createdAt = clock.now().epochSeconds,
                    claimableAtHeight = claimableAtHeight?.toInt(),
                    txTotalSats = txTotalSats,
                    preTransferOnchainSats = preTransferOnchainSats,
                )
            )
            Logger.info("Created transfer: id=$id type=$type channelId=$channelId", context = TAG)
            id
        }.onFailure { e ->
            Logger.error("Failed to create transfer", e, context = TAG)
        }
    }

    // TODO maybe replace with delete, or call delete once activity item was augmented with the transfer's data.
    //  Likely no clear reason to keep persisting transfers afterwards.
    suspend fun markSettled(id: String): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
            val settledAt = clock.now().epochSeconds
            transferDao.markSettled(id, settledAt)
            Logger.info("Settled transfer: $id", context = TAG)
        }.onFailure { e ->
            Logger.error("Failed to settle transfer", e, context = TAG)
        }
    }

    @Suppress("LongParameterList")
    suspend fun createPendingToSpendingActivity(
        order: IBtOrder,
        txId: String,
        fee: ULong,
        feeRate: ULong,
        walletId: String = WalletScope.default,
    ): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            val address = requireNotNull(order.payment?.onchain?.address?.takeIf { it.isNotEmpty() }) {
                "Order '${order.id}' has no on-chain payment address"
            }
            coreService.activity.createSentOnchainActivityFromSendResult(
                txid = txId,
                address = address,
                amount = order.feeSat,
                fee = fee,
                feeRate = feeRate,
                isTransfer = true,
                channelId = order.channel?.shortChannelId,
                walletId = walletId,
            )
        }.onFailure {
            Logger.error("Failed to create pending transfer activity for '$txId'", it, context = TAG)
        }
    }

    suspend fun findLspOrderIdByFundingTxId(fundingTxId: String): Result<String?> = withContext(bgDispatcher) {
        runSuspendCatching {
            transferDao.getByFundingTxId(fundingTxId)?.lspOrderId
        }.onFailure {
            Logger.warn("Failed to find transfer by funding txid '$fundingTxId'", it, context = TAG)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    suspend fun syncTransferStates(): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
            val activeTransfers = transferDao.getActiveTransfers().first()
            if (activeTransfers.isEmpty()) return@runCatching

            val channels = lightningRepo.getChannels() ?: emptyList()
            val balances = lightningRepo.getBalancesAsync().getOrNull()

            Logger.debug("Syncing ${activeTransfers.size} active transfers", context = TAG)

            val toSpending = activeTransfers.filter { it.type.isToSpending() }

            for (transfer in toSpending) {
                val channelId = resolveChannelIdForTransfer(transfer, channels)
                channelId?.let { persistResolvedChannel(transfer, it) }
                val channel = channelId?.let { channels.find { c -> c.channelId == it } }
                if (channel != null && channel.isChannelReady) {
                    markSettled(transfer.id)
                    Logger.debug("Channel $channelId ready, settled transfer: ${transfer.id}", context = TAG)
                } else if (channelId == null && transfer.lspOrderId != null) {
                    val order = blocktankRepo.getOrder(transfer.lspOrderId, refresh = false).getOrNull()
                    if (order?.state2 == BtOrderState2.EXPIRED) {
                        markSettled(transfer.id)
                        Logger.info(
                            "Order ${transfer.lspOrderId} expired, settled transfer: ${transfer.id}",
                            context = TAG,
                        )
                    }
                }
            }

            settleToSavingsTransfers(activeTransfers, channels, balances)
        }.onSuccess {
            Logger.verbose("syncTransferStates completed", context = TAG)
        }.onFailure { e ->
            Logger.error("syncTransferStates error", e, context = TAG)
        }
    }

    private suspend fun settleToSavingsTransfers(
        activeTransfers: List<TransferEntity>,
        channels: List<ChannelDetails>,
        balances: BalanceDetails?,
    ) {
        val toSavings = activeTransfers.filter { it.type.isToSavings() }
        if (toSavings.isEmpty()) return

        val balanceDetails = balances ?: run {
            Logger.debug("Skipped settling to-savings transfers because balances are unavailable", context = TAG)
            return
        }

        for (transfer in toSavings) {
            val channelId = resolveChannelIdForTransfer(transfer, channels)
            val hasBalance = balanceDetails.lightningBalances.any {
                it.channelId() == channelId
            }

            if (!hasBalance) {
                if (transfer.type == TransferType.FORCE_CLOSE) {
                    settleForceClose(transfer, channelId, balanceDetails.pendingBalancesFromChannelClosures)
                } else {
                    markSettled(transfer.id)
                    Logger.debug(
                        "Channel $channelId balance swept, settled transfer: ${transfer.id}",
                        context = TAG
                    )
                }
            }
        }
    }

    private suspend fun settleForceClose(
        transfer: TransferEntity,
        channelId: String?,
        pendingSweeps: List<PendingSweepBalance>?,
    ) {
        if (channelId == null) return

        if (coreService.activity.hasOnchainActivityForChannel(channelId)) {
            markActivityAsTransferByChannel(channelId)
            markSettled(transfer.id)
            Logger.debug("Force close sweep detected, settled transfer: ${transfer.id}", context = TAG)
            return
        }

        // When LDK batches sweeps from multiple channels into one transaction,
        // the on-chain activity may only be linked to one channel. Fall back to
        // checking if there are no remaining pending sweep balances for this channel.
        val pendingSweep = pendingSweeps?.find { it.channelId() == channelId }

        if (pendingSweep == null) {
            markSettled(transfer.id)
            Logger.debug(
                "Force close sweep completed (no pending sweeps), settled transfer: ${transfer.id}",
                context = TAG,
            )
            return
        }

        val sweepTxid = pendingSweep.latestSpendingTxid()
        if (sweepTxid != null && coreService.activity.hasOnchainActivityForTxid(sweepTxid)) {
            // The sweep tx was already synced as an on-chain activity (linked to another
            // channel in the same batched sweep). Safe to settle this transfer.
            markActivityAsTransfer(sweepTxid, channelId)
            markSettled(transfer.id)
            Logger.debug(
                "Force close batched sweep detected via txid $sweepTxid, settled transfer: ${transfer.id}",
                context = TAG,
            )
            return
        }

        Logger.debug("Force close awaiting sweep detection for transfer: ${transfer.id}", context = TAG)
    }

    private suspend fun persistResolvedChannel(transfer: TransferEntity, channelId: String) {
        if (transfer.channelId == null) {
            transferDao.update(transfer.copy(channelId = channelId))
            Logger.debug("Persisted channel '$channelId' for transfer '${transfer.id}'", context = TAG)
        }
        transfer.fundingTxId?.let { markActivityAsTransfer(it, channelId) }
    }

    private suspend fun markActivityAsTransfer(txid: String, channelId: String) {
        val activity = coreService.activity.get(
            walletId = null,
            filter = ActivityFilter.ONCHAIN,
            search = txid,
            limit = 10u,
            sortDirection = SortDirection.DESC,
        ).filterIsInstance<Activity.Onchain>()
            .filter { it.v1.txId == txid }
            .let { matches ->
                matches.firstOrNull { it.v1.isTransfer }
                    ?: matches.firstOrNull {
                        it.v1.walletId != WalletScope.default && it.v1.txType == PaymentType.SENT
                    }
                    ?: matches.firstOrNull()
            }?.v1 ?: return
        if (activity.isTransfer && activity.channelId == channelId) return
        val updated = activity.copy(isTransfer = true, channelId = channelId)
        coreService.activity.update(activity.id, Activity.Onchain(updated))
        Logger.debug("Marked activity ${activity.id} as transfer for channel $channelId", context = TAG)
    }

    private suspend fun markActivityAsTransferByChannel(channelId: String) {
        val activities = coreService.activity.get(
            filter = ActivityFilter.ONCHAIN,
            limit = 50u,
            sortDirection = SortDirection.DESC,
        )
        val activity = activities.firstOrNull { it is Activity.Onchain && it.v1.channelId == channelId }
            as? Activity.Onchain ?: return
        if (activity.v1.isTransfer) return
        val updated = activity.v1.copy(isTransfer = true, channelId = channelId)
        coreService.activity.update(activity.v1.id, Activity.Onchain(updated))
        Logger.debug("Marked activity ${activity.v1.id} as transfer for channel $channelId", context = TAG)
    }

    /** Resolve channelId: direct transfer data first, then LSP order metadata, then funding tx fallback. */
    suspend fun resolveChannelIdForTransfer(
        transfer: TransferEntity,
        channels: List<ChannelDetails>,
    ): String? {
        transfer.channelId?.let { return it }

        val orderFundingTxId = transfer.lspOrderId?.let { orderId ->
            blocktankRepo.getOrder(orderId, refresh = false).getOrNull()
                ?.channel
                ?.fundingTx
                ?.id
        }
        val fundingTxId = orderFundingTxId ?: transfer.fundingTxId

        return fundingTxId?.let { txid ->
            channels.find { it.fundingTxo?.txid == txid }?.channelId
        }
    }

    companion object {
        private const val TAG = "TransferRepo"
    }
}
