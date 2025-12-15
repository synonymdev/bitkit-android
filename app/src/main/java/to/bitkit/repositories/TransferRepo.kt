package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.data.dao.TransferDao
import to.bitkit.data.entities.TransferEntity
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.channelId
import to.bitkit.models.TransferType
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
                val channelId = resolveChannelIdForTransfer(transfer, channels)
                val hasBalance = balances?.lightningBalances?.any {
                    it.channelId() == channelId
                } ?: false

                if (!hasBalance) {
                    markSettled(transfer.id)
                    Logger.debug("Channel $channelId balance swept, settled transfer: ${transfer.id}", context = TAG)
                }
            }
        }.onSuccess {
            Logger.verbose("syncTransferStates completed", context = TAG)
        }.onFailure { e ->
            Logger.error("syncTransferStates error", e, context = TAG)
        }
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
