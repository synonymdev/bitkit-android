package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.CacheStore
import to.bitkit.data.dto.PendingBarkBoard
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Moves funds between savings (ldk-node on-chain) and Ark spending.
 *
 * Offboarding is one step, because bark can send straight to any on-chain address. Boarding is two,
 * because bark boards from its *own* on-chain wallet: savings must be sent there first, and only
 * once that transaction confirms can the board happen. ldk-node exposes no PSBT API, so bark's
 * on-chain wallet cannot be backed by the savings wallet to collapse this into one step.
 */
@OptIn(ExperimentalTime::class)
@Singleton
class BarkTransferRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val barkRepo: BarkRepo,
    private val cacheStore: CacheStore,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "BarkTransferRepo"
    }

    /**
     * Step 1 of savings -> spending: fund bark's on-chain wallet and record the intent. Step 2 runs
     * from [resumePendingBoard] once the funding transaction has enough confirmations.
     */
    suspend fun startBoard(amountSats: ULong): Result<String> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (cacheStore.data.first().pendingBarkBoard != null) {
                throw AppError("A board is already in progress")
            }

            val arkInfo = barkRepo.barkState.value.arkInfo
            val minBoardSats = arkInfo?.minBoardAmountSats
            if (minBoardSats != null && amountSats < minBoardSats) {
                throw AppError("Amount is below the Ark minimum of $minBoardSats sats")
            }
            val maxVtxoSats = arkInfo?.maxVtxoAmountSats
            if (maxVtxoSats != null && amountSats > maxVtxoSats) {
                throw AppError("Amount is above the Ark maximum of $maxVtxoSats sats")
            }

            val depositAddress = barkRepo.onchainDepositAddress().getOrThrow()
            val txId = lightningRepo.sendOnChain(
                address = depositAddress,
                sats = amountSats,
                isTransfer = true,
            ).getOrThrow()

            cacheStore.update {
                it.copy(
                    pendingBarkBoard = PendingBarkBoard(
                        fundingTxId = txId,
                        amountSats = amountSats,
                        createdAtMillis = nowMillis(clock),
                    )
                )
            }
            Logger.info("Started Ark board of '$amountSats' sats via '$txId'", context = TAG)
            txId
        }.onFailure {
            Logger.error("Failed to start Ark board", it, context = TAG)
        }
    }

    /**
     * Step 2: board once bark's on-chain wallet actually holds the funds. Called on every sync, so
     * it must be cheap and idempotent while the funding transaction is still unconfirmed.
     */
    suspend fun resumePendingBoard(): Result<Boolean> = withContext(bgDispatcher) {
        runSuspendCatching {
            val pending = cacheStore.data.first().pendingBarkBoard ?: return@runSuspendCatching false

            val confirmedSats = barkRepo.onchainSpendableSats().getOrThrow()
            if (confirmedSats < pending.amountSats) {
                Logger.debug(
                    "Waiting on Ark board funding '${pending.fundingTxId}': " +
                        "confirmed '$confirmedSats' of '${pending.amountSats}' sats",
                    context = TAG,
                )
                return@runSuspendCatching false
            }

            barkRepo.board(pending.amountSats).getOrThrow()
            cacheStore.update { it.copy(pendingBarkBoard = null) }
            Logger.info("Completed Ark board of '${pending.amountSats}' sats", context = TAG)
            true
        }.onFailure {
            Logger.error("Failed to resume Ark board", it, context = TAG)
        }
    }

    /** Sats already sent to bark but not yet boarded, shown as in-transfer to spending. */
    suspend fun pendingBoardSats(): ULong = cacheStore.data.first().pendingBarkBoard?.amountSats ?: 0uL

    /** Spending -> savings: offboard every VTXO to a fresh ldk-node on-chain address. */
    suspend fun offboardToSavings(): Result<String> = withContext(bgDispatcher) {
        runSuspendCatching {
            val savingsAddress = lightningRepo.newAddress().getOrThrow()
            barkRepo.offboardAll(savingsAddress).getOrThrow()
        }.onFailure {
            Logger.error("Failed to offboard Ark balance to savings", it, context = TAG)
        }
    }

    suspend fun estimateOffboardFeeSats(): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching {
            val savingsAddress = lightningRepo.newAddress().getOrThrow()
            barkRepo.estimateOffboardAllFeeSats(savingsAddress).getOrThrow()
        }
    }
}
