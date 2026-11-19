package to.bitkit.usecases

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.TransferType
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.MoveToSparkMath
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/** Closes a channel cooperatively and records its transfer to Savings, so the close shows in Activity. */
@Singleton
class CloseChannelUseCase @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val transferRepo: TransferRepo,
    private val walletRepo: WalletRepo,
) {
    companion object {
        private const val TAG = "CloseChannelUseCase"
    }

    suspend operator fun invoke(channel: ChannelDetails): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            lightningRepo.closeChannel(channel).getOrThrow()
            transferRepo.createTransfer(
                type = TransferType.COOP_CLOSE,
                amountSats = channel.amountOnClose.toLong(),
                channelId = channel.channelId,
                fundingTxId = channel.fundingTxo?.txid,
            )
            walletRepo.syncNodeAndWallet()
            Unit
        }
    }

    /**
     * Closes each channel a move to Spark emptied: one that could send at least the move minimum in [before] and can
     * no longer, read from the node after a sync. A close that fails leaves its channel listed for a manual close.
     * While another payment is in flight its funds lower each channel's limit without leaving it, so nothing closes
     * and the channels stay listed for a manual close.
     */
    suspend fun closeEmptiedByMove(before: List<ChannelDetails>) = withContext(ioDispatcher) {
        walletRepo.syncNodeAndWallet()
        // A sync already running returns at once, so the channels are read from the node directly.
        lightningRepo.syncState()
        val inFlight = lightningRepo.listPaymentsOrNull().orEmpty()
            .any { it.direction == PaymentDirection.OUTBOUND && it.status == PaymentStatus.PENDING }
        if (inFlight) {
            Logger.info("Kept the channels a move may have emptied open while a payment is in flight", context = TAG)
            return@withContext
        }
        val emptied = MoveToSparkMath.emptiedChannels(before, lightningRepo.lightningState.value.channels)
        emptied.forEach { channel ->
            invoke(channel)
                .onSuccess { Logger.info("Closed channel '${channel.channelId}' emptied by a move", context = TAG) }
                .onFailure { Logger.warn("Failed to close channel '${channel.channelId}'", it, context = TAG) }
        }
    }
}
