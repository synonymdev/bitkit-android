package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.SortDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.PendingSweepBalance
import to.bitkit.data.dao.TransferDao
import to.bitkit.data.entities.TransferEntity
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.channelId
import to.bitkit.ext.latestSpendingTxid
import to.bitkit.models.TransferType
import to.bitkit.services.CoreService
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

    suspend fun createTransfer(
        type: TransferType,
        amountSats: Long,
        channelId: String? = null,
        fundingTxId: String? = null,
        lspOrderId: String? = null,
        claimableAtHeight: UInt? = null,
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
                    claimableAtHeight = claimableAtHeight,
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
                val channel = channelId?.let { channels.find { c -> c.channelId == it } }
                if (channel != null && channel.isChannelReady) {
                    markSettled(transfer.id)
                    Logger.debug("Channel $channelId ready, settled transfer: ${transfer.id}", context = TAG)
                }
            }

            val toSavings = activeTransfers.filter { it.type.isToSavings() }

            for (transfer in toSavings) {
                if (transfer.type == TransferType.COOP_CLOSE) {
                    markSettled(transfer.id)
                    Logger.debug("Coop close settled immediately: ${transfer.id}", context = TAG)
                    continue
                }

                val channelId = resolveChannelIdForTransfer(transfer, channels)
                val hasBalance = balances?.lightningBalances?.any {
                    it.channelId() == channelId
                } ?: false

                if (!hasBalance) {
                    if (transfer.type == TransferType.FORCE_CLOSE) {
                        settleForceClose(transfer, channelId, balances?.pendingBalancesFromChannelClosures)
                    } else {
                        markSettled(transfer.id)
                        Logger.debug(
                            "Channel $channelId balance swept, settled transfer: ${transfer.id}",
                            context = TAG
                        )
                    }
                }
            }
        }.onSuccess {
            Logger.verbose("syncTransferStates completed", context = TAG)
        }.onFailure { e ->
            Logger.error("syncTransferStates error", e, context = TAG)
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

    private suspend fun markActivityAsTransfer(txid: String, channelId: String) {
        val activity = coreService.activity.getOnchainActivityByTxId(txid) ?: return
        if (activity.isTransfer) return
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

    /** Resolve channelId: for LSP orders: via order->fundingTx match, for manual: directly. */
    suspend fun resolveChannelIdForTransfer(
        transfer: TransferEntity,
        channels: List<ChannelDetails>,
    ): String? {
        return transfer.lspOrderId
            ?.let { orderId ->
                val order = blocktankRepo.getOrder(orderId, refresh = false).getOrNull()
                val fundingTxId = order?.channel?.fundingTx?.id ?: return null
                return@let channels.find { it.fundingTxo?.txid == fundingTxId }?.channelId
            }
            ?: transfer.channelId
    }

    companion object {
        private const val TAG = "TransferRepo"
    }
}
