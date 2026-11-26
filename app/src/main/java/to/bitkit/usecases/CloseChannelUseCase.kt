package to.bitkit.usecases

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.TransferType
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.MoveToSparkMath
import to.bitkit.repositories.MoveToSparkRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** Closes a channel cooperatively and records its transfer to Savings, so the close shows in Activity. */
@Singleton
class CloseChannelUseCase @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val transferRepo: TransferRepo,
    private val walletRepo: WalletRepo,
    private val moveToSparkRepo: MoveToSparkRepo,
) {
    companion object {
        private const val TAG = "CloseChannelUseCase"

        /** How long a close waits for a move's HTLC to stop counting against the peer's in-flight cap. */
        private const val HTLC_CLEAR_RECHECKS = 15
        private val HTLC_CLEAR_RECHECK = 2.seconds
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
     * no longer, read from the node after a sync. Nothing closes while the payments are unclear or another is in
     * flight, or when a payment other than a move's changed since [paymentsAtStart]. A channel that still holds the
     * minimum in capacity is read again until its limit rises or the wait ends. A close that fails leaves its channel
     * listed for a manual close.
     */
    suspend fun closeEmptiedByMove(
        before: List<ChannelDetails>,
        paymentsAtStart: List<PaymentDetails>?,
    ) = withContext(ioDispatcher) {
        walletRepo.syncNodeAndWallet()
        var emptied = readEmptied(before, paymentsAtStart)
        var previous: List<ChannelDetails>? = null
        // A channel whose limit rises once the move's HTLC clears drops out. One not emptied for good after the wait
        // stays open for a manual close, so a close never starts on a reading the HTLC may have lowered.
        repeat(HTLC_CLEAR_RECHECKS) {
            if (emptied.all { MoveToSparkMath.isEmptiedForGood(it, previous) }) return@repeat
            delay(HTLC_CLEAR_RECHECK)
            previous = emptied
            emptied = readEmptied(before, paymentsAtStart)
        }
        emptied.filter { MoveToSparkMath.isEmptiedForGood(it, previous) }.forEach { channel ->
            invoke(channel)
                .onSuccess { Logger.info("Closed channel '${channel.channelId}' emptied by a move", context = TAG) }
                .onFailure { Logger.warn("Failed to close channel '${channel.channelId}'", it, context = TAG) }
        }
    }

    /** The channels a move emptied, read between two reads of the payments; none while those are unclear. */
    private suspend fun readEmptied(
        before: List<ChannelDetails>,
        paymentsAtStart: List<PaymentDetails>?,
    ): List<ChannelDetails> {
        val paymentsBefore = lightningRepo.listPaymentsOrNull()
        // A sync already running returns at once, so the channels are read from the node directly.
        lightningRepo.syncState()
        val moveHashes = moveToSparkRepo.moveHashes.first()
        val payments = MoveToSparkMath.paymentsAround(
            moveStart = paymentsAtStart,
            before = paymentsBefore,
            after = lightningRepo.listPaymentsOrNull(),
        ) { it.lowercase() in moveHashes }
        if (payments == null) {
            Logger.info("Kept the channels a move may have emptied open while its payments are unclear", context = TAG)
            return emptyList()
        }
        val isInFlight = payments.any {
            it.direction == PaymentDirection.OUTBOUND &&
                it.status == PaymentStatus.PENDING &&
                it.kind !is PaymentKind.Onchain
        }
        if (isInFlight) {
            Logger.info("Kept the channels a move may have emptied open while a payment is in flight", context = TAG)
            return emptyList()
        }
        return MoveToSparkMath.emptiedChannels(before, lightningRepo.lightningState.value.channels)
    }
}
