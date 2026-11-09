package to.bitkit.repositories

import breez_sdk_spark.CooperativeRecoveryError
import breez_sdk_spark.CooperativeRecoveryFailure
import breez_sdk_spark.CpfpFundingStrategy
import breez_sdk_spark.CpfpInput
import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.RecoverFundsLeaf
import breez_sdk_spark.RecoverFundsResponse
import breez_sdk_spark.RecoveryMethod
import breez_sdk_spark.RecoveryTransaction
import breez_sdk_spark.RecoveryTxKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import to.bitkit.models.safe
import to.bitkit.utils.AppError
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Everything a Spark emergency exit needs to continue after a restart. The SDK keeps none of it: the signed
 * recovery, the request it came from, how its fees were funded and what this device already broadcast. Times are
 * epoch milliseconds. The JSON shape is shared with Bitkit iOS.
 */
data class SparkExitRecord(
    val version: Int = CURRENT_VERSION,
    val network: String,
    val createdAt: Long,
    val phase: Phase,
    val feeRateSatPerVbyte: ULong,
    val destination: String,
    /** The leaves of the quote the user confirmed; every rebuild selects exactly these. */
    val leafIds: List<String>,
    val funding: List<Funding>,
    /**
     * Bumped on every rebuild. Broadcasts are logged per generation and a rebuilt recovery replaces the old one
     * entirely, so older hex is never sent again.
     */
    val generation: Int = 0,
    val recovery: RecoverFundsResponse? = null,
    val broadcastLog: List<BroadcastAttempt> = emptyList(),
    val transferId: String? = null,
    /** What reaches savings: the exited value less the fees paid out of it, plus the unspent fee funding. */
    val expectedSats: ULong,
    val claimableAtHeight: UInt? = null,
    val leftover: List<Leftover> = emptyList(),
    val skippedLeafCount: Int = 0,
    /** Cooperative leaves the last build could not recover. */
    val failedLeafCount: Int = 0,
    /** Set and saved before a rebuild, so nothing of the diverged generation is broadcast while it is replaced. */
    val isRebuilding: Boolean = false,
    val lastBuildAt: Long? = null,
    /** The value of the quote's leaves, to show what Spark holds outside the exit. */
    val leafTotalSats: ULong? = null,
    /**
     * The CPFP child and fan-out txids of replaced generations, whose outputs at the funding address are not new
     * funding. The name predates the fan-out ids, kept so stored records read the same.
     */
    val earlierCpfpTxids: List<String> = emptyList(),
    /** The sweep of [leftover] to [destination] once the exit ended. */
    val leftoverSweep: LeftoverSweep? = null,
    /**
     * Per step txid, how many rebuilds in a row reproduced a step whose input was already spent. At two, an older
     * state of this wallet claimed its leaf, which no rebuild can recover.
     */
    val spentStepStrikes: Map<String, Int> = emptyMap(),
    /** The payment from savings that funded the exit, kept so progress never asks for it again while it confirms. */
    val fundingPaymentTxid: String? = null,
    /** Leaves an older state of this wallet claimed on-chain; builds leave them out. */
    val lostLeaves: List<LostLeaf> = emptyList(),
    /** The user opened the result of an exit that failed or lost leaves, so Emergency exit may start a new one. */
    val isResultSeen: Boolean = false,
    /** Leaves a rebuild could not exit, such as a fee-ladder leaf whose refund cannot pay its fee; left on Spark. */
    val leftBehindLeaves: List<LeftBehindLeaf> = emptyList(),
    /**
     * Rebuilds in a row over steps that keep being refused or a fee chain that lost a race, with no step confirming in
     * between. At [SparkExitDriver.STALL_REBUILDS_TO_SURFACE] the exit stops rebuilding and says it is not moving.
     */
    val stallRebuilds: Int = 0,
    /** How many steps were confirmed when the last of those rebuilds started; more since then resets the count. */
    val stallConfirmedCount: Int? = null,
    /** The tip of the last rebuild over a step the SDK could not read, so it rebuilds at most once a block. */
    val unverifiedRebuiltAtHeight: UInt? = null,
    /** The tip at which each of the exit's leaves was first seen stopped by the operators' watchtower. */
    val stoppedLeafSince: Map<String, UInt> = emptyMap(),
    /** The tip at which a build first found nothing to build while a leaf may still move. */
    val retrySince: UInt? = null,
    /** The tip of the last of those builds. */
    val retriedAt: UInt? = null,
    /**
     * The exit's leaves a build skipped while they may still move, with the rest built: tried again on their own
     * before the exit ends, at the same gaps, and let go about a week later.
     */
    val waitingLeafIds: List<String> = emptyList(),
    /** The SDK's exit data from just before the first build, imported again before every rebuild. */
    val exitStateSnapshot: String? = null,
    /** The stored record could not be read, so the exit rebuilds from the chain with the funding it kept. */
    val isRebuiltFromChain: Boolean = false,
    /** The rebuild in progress started from a Redo verdict, the only one whose reproduced spent steps strike. */
    val isRedoRebuild: Boolean = false,
    /** What confirmed SWEEP and COOPERATIVE steps of replaced generations paid to [destination], by txid. */
    val earlierReceivedSats: Map<String, ULong> = emptyMap(),
    /**
     * How the funding pays the CPFP children, [PER_NODE] for exits started on SDK 0.28 and later; null funds one UTXO
     * per branch, as exits started before did. Every prepare of the exit resumes with it.
     */
    val fundingStrategy: String? = null,
    /**
     * Cooperative leaves the operators moved on-chain but could not co-sign the recovery of, listed by a Done verdict.
     * They stay part of the exit and are retried as their own recovery until [isFailedLeavesReleased].
     */
    val failedLeaves: List<FailedLeaf> = emptyList(),
    /** The tip at which a Done verdict first listed [failedLeaves]; they are let go about a week later. */
    val failedLeafRetrySince: UInt? = null,
    /** The tip of the last retry of [failedLeaves]. */
    val failedLeafRetriedAt: UInt? = null,
    /** The week of retries ended with [failedLeaves] still not recovered: the exit is done without them. */
    val isFailedLeavesReleased: Boolean = false,
    /** The confirmed sweep of a replaced generation, so Done still names it after a retry of leaves replaced it. */
    val landedSweepTxid: String? = null,
    /** Leftover sweeps that confirmed while more funding was still at the address, earliest first. */
    val sweptLeftover: List<SweptLeftover> = emptyList(),
    /** Steps confirmed when the rebuild from the chain was adopted; one more clears [isRebuiltFromChain]. */
    val rebuiltFromChainConfirmedCount: Int? = null,
    /** The tip of the first funding-wait tick that looked for [fundingPaymentTxid]. */
    val fundingPaymentSeenAtHeight: UInt? = null,
    /** The first tip at which no host knew [fundingPaymentTxid]; cleared once one does. */
    val fundingPaymentMissingSince: UInt? = null,
    /** The user cancelled the exit after funding had reached its address; the leftover sweep sends it back. */
    val isCancelled: Boolean = false,
) {
    companion object {
        const val CURRENT_VERSION = 1

        /** Rebuilds in a row that reproduce a spent step before its leaf counts as claimed by an older state. */
        const val LOST_AFTER_STRIKES = 2

        /** Blocks a step a node accepted may go unconfirmed before the exit's fee rate counts as too low. */
        const val UNCONFIRMED_BLOCKS_TO_SURFACE = 6u

        /** Blocks a payment from savings may be unknown to every host before the exit forgets it and asks again. */
        const val OWN_PAYMENT_MISSING_BLOCKS = 1u

        /** Blocks after the first look a payment from savings may stay unadopted before the exit asks again. */
        const val OWN_PAYMENT_TIMEOUT_BLOCKS = 144u

        /** [fundingStrategy] of an exit that funds each fee-bumped transaction from a UTXO of its own. */
        const val PER_NODE = "perNode"
    }

    sealed interface Phase {
        /** Waiting for confirmed fee funding of [requiredSats] in all at the funding address before the first build. */
        data class AwaitingFunding(val requiredSats: ULong) : Phase

        /** Built and being driven to the chain. */
        data object Running : Phase

        /** A rebuild needs fee funding of [requiredSats] in all, more than the funding address received so far. */
        data class NeedsFunding(val requiredSats: ULong) : Phase

        data object Done : Phase

        data class Failed(val reason: String) : Phase

        val requiredFundingSats: ULong?
            get() = when (this) {
                is AwaitingFunding -> requiredSats
                is NeedsFunding -> requiredSats
                else -> null
            }
    }

    data class Funding(
        /** The last path component under the funding key's account. */
        val index: UInt,
        val address: String,
        val pubkey: String,
        val txid: String? = null,
        val vout: UInt? = null,
        val valueSats: ULong? = null,
    ) {
        val input: CpfpInput?
            get() {
                if (txid == null || vout == null || valueSats == null) return null
                return CpfpInput.P2wpkh(txid, vout, valueSats, pubkey)
            }
    }

    data class BroadcastAttempt(
        val generation: Int,
        val txid: String,
        val attempts: Int,
        val rejections: Int,
        val lastAttemptAt: Long,
        val lastOutcome: String,
        val isAccepted: Boolean,
        /** Its parents confirmed but an input was already spent: the keyless pass stops sending it. */
        val isInputSpent: Boolean = false,
        /** Sends in a row, parents confirmed, that came back unpaid or with missing inputs; other outcomes reset it. */
        val feeChainFailures: Int = 0,
        /** The tip when a node first accepted it, kept while it stays accepted. */
        val acceptedAtHeight: UInt? = null,
    )

    data class Leftover(
        val txid: String,
        val vout: UInt,
        val valueSats: ULong,
    )

    data class LostLeaf(val leafId: String, val valueSats: ULong)

    data class SweptLeftover(val txid: String, val valueSats: ULong)

    data class LeftBehindLeaf(val leafId: String, val valueSats: ULong, val reason: String)

    /** A cooperative leaf the operators moved on-chain; [valueSats] is 0 until a retry's quote names it. */
    data class FailedLeaf(val leafId: String, val valueSats: ULong)

    /** The leftover waits for network fees to drop before it is worth sweeping. */
    val isLeftoverWaitingForFees: Boolean
        get() {
            val sweep = leftoverSweep ?: return false
            // A sweep refused for its fee goes back to waiting on the next tick; until then it reads the same.
            return leftover.isNotEmpty() && (sweep.isWaitingForFees || (sweep.isFeeRejection && !sweep.isAccepted))
        }

    /** Savings paid funding the exit has not taken yet, such as a higher fee: nothing is asked while it confirms. */
    val isAwaitingOwnPayment: Boolean
        get() = fundingPaymentTxid != null && fundingShortfallSats != null &&
            funding.none { it.txid == fundingPaymentTxid }

    /** The exit ended while a payment from savings to its address was unresolved: it is watched until it is. */
    val isEndedPaymentPending: Boolean
        get() = !isActive && fundingPaymentTxid != null && funding.none { it.txid == fundingPaymentTxid } &&
            leftover.none { it.txid == fundingPaymentTxid }

    /** Steps still go out: the exit runs, or drives its generation while a higher fee's payment confirms. */
    val isSendingSteps: Boolean get() = phase == Phase.Running || isDrivingWhileFunding

    /** A higher fee's top-up, not a diverged rebuild: the generation out there is still driven while it confirms. */
    val isDrivingWhileFunding: Boolean
        get() = phase is Phase.NeedsFunding && recovery != null && !isRebuilding

    /**
     * [fundingPaymentTxid] at [tip], when [isKnown] says whether a host knows it: forgotten once missing for a block,
     * or 144 blocks after the first look without being adopted, so the ask and its reminder come back.
     */
    fun withOwnPaymentLooked(tip: UInt, isKnown: Boolean): SparkExitRecord {
        if (!isAwaitingOwnPayment && !isEndedPaymentPending) return this
        val seenAt = fundingPaymentSeenAtHeight ?: tip
        val missingSince = if (isKnown) null else fundingPaymentMissingSince ?: tip
        val isMissing = missingSince != null && tip >= missingSince + OWN_PAYMENT_MISSING_BLOCKS
        val isTimedOut = tip >= seenAt + OWN_PAYMENT_TIMEOUT_BLOCKS
        if (isMissing || isTimedOut) {
            return copy(fundingPaymentTxid = null, fundingPaymentSeenAtHeight = null, fundingPaymentMissingSince = null)
        }
        return copy(fundingPaymentSeenAtHeight = seenAt, fundingPaymentMissingSince = missingSince)
    }

    /** A built sweep of the leftover, saved before it is sent; a null [txid] means it was dust and nothing went. */
    data class LeftoverSweep(
        val txid: String?,
        val txHex: String?,
        val valueSats: ULong,
        val isAccepted: Boolean = false,
        val rejection: String? = null,
        val isConfirmed: Boolean = false,
        /** The rate it was built at; null for the exit's own rate. */
        val feeRateSatPerVbyte: ULong? = null,
        /** The tip when a node first accepted it, so one that does not confirm is replaced at a higher rate. */
        val sentAtHeight: UInt? = null,
        /** Earlier versions a node accepted that this one replaced; any of them confirming ends the sweep. */
        val replacedTxids: List<String> = emptyList(),
        /** With no [txid]: the tip from which the leftover waits for fees low enough to make it worth sending. */
        val waitingSince: UInt? = null,
        /** The tip at which the waiting leftover's rate was last read. */
        val checkedAtHeight: UInt? = null,
        /** The wait ran out: the leftover stays at its funding address as dust. */
        val isDustKept: Boolean = false,
        /**
         * While waiting: the least rate a node takes, read from the refusal of an earlier version. The next build pays
         * this or the network's cheapest rate, whichever is higher, even past the exit's own rate.
         */
        val refusedFeeRate: ULong? = null,
        /** While waiting after a refusal: the tip from which the next build may go out, the block after it. */
        val retryAtHeight: UInt? = null,
        /** The tip it was built at, so one no node ever takes is built again from the address. */
        val builtAtHeight: UInt? = null,
    ) {
        /** Not built, waiting for fees to drop. A sweep with no [txid] and no wait is dust kept, as before waits. */
        val isWaitingForFees: Boolean get() = txid == null && waitingSince != null && !isDustKept

        /** This version, once a node accepted it, and the earlier ones it replaced: any of them may still confirm. */
        val sentTxids: List<String> get() = listOfNotNull(txid.takeIf { isAccepted }) + replacedTxids

        /** A refusal for its fee is not final: the sweep is built again at the network's current rate. */
        val isFeeRejection: Boolean get() = rejection?.contains("fee", ignoreCase = true) == true
    }

    /** The leftover still has to be swept: not built yet, or built and neither confirmed nor finally rejected. */
    val isLeftoverSweepPending: Boolean
        get() {
            if (leftover.isEmpty()) return false
            val sweep = leftoverSweep ?: return true
            if (sweep.isWaitingForFees) return true
            return sweep.txid != null && (sweep.rejection == null || sweep.isFeeRejection) && !sweep.isConfirmed
        }

    /**
     * Emergency exit opens progress, not a new exit, while this one runs and until its leftover fee funding is swept
     * and confirmed, or kept as dust. A rejected sweep keeps it too: progress names the address the leftover is at.
     * An exit that failed or lost leaves keeps it until the user opened it once.
     */
    val showsResult: Boolean
        get() = isActive || isLeftoverSweepPending || isEndedPaymentPending ||
            (leftover.isNotEmpty() && leftoverSweep?.rejection != null) ||
            hasUnseenResult

    /** The exit ended failed or with leaves lost, and the user has not opened its result yet. */
    val hasUnseenResult: Boolean
        get() = !isActive && (phase is Phase.Failed || lostSats > 0uL) && !isResultSeen

    /** Background runs and the foreground loop keep going while the exit runs and until its leftover is swept. */
    val needsDriving: Boolean get() = isActive || isLeftoverSweepPending || isEndedPaymentPending

    val isActive: Boolean
        get() = when (phase) {
            is Phase.AwaitingFunding, Phase.Running, is Phase.NeedsFunding -> true
            Phase.Done, is Phase.Failed -> false
        }

    val fundingInputs: List<CpfpInput> get() = funding.mapNotNull { it.input }

    /** The strategy every prepare of this exit passes; null is the SDK's default, one UTXO per branch. */
    val cpfpFundingStrategy: CpfpFundingStrategy?
        get() = CpfpFundingStrategy.PER_NODE.takeIf { fundingStrategy == PER_NODE }

    /** The funding key index of this exit; records from before exits had their own index hold 0. */
    val fundingIndex: UInt get() = funding.firstOrNull()?.index ?: 0u

    /** The leaves builds select: the quote's, less those an older state claimed and those that cannot be exited. */
    val exitLeafIds: List<String>
        get() = leafIds - (lostLeaves.map { it.leafId } + leftBehindLeaves.map { it.leafId }).toSet()

    /** Every leaf of the exit was claimed by an older state, none left behind. */
    val isEveryLeafLost: Boolean
        get() = lostLeaves.isNotEmpty() && lostLeaves.map { it.leafId }.containsAll(leafIds)

    val lostSats: ULong get() = lostLeaves.fold(0uL) { sum, leaf -> sum.safe() + leaf.valueSats.safe() }

    val leftBehindLeafSats: ULong get() = leftBehindLeaves.fold(0uL) { sum, leaf -> sum.safe() + leaf.valueSats.safe() }

    /** Leaves [leafId] on Spark for [reason], valued from the current recovery, else 0 before the first build. */
    fun leaveBehind(leafId: String, reason: String): SparkExitRecord =
        copy(leftBehindLeaves = leftBehindLeaves + LeftBehindLeaf(leafId, leafValueSats(leafId), reason))

    /** What the exit knows of [leafId]'s value, from its current recovery, else 0. */
    fun leafValueSats(leafId: String): ULong = recovery?.leaves?.firstOrNull { it.leafId == leafId }?.valueSats ?: 0uL

    val fundedSats: ULong get() = funding.fold(0uL) { sum, f -> sum.safe() + (f.valueSats ?: 0uL).safe() }

    /** What the funding address still has to receive before the exit can build, or null when nothing is missing. */
    val fundingShortfallSats: ULong?
        get() {
            val required = phase.requiredFundingSats ?: return null
            val shortfall = required.safe() - fundedSats.safe()
            return shortfall.takeIf { it > 0uL }
        }

    /** Starts a new generation from a fresh build, forgetting what was broadcast for the previous one. */
    fun adopt(built: RecoverFundsResponse, atMillis: Long): SparkExitRecord = copy(
        spentStepStrikes = if (isRedoRebuild) spentStepStrikes(built) else spentStepStrikes,
        isRedoRebuild = false,
        earlierReceivedSats = earlierReceivedSats + recovery?.let { SparkExitMath.confirmedReceived(it, destination) }
            .orEmpty(),
        earlierCpfpTxids = (earlierCpfpTxids + recovery?.let(SparkExitTxids::fundingTxids).orEmpty()).distinct(),
        landedSweepTxid = recovery?.transactions
            ?.firstOrNull { it.kind == RecoveryTxKind.SWEEP && it.status is ExitTransactionStatus.Confirmed }
            ?.txid
            ?: landedSweepTxid,
        generation = generation + 1,
        recovery = built,
        broadcastLog = emptyList(),
        phase = Phase.Running,
        isRebuilding = false,
        lastBuildAt = atMillis,
        failedLeafCount = built.failed.size,
        retrySince = null,
        retriedAt = null,
        // A rebuild from the chain keeps saying so until one of its steps confirms.
        rebuiltFromChainConfirmedCount = if (isRebuiltFromChain) {
            rebuiltFromChainConfirmedCount ?: SparkExitMath.confirmedCount(built)
        } else {
            null
        },
    )

    /**
     * Whether the user may raise the exit's fee rate: it runs on a built recovery, is not rebuilding or retrying leaves
     * the operators moved, and the network finds its rate too low ([underpricedStep]) or asks more for the next block
     * ([fees]).
     */
    fun canPayHigherFee(tip: UInt?, fees: SparkExitNetworkFees?): Boolean {
        if (phase != Phase.Running || isRebuilding || recovery == null || isRetryingFailedLeaves) return false
        return underpricedStep(tip) != null || isBelowNetwork(fees)
    }

    /** The network asks more for the next block than the exit's rate. */
    fun isBelowNetwork(fees: SparkExitNetworkFees?): Boolean = fees != null && fees.fastest > feeRateSatPerVbyte

    /**
     * The exit at [rate]: with [extraSats] of funding paid in [paymentTxid], it waits for that payment as a top-up
     * and then rebuilds; with nothing to pay, it rebuilds on the next tick. Either way what has not confirmed goes
     * out again at the new rate and replaces what is in the mempool. Not a Redo, so it strikes nothing.
     */
    fun applyFeeBump(
        rate: ULong,
        extraSats: ULong,
        paymentTxid: String?,
    ): SparkExitRecord {
        val bumped = copy(feeRateSatPerVbyte = rate, isRedoRebuild = false)
        if (extraSats == 0uL) return bumped.copy(isRebuilding = true, lastBuildAt = null)
        return bumped.copy(
            phase = Phase.NeedsFunding(fundedSats.safe() + extraSats.safe()),
            fundingPaymentTxid = paymentTxid,
            fundingPaymentSeenAtHeight = null,
            fundingPaymentMissingSince = null,
        )
    }

    /** The exit retries cooperative leaves the operators moved on-chain but could not co-sign. */
    val isRetryingFailedLeaves: Boolean get() = isActive && failedLeafRetrySince != null

    /**
     * Leaves are still on their way to savings outside the running recovery's steps: cooperative ones the operators
     * could not co-sign (the recovery lists them, or their retry runs), or ones a build skipped while they may move.
     */
    val hasLeavesOutstanding: Boolean
        get() = isRetryingFailedLeaves ||
            (isActive && (recovery?.failed?.isNotEmpty() == true || waitingLeafIds.isNotEmpty()))

    /** While retrying, the current recovery still lists leaves the operators have not co-signed. */
    val isWaitingForOperators: Boolean
        get() = isRetryingFailedLeaves && recovery?.failed?.isNotEmpty() == true

    val failedLeafSats: ULong get() = failedLeaves.fold(0uL) { sum, leaf -> sum.safe() + leaf.valueSats.safe() }

    /** A build found nothing to build while a leaf may still move, and waits for it. */
    val isWaitingForLeaves: Boolean get() = retrySince != null

    /**
     * A step of this generation that keeps being refused, or whose fee chain keeps failing, and has not confirmed:
     * the chain is broken, so the exit rebuilds over it.
     */
    /**
     * A step of this generation whose last send reached no node, such as a host timing out: the exit is not on track
     * while it waits, so the deadline is not pushed back.
     */
    val unpublishedStep: BroadcastAttempt?
        get() {
            val confirmed = confirmedTxids
            return broadcastLog.firstOrNull {
                it.generation == generation && !it.isAccepted && it.txid !in confirmed &&
                    it.lastOutcome.startsWith(SparkExitBroadcastOutcome.RetryLater.PREFIX)
            }
        }

    val stuckStep: BroadcastAttempt?
        get() {
            val confirmed = confirmedTxids
            return broadcastLog.firstOrNull {
                it.generation == generation && !it.isAccepted && it.txid !in confirmed &&
                    (
                        it.rejections >= SparkExitDriver.REJECTIONS_TO_SURFACE ||
                            it.feeChainFailures >= SparkExitDriver.FEE_CHAIN_FAILURES_TO_REBUILD
                        )
            }
        }

    /** Rebuilding over a broken chain failed to move the exit often enough to stop and say so. */
    val isStalled: Boolean get() = stallRebuilds >= SparkExitDriver.STALL_REBUILDS_TO_SURFACE

    /**
     * A step whose fee the network finds too low: a node refused it for its fee, or accepted it and it has not
     * confirmed for more blocks than a fair fee rate needs at [tip]. A rebuild at the same rate does not change that.
     */
    fun underpricedStep(tip: UInt?): BroadcastAttempt? {
        val confirmed = confirmedTxids
        return broadcastLog.firstOrNull {
            if (it.generation != generation || it.txid in confirmed) return@firstOrNull false
            if (!it.isAccepted) return@firstOrNull SparkExitBroadcastClassifier.isFeeTooLow(it.lastOutcome)
            val height = it.acceptedAtHeight ?: return@firstOrNull false
            tip != null && tip > height && tip - height > UNCONFIRMED_BLOCKS_TO_SURFACE
        }
    }

    private val confirmedTxids: Set<String>
        get() = recovery?.transactions.orEmpty()
            .filter { it.status is ExitTransactionStatus.Confirmed }
            .map { it.txid }
            .toSet()

    /**
     * The strikes after adopting [built]: a step of it that had its input spent in the generation it replaces gets one
     * more; every other step drops out.
     */
    fun spentStepStrikes(built: RecoverFundsResponse): Map<String, Int> {
        val spent = broadcastLog.filter { it.generation == generation && it.isInputSpent }.map { it.txid }.toSet()
        return built.transactions
            .filter { it.txid in spent }
            .associate { it.txid to (spentStepStrikes[it.txid] ?: 0) + 1 }
    }

    /**
     * The leaves [built] would lose again: a refund of an exiting leaf that Redo rebuilds reproduced with its input
     * spent twice. Other rebuilds never strike.
     */
    fun lostLeaves(built: RecoverFundsResponse): List<LostLeaf> {
        if (!isRedoRebuild) return emptyList()
        val strikes = spentStepStrikes(built)
        val values = (recovery?.leaves.orEmpty() + built.leaves).associate { it.leafId to it.valueSats }
        return built.transactions
            .filter { it.kind == RecoveryTxKind.REFUND && (strikes[it.txid] ?: 0) >= LOST_AFTER_STRIKES }
            .mapNotNull { it.nodeId?.takeIf { id -> id in exitLeafIds } }
            .distinct()
            .map { LostLeaf(it, values[it] ?: 0uL) }
    }
}

/**
 * Reads and writes the exit record of one wallet. Writes go to a temporary file first and replace the record in one
 * move, so a crash never leaves half a record. Files in the app's storage are readable after the first unlock, so
 * the background worker can drive the exit while the device is locked.
 */
class SparkExitStore(private val file: File) {
    fun load(): SparkExitRecord? {
        if (!file.exists()) return null
        return SparkExitJson.decode(file.readText())
    }

    /**
     * Whether an exit runs, false when the record is missing. A record that cannot be read counts as running until it
     * is salvaged, so an exit never disappears over it.
     */
    fun isActive(): Boolean = runCatching { load()?.isActive == true }.getOrElse { file.exists() }

    /** The record's raw text, for salvaging one that cannot be read; null when there is none. */
    fun loadText(): String? = file.takeIf { it.exists() }?.readText()

    /** Copies an unreadable record aside for support; the salvaged record then replaces it in one atomic write. */
    fun setAside(nowMillis: Long) {
        val aside = File(file.parentFile, "${file.nameWithoutExtension}.unreadable-$nowMillis.json")
        Files.copy(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun save(record: SparkExitRecord) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        writeDurably(temp, SparkExitJson.encode(record))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun delete() {
        file.delete()
    }
}

/**
 * The ids of the leaves an older state of one wallet claimed on-chain. The operators keep listing such a leaf, so it
 * outlives the exit record that found it: a later exit neither quotes nor builds it again. Not backed up; a restored
 * wallet finds a lost leaf again in one rebuild.
 */
class SparkExitLostLeafStore(private val file: File) {
    fun load(): Set<String> {
        if (!file.exists()) return emptySet()
        return Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonPrimitive.content }.toSet()
    }

    /** Adds [leafIds], writing only when one is new; an unreadable file is written anew. */
    fun add(leafIds: Collection<String>) {
        val known = runCatching { load() }.getOrDefault(emptySet())
        if (known.containsAll(leafIds)) return
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        writeDurably(temp, JsonArray((known + leafIds).sorted().map(::JsonPrimitive)).toString())
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun delete() {
        file.delete()
    }
}

/** Writes [text] to [file] and syncs it to disk, so the atomic move after it never lands an empty file. */
internal fun writeDurably(file: File, text: String) {
    FileOutputStream(file).use {
        it.write(text.toByteArray())
        it.fd.sync()
    }
}

class SparkExitFormatError(message: String) : AppError(message)

/**
 * Converters from older record versions, each to the next version up, applied in turn by [SparkExitJson.decode]. Add
 * one here before raising [SparkExitRecord.CURRENT_VERSION], so an exit written by an earlier Bitkit keeps running.
 */
object SparkExitRecordMigration {
    val converters: Map<Int, (JsonObject) -> JsonObject> = emptyMap()
}

class SparkExitUnsupportedVersion(val version: Int) : AppError("Unsupported Spark exit record version '$version'")

/**
 * The exit record as JSON. The record's own fields follow Swift's synthesized `Codable` (camelCase, an enum case as
 * `{"running":{}}`, absent optionals left out), and `recovery` follows spark-sdk 0.27.2's serde shape: snake_case
 * fields, explicit nulls and externally tagged enums (`"Ready"`, `{"Confirmed":{"block_height":120}}`), so a stored
 * recovery is readable by the Breez CLI's `check-recover-funds`. Built by hand so the app's `Json` settings cannot
 * change it.
 */
object SparkExitJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(record: SparkExitRecord): String = recordToJson(record).toString()

    fun decode(text: String): SparkExitRecord {
        var root = json.parseToJsonElement(text).jsonObject
        var version = root.int("version")
        while (version != SparkExitRecord.CURRENT_VERSION) {
            val convert = SparkExitRecordMigration.converters[version] ?: throw SparkExitUnsupportedVersion(version)
            root = convert(root)
            version = root.int("version")
        }
        return recordFromJson(root)
    }

    /**
     * What can be read of a record [decode] refused, as a record that rebuilds from the chain: the exit's leaves, rate,
     * destination and the funding it kept, with no recovery or broadcasts. An exit that had ended stays ended. Null
     * when not even the leaves, rate and destination can be read.
     */
    fun salvage(text: String, nowMillis: Long): SparkExitRecord? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        fun <T> read(block: () -> T): T? = runCatching(block).getOrNull()
        val network = read { o.string("network") } ?: return null
        val feeRate = read { o.ulong("feeRateSatPerVbyte") } ?: return null
        val destination = read { o.string("destination") } ?: return null
        val leafIds = read { o.array("leafIds").map { it.jsonPrimitive.content } }?.takeIf { it.isNotEmpty() }
            ?: return null
        val funding = read { o.array("funding") }.orEmpty().mapNotNull {
            read {
                val f = it.jsonObject
                SparkExitRecord.Funding(
                    index = f.ulong("index").toUInt(),
                    address = f.string("address"),
                    pubkey = f.string("pubkey"),
                    txid = f.stringOrNull("txid"),
                    vout = f.ulongOrNull("vout")?.toUInt(),
                    valueSats = f.ulongOrNull("valueSats"),
                )
            }
        }
        val stored = o["phase"]?.let { read { phaseFromJson(it) } }
        val phase = when (stored) {
            is SparkExitRecord.Phase.AwaitingFunding,
            is SparkExitRecord.Phase.NeedsFunding,
            SparkExitRecord.Phase.Done,
            is SparkExitRecord.Phase.Failed,
            -> stored
            SparkExitRecord.Phase.Running, null -> SparkExitRecord.Phase.Running
        }
        val isActive = phase !is SparkExitRecord.Phase.Done && phase !is SparkExitRecord.Phase.Failed
        return SparkExitRecord(
            network = network,
            createdAt = read { o.millis("createdAt") } ?: nowMillis,
            phase = phase,
            feeRateSatPerVbyte = feeRate,
            destination = destination,
            leafIds = leafIds,
            funding = funding,
            transferId = read { o.stringOrNull("transferId") },
            expectedSats = read { o.ulong("expectedSats") } ?: 0uL,
            leafTotalSats = read { o.ulongOrNull("leafTotalSats") },
            lostLeaves = read {
                o.arrayOrEmpty("lostLeaves").map {
                    val l = it.jsonObject
                    SparkExitRecord.LostLeaf(l.string("leafId"), l.ulong("valueSats"))
                }
            }.orEmpty(),
            leftBehindLeaves = read {
                o.arrayOrEmpty("leftBehindLeaves").map {
                    val l = it.jsonObject
                    SparkExitRecord.LeftBehindLeaf(l.string("leafId"), l.ulong("valueSats"), l.string("reason"))
                }
            }.orEmpty(),
            isRebuilding = phase == SparkExitRecord.Phase.Running,
            isRebuiltFromChain = isActive,
            exitStateSnapshot = read { o.stringOrNull("exitStateSnapshot") },
            fundingStrategy = read { o.stringOrNull("fundingStrategy") },
            // An exit that had ended still sweeps what it left at its funding address, and keeps what it landed.
            leftover = read { o.arrayOrEmpty("leftover").map { leftoverFromJson(it.jsonObject) } }.orEmpty(),
            leftoverSweep = read { o.objectOrNull("leftoverSweep")?.let(::sweepFromJson) },
            // The stored recovery's children and fan-out too, when it still reads: their outputs aren't new funding.
            earlierCpfpTxids = (
                read { o.arrayOrEmpty("earlierCpfpTxids").map { it.jsonPrimitive.content } }.orEmpty() +
                    read { o.objectOrNull("recovery")?.let { SparkExitTxids.fundingTxids(recoveryFromJson(it)) } }
                        .orEmpty()
                ).distinct(),
            earlierReceivedSats = read {
                o.objectOrNull("earlierReceivedSats")?.mapValues { it.value.jsonPrimitive.content.toULong() }
            }.orEmpty(),
            landedSweepTxid = read { o.stringOrNull("landedSweepTxid") },
            sweptLeftover = read { sweptLeftoverFromJson(o) }.orEmpty(),
        )
    }

    fun encodeRecovery(recovery: RecoverFundsResponse): String = recoveryToJson(recovery).toString()

    fun decodeRecovery(text: String): RecoverFundsResponse =
        recoveryFromJson(json.parseToJsonElement(text).jsonObject)

    private fun recordToJson(r: SparkExitRecord) = buildJsonObject {
        put("version", r.version)
        put("network", r.network)
        put("createdAt", r.createdAt)
        put("phase", phaseToJson(r.phase))
        put("feeRateSatPerVbyte", r.feeRateSatPerVbyte.toLong())
        put("destination", r.destination)
        put("leafIds", buildJsonArray { r.leafIds.forEach { add(JsonPrimitive(it)) } })
        put(
            "funding",
            buildJsonArray {
                r.funding.forEach {
                    add(
                        buildJsonObject {
                            put("index", it.index.toLong())
                            put("address", it.address)
                            put("pubkey", it.pubkey)
                            it.txid?.let { txid -> put("txid", txid) }
                            it.vout?.let { vout -> put("vout", vout.toLong()) }
                            it.valueSats?.let { value -> put("valueSats", value.toLong()) }
                        },
                    )
                }
            },
        )
        put("generation", r.generation)
        r.recovery?.let { put("recovery", recoveryToJson(it)) }
        put(
            "broadcastLog",
            buildJsonArray {
                r.broadcastLog.forEach {
                    add(
                        buildJsonObject {
                            put("generation", it.generation)
                            put("txid", it.txid)
                            put("attempts", it.attempts)
                            put("rejections", it.rejections)
                            put("lastAttemptAt", it.lastAttemptAt)
                            put("lastOutcome", it.lastOutcome)
                            put("isAccepted", it.isAccepted)
                            if (it.isInputSpent) put("isInputSpent", true)
                            if (it.feeChainFailures > 0) put("feeChainFailures", it.feeChainFailures)
                            it.acceptedAtHeight?.let { height -> put("acceptedAtHeight", height.toLong()) }
                        },
                    )
                }
            },
        )
        r.transferId?.let { put("transferId", it) }
        put("expectedSats", r.expectedSats.toLong())
        r.claimableAtHeight?.let { put("claimableAtHeight", it.toLong()) }
        put(
            "leftover",
            buildJsonArray {
                r.leftover.forEach {
                    add(
                        buildJsonObject {
                            put("txid", it.txid)
                            put("vout", it.vout.toLong())
                            put("valueSats", it.valueSats.toLong())
                        },
                    )
                }
            },
        )
        put("skippedLeafCount", r.skippedLeafCount)
        put("failedLeafCount", r.failedLeafCount)
        put("isRebuilding", r.isRebuilding)
        r.lastBuildAt?.let { put("lastBuildAt", it) }
        r.leafTotalSats?.let { put("leafTotalSats", it.toLong()) }
        if (r.earlierCpfpTxids.isNotEmpty()) {
            put("earlierCpfpTxids", buildJsonArray { r.earlierCpfpTxids.forEach { add(JsonPrimitive(it)) } })
        }
        r.leftoverSweep?.let { put("leftoverSweep", sweepToJson(it)) }
        r.fundingPaymentTxid?.let { put("fundingPaymentTxid", it) }
        if (r.isResultSeen) put("isResultSeen", true)
        if (r.spentStepStrikes.isNotEmpty()) {
            put(
                "spentStepStrikes",
                buildJsonObject { r.spentStepStrikes.forEach { (txid, strikes) -> put(txid, strikes) } },
            )
        }
        putRebuildState(r)
        if (r.leftBehindLeaves.isNotEmpty()) {
            put(
                "leftBehindLeaves",
                buildJsonArray {
                    r.leftBehindLeaves.forEach {
                        add(
                            buildJsonObject {
                                put("leafId", it.leafId)
                                put("valueSats", it.valueSats.toLong())
                                put("reason", it.reason)
                            },
                        )
                    }
                },
            )
        }
        if (r.lostLeaves.isNotEmpty()) {
            put(
                "lostLeaves",
                buildJsonArray {
                    r.lostLeaves.forEach {
                        add(
                            buildJsonObject {
                                put("leafId", it.leafId)
                                put("valueSats", it.valueSats.toLong())
                            },
                        )
                    }
                },
            )
        }
    }

    private fun sweepToJson(sweep: SparkExitRecord.LeftoverSweep) = buildJsonObject {
        sweep.txid?.let { put("txid", it) }
        sweep.txHex?.let { put("txHex", it) }
        put("valueSats", sweep.valueSats.toLong())
        put("isAccepted", sweep.isAccepted)
        sweep.rejection?.let { put("rejection", it) }
        if (sweep.isConfirmed) put("isConfirmed", true)
        sweep.feeRateSatPerVbyte?.let { put("feeRateSatPerVbyte", it.toLong()) }
        sweep.sentAtHeight?.let { put("sentAtHeight", it.toLong()) }
        if (sweep.replacedTxids.isNotEmpty()) {
            put("replacedTxids", buildJsonArray { sweep.replacedTxids.forEach { add(JsonPrimitive(it)) } })
        }
        sweep.waitingSince?.let { put("waitingSince", it.toLong()) }
        sweep.checkedAtHeight?.let { put("checkedAtHeight", it.toLong()) }
        if (sweep.isDustKept) put("isDustKept", true)
        sweep.refusedFeeRate?.let { put("refusedFeeRate", it.toLong()) }
        sweep.retryAtHeight?.let { put("retryAtHeight", it.toLong()) }
        sweep.builtAtHeight?.let { put("builtAtHeight", it.toLong()) }
    }

    /** What the exit keeps to pace and bound its rebuilds and waits, each left out at its default. */
    private fun JsonObjectBuilder.putRebuildState(r: SparkExitRecord) {
        if (r.stallRebuilds > 0) put("stallRebuilds", r.stallRebuilds)
        r.stallConfirmedCount?.let { put("stallConfirmedCount", it) }
        r.unverifiedRebuiltAtHeight?.let { put("unverifiedRebuiltAtHeight", it.toLong()) }
        if (r.stoppedLeafSince.isNotEmpty()) {
            put(
                "stoppedLeafSince",
                buildJsonObject { r.stoppedLeafSince.forEach { (leafId, height) -> put(leafId, height.toLong()) } },
            )
        }
        r.retrySince?.let { put("retrySince", it.toLong()) }
        r.retriedAt?.let { put("retriedAt", it.toLong()) }
        if (r.waitingLeafIds.isNotEmpty()) {
            put("waitingLeafIds", buildJsonArray { r.waitingLeafIds.forEach { add(JsonPrimitive(it)) } })
        }
        r.exitStateSnapshot?.let { put("exitStateSnapshot", it) }
        if (r.isRebuiltFromChain) put("isRebuiltFromChain", true)
        if (r.isRedoRebuild) put("isRedoRebuild", true)
        r.fundingStrategy?.let { put("fundingStrategy", it) }
        if (r.failedLeaves.isNotEmpty()) {
            put(
                "failedLeaves",
                buildJsonArray {
                    r.failedLeaves.forEach {
                        add(
                            buildJsonObject {
                                put("leafId", it.leafId)
                                put("valueSats", it.valueSats.toLong())
                            },
                        )
                    }
                },
            )
        }
        r.failedLeafRetrySince?.let { put("failedLeafRetrySince", it.toLong()) }
        r.failedLeafRetriedAt?.let { put("failedLeafRetriedAt", it.toLong()) }
        if (r.isFailedLeavesReleased) put("isFailedLeavesReleased", true)
        r.landedSweepTxid?.let { put("landedSweepTxid", it) }
        if (r.sweptLeftover.isNotEmpty()) {
            put(
                "sweptLeftover",
                buildJsonArray {
                    r.sweptLeftover.forEach {
                        add(
                            buildJsonObject {
                                put("txid", it.txid)
                                put("valueSats", it.valueSats.toLong())
                            },
                        )
                    }
                },
            )
        }
        r.rebuiltFromChainConfirmedCount?.let { put("rebuiltFromChainConfirmedCount", it) }
        r.fundingPaymentSeenAtHeight?.let { put("fundingPaymentSeenAtHeight", it.toLong()) }
        r.fundingPaymentMissingSince?.let { put("fundingPaymentMissingSince", it.toLong()) }
        if (r.isCancelled) put("isCancelled", true)
        if (r.earlierReceivedSats.isNotEmpty()) {
            put(
                "earlierReceivedSats",
                buildJsonObject { r.earlierReceivedSats.forEach { (txid, sats) -> put(txid, sats.toLong()) } },
            )
        }
    }

    private fun recordFromJson(o: JsonObject) = SparkExitRecord(
        version = o.int("version"),
        network = o.string("network"),
        createdAt = o.millis("createdAt"),
        phase = phaseFromJson(o.getValue("phase")),
        feeRateSatPerVbyte = o.ulong("feeRateSatPerVbyte"),
        destination = o.string("destination"),
        leafIds = o.array("leafIds").map { it.jsonPrimitive.content },
        funding = o.array("funding").map {
            val f = it.jsonObject
            SparkExitRecord.Funding(
                index = f.ulong("index").toUInt(),
                address = f.string("address"),
                pubkey = f.string("pubkey"),
                txid = f.stringOrNull("txid"),
                vout = f.ulongOrNull("vout")?.toUInt(),
                valueSats = f.ulongOrNull("valueSats"),
            )
        },
        generation = o.intOrNull("generation") ?: 0,
        recovery = o.objectOrNull("recovery")?.let(::recoveryFromJson),
        broadcastLog = o.arrayOrEmpty("broadcastLog").map {
            val b = it.jsonObject
            SparkExitRecord.BroadcastAttempt(
                generation = b.int("generation"),
                txid = b.string("txid"),
                attempts = b.int("attempts"),
                rejections = b.intOrNull("rejections") ?: 0,
                lastAttemptAt = b.millis("lastAttemptAt"),
                lastOutcome = b.string("lastOutcome"),
                isAccepted = b.booleanOrNull("isAccepted") ?: false,
                isInputSpent = b.booleanOrNull("isInputSpent") ?: false,
                feeChainFailures = b.intOrNull("feeChainFailures") ?: 0,
                acceptedAtHeight = b.ulongOrNull("acceptedAtHeight")?.toUInt(),
            )
        },
        transferId = o.stringOrNull("transferId"),
        expectedSats = o.ulong("expectedSats"),
        claimableAtHeight = o.ulongOrNull("claimableAtHeight")?.toUInt(),
        leftover = o.arrayOrEmpty("leftover").map { leftoverFromJson(it.jsonObject) },
        skippedLeafCount = o.intOrNull("skippedLeafCount") ?: 0,
        failedLeafCount = o.intOrNull("failedLeafCount") ?: 0,
        isRebuilding = o.booleanOrNull("isRebuilding") ?: false,
        lastBuildAt = o.millisOrNull("lastBuildAt"),
        leafTotalSats = o.ulongOrNull("leafTotalSats"),
        earlierCpfpTxids = o.arrayOrEmpty("earlierCpfpTxids").map { it.jsonPrimitive.content },
        leftoverSweep = o.objectOrNull("leftoverSweep")?.let(::sweepFromJson),
        fundingPaymentTxid = o.stringOrNull("fundingPaymentTxid"),
        isResultSeen = o.booleanOrNull("isResultSeen") ?: false,
        spentStepStrikes = o.objectOrNull("spentStepStrikes")
            ?.mapValues { it.value.jsonPrimitive.content.toInt() }
            .orEmpty(),
        lostLeaves = o.arrayOrEmpty("lostLeaves").map {
            val l = it.jsonObject
            SparkExitRecord.LostLeaf(l.string("leafId"), l.ulong("valueSats"))
        },
        leftBehindLeaves = o.arrayOrEmpty("leftBehindLeaves").map {
            val l = it.jsonObject
            SparkExitRecord.LeftBehindLeaf(l.string("leafId"), l.ulong("valueSats"), l.string("reason"))
        },
        stallRebuilds = o.intOrNull("stallRebuilds") ?: 0,
        stallConfirmedCount = o.intOrNull("stallConfirmedCount"),
        unverifiedRebuiltAtHeight = o.ulongOrNull("unverifiedRebuiltAtHeight")?.toUInt(),
        stoppedLeafSince = o.objectOrNull("stoppedLeafSince")
            ?.mapValues { it.value.jsonPrimitive.content.toUInt() }
            .orEmpty(),
        retrySince = o.ulongOrNull("retrySince")?.toUInt(),
        retriedAt = o.ulongOrNull("retriedAt")?.toUInt(),
        waitingLeafIds = o.arrayOrEmpty("waitingLeafIds").map { it.jsonPrimitive.content },
        exitStateSnapshot = o.stringOrNull("exitStateSnapshot"),
        isRebuiltFromChain = o.booleanOrNull("isRebuiltFromChain") ?: false,
        isRedoRebuild = o.booleanOrNull("isRedoRebuild") ?: false,
        fundingStrategy = o.stringOrNull("fundingStrategy"),
        failedLeaves = o.arrayOrEmpty("failedLeaves").map {
            val l = it.jsonObject
            SparkExitRecord.FailedLeaf(l.string("leafId"), l.ulong("valueSats"))
        },
        failedLeafRetrySince = o.ulongOrNull("failedLeafRetrySince")?.toUInt(),
        failedLeafRetriedAt = o.ulongOrNull("failedLeafRetriedAt")?.toUInt(),
        isFailedLeavesReleased = o.booleanOrNull("isFailedLeavesReleased") ?: false,
        landedSweepTxid = o.stringOrNull("landedSweepTxid"),
        sweptLeftover = sweptLeftoverFromJson(o),
        rebuiltFromChainConfirmedCount = o.intOrNull("rebuiltFromChainConfirmedCount"),
        fundingPaymentSeenAtHeight = o.ulongOrNull("fundingPaymentSeenAtHeight")?.toUInt(),
        fundingPaymentMissingSince = o.ulongOrNull("fundingPaymentMissingSince")?.toUInt(),
        isCancelled = o.booleanOrNull("isCancelled") ?: false,
        earlierReceivedSats = o.objectOrNull("earlierReceivedSats")
            ?.mapValues { it.value.jsonPrimitive.content.toULong() }
            .orEmpty(),
    )

    private fun sweptLeftoverFromJson(o: JsonObject) = o.arrayOrEmpty("sweptLeftover").map {
        val l = it.jsonObject
        SparkExitRecord.SweptLeftover(l.string("txid"), l.ulong("valueSats"))
    }

    private fun leftoverFromJson(l: JsonObject) =
        SparkExitRecord.Leftover(l.string("txid"), l.ulong("vout").toUInt(), l.ulong("valueSats"))

    private fun sweepFromJson(it: JsonObject) =
        SparkExitRecord.LeftoverSweep(
            txid = it.stringOrNull("txid"),
            txHex = it.stringOrNull("txHex"),
            valueSats = it.ulong("valueSats"),
            isAccepted = it.booleanOrNull("isAccepted") ?: false,
            rejection = it.stringOrNull("rejection"),
            isConfirmed = it.booleanOrNull("isConfirmed") ?: false,
            feeRateSatPerVbyte = it.ulongOrNull("feeRateSatPerVbyte"),
            sentAtHeight = it.ulongOrNull("sentAtHeight")?.toUInt(),
            replacedTxids = it.arrayOrEmpty("replacedTxids").map { txid -> txid.jsonPrimitive.content },
            waitingSince = it.ulongOrNull("waitingSince")?.toUInt(),
            checkedAtHeight = it.ulongOrNull("checkedAtHeight")?.toUInt(),
            isDustKept = it.booleanOrNull("isDustKept") ?: false,
            refusedFeeRate = it.ulongOrNull("refusedFeeRate"),
            retryAtHeight = it.ulongOrNull("retryAtHeight")?.toUInt(),
            builtAtHeight = it.ulongOrNull("builtAtHeight")?.toUInt(),
        )

    private fun phaseToJson(phase: SparkExitRecord.Phase): JsonObject = when (phase) {
        is SparkExitRecord.Phase.AwaitingFunding ->
            tagged("awaitingFunding", buildJsonObject { put("requiredSats", phase.requiredSats.toLong()) })
        SparkExitRecord.Phase.Running -> tagged("running", JsonObject(emptyMap()))
        is SparkExitRecord.Phase.NeedsFunding ->
            tagged("needsFunding", buildJsonObject { put("requiredSats", phase.requiredSats.toLong()) })
        SparkExitRecord.Phase.Done -> tagged("done", JsonObject(emptyMap()))
        is SparkExitRecord.Phase.Failed -> tagged("failed", buildJsonObject { put("reason", phase.reason) })
    }

    private fun phaseFromJson(element: JsonElement): SparkExitRecord.Phase {
        val (tag, fields) = variant(element)
        return when (tag) {
            "awaitingFunding" -> SparkExitRecord.Phase.AwaitingFunding(requireFields(fields).ulong("requiredSats"))
            "running" -> SparkExitRecord.Phase.Running
            "needsFunding" -> SparkExitRecord.Phase.NeedsFunding(requireFields(fields).ulong("requiredSats"))
            "done" -> SparkExitRecord.Phase.Done
            "failed" -> SparkExitRecord.Phase.Failed(requireFields(fields).string("reason"))
            else -> throw unknown(tag)
        }
    }

    private fun recoveryToJson(r: RecoverFundsResponse) = buildJsonObject {
        put("recoverable_value_sats", r.recoverableValueSats.toLong())
        put("total_fee_sats", r.totalFeeSats.toLong())
        put("cooperative_fee_sats", r.cooperativeFeeSats.toLong())
        put("cpfp_fee_sats", r.cpfpFeeSats.toLong())
        put("fanout_fee_sats", r.fanoutFeeSats.toLong())
        put("refund_fee_sats", r.refundFeeSats.toLong())
        put("sweep_fee_sats", r.sweepFeeSats.toLong())
        put("leaves", JsonArray(r.leaves.map(::leafToJson)))
        put("failed", JsonArray(r.failed.map(::failureToJson)))
        put("transactions", JsonArray(r.transactions.map(::transactionToJson)))
        put("funding_inputs", JsonArray(r.fundingInputs.map(::cpfpInputToJson)))
        put("fee_rate_sat_per_vbyte", r.feeRateSatPerVbyte.toLong())
        put("destination", r.destination)
    }

    private fun recoveryFromJson(o: JsonObject) = RecoverFundsResponse(
        recoverableValueSats = o.ulong("recoverable_value_sats"),
        totalFeeSats = o.ulong("total_fee_sats"),
        cooperativeFeeSats = o.ulong("cooperative_fee_sats"),
        cpfpFeeSats = o.ulong("cpfp_fee_sats"),
        fanoutFeeSats = o.ulong("fanout_fee_sats"),
        refundFeeSats = o.ulong("refund_fee_sats"),
        sweepFeeSats = o.ulong("sweep_fee_sats"),
        leaves = o.array("leaves").map { leafFromJson(it.jsonObject) },
        failed = o.array("failed").map { failureFromJson(it.jsonObject) },
        transactions = o.array("transactions").map { transactionFromJson(it.jsonObject) },
        fundingInputs = o.array("funding_inputs").map(::cpfpInputFromJson),
        feeRateSatPerVbyte = o.ulong("fee_rate_sat_per_vbyte"),
        destination = o.string("destination"),
    )

    private val methods = mapOf(RecoveryMethod.COOPERATIVE to "Cooperative", RecoveryMethod.UNILATERAL to "Unilateral")

    private val kinds = mapOf(
        RecoveryTxKind.COOPERATIVE to "Cooperative",
        RecoveryTxKind.FAN_OUT to "FanOut",
        RecoveryTxKind.NODE to "Node",
        RecoveryTxKind.REFUND to "Refund",
        RecoveryTxKind.SWEEP to "Sweep",
    )

    private fun leafToJson(leaf: RecoverFundsLeaf) = buildJsonObject {
        put("leaf_id", leaf.leafId)
        put("value_sats", leaf.valueSats.toLong())
        put("method", methods.getValue(leaf.method))
    }

    private fun leafFromJson(o: JsonObject): RecoverFundsLeaf {
        val name = o.string("method")
        val method = methods.entries.firstOrNull { it.value == name }?.key ?: throw unknown(name)
        return RecoverFundsLeaf(o.string("leaf_id"), o.ulong("value_sats"), method)
    }

    private fun failureToJson(failure: CooperativeRecoveryFailure) = buildJsonObject {
        put("leaf_id", failure.leafId)
        put("output_txid", failure.outputTxid)
        put("output_vout", failure.outputVout.toLong())
        put(
            "error",
            when (val error = failure.error) {
                is CooperativeRecoveryError.ReplacementFeeTooLow -> tagged(
                    "ReplacementFeeTooLow",
                    buildJsonObject {
                        put("required_fee_sats", error.requiredFeeSats.toLong())
                        put("required_fee_rate_sat_per_vbyte", error.requiredFeeRateSatPerVbyte.toLong())
                    },
                )
                is CooperativeRecoveryError.OperatorsUnavailable ->
                    tagged("OperatorsUnavailable", buildJsonObject { put("message", error.message) })
                is CooperativeRecoveryError.Generic ->
                    tagged("Generic", buildJsonObject { put("message", error.message) })
            },
        )
    }

    private fun failureFromJson(o: JsonObject): CooperativeRecoveryFailure {
        val (tag, fields) = variant(o.getValue("error"))
        val error = when (tag) {
            "ReplacementFeeTooLow" -> requireFields(fields).let {
                CooperativeRecoveryError.ReplacementFeeTooLow(
                    it.ulong("required_fee_sats"),
                    it.ulong("required_fee_rate_sat_per_vbyte"),
                )
            }
            "OperatorsUnavailable" -> CooperativeRecoveryError.OperatorsUnavailable(
                requireFields(fields).string("message")
            )
            "Generic" -> CooperativeRecoveryError.Generic(requireFields(fields).string("message"))
            else -> throw unknown(tag)
        }
        return CooperativeRecoveryFailure(
            o.string("leaf_id"),
            o.string("output_txid"),
            o.ulong("output_vout").toUInt(),
            error
        )
    }

    private fun transactionToJson(tx: RecoveryTransaction) = buildJsonObject {
        put("kind", kinds.getValue(tx.kind))
        put("node_id", tx.nodeId?.let(::JsonPrimitive) ?: JsonNull)
        put("txid", tx.txid)
        put("tx_hex", tx.txHex)
        put("cpfp_tx_hex", tx.cpfpTxHex?.let(::JsonPrimitive) ?: JsonNull)
        put("csv_timelock_blocks", tx.csvTimelockBlocks?.let { JsonPrimitive(it.toLong()) } ?: JsonNull)
        put("depends_on", JsonArray(tx.dependsOn.map(::JsonPrimitive)))
        put("status", statusToJson(tx.status))
    }

    private fun transactionFromJson(o: JsonObject): RecoveryTransaction {
        val kindName = o.string("kind")
        return RecoveryTransaction(
            kind = kinds.entries.firstOrNull { it.value == kindName }?.key ?: throw unknown(kindName),
            nodeId = o.stringOrNull("node_id"),
            txid = o.string("txid"),
            txHex = o.string("tx_hex"),
            cpfpTxHex = o.stringOrNull("cpfp_tx_hex"),
            csvTimelockBlocks = o.ulongOrNull("csv_timelock_blocks")?.toUInt(),
            dependsOn = o.array("depends_on").map { it.jsonPrimitive.content },
            status = statusFromJson(o.getValue("status")),
        )
    }

    private fun statusToJson(status: ExitTransactionStatus): JsonElement = when (status) {
        is ExitTransactionStatus.Confirmed -> tagged(
            "Confirmed",
            buildJsonObject { put("block_height", status.blockHeight?.let { JsonPrimitive(it.toLong()) } ?: JsonNull) },
        )
        ExitTransactionStatus.Ready -> JsonPrimitive("Ready")
        ExitTransactionStatus.WaitingForDependencies -> JsonPrimitive("WaitingForDependencies")
        is ExitTransactionStatus.WaitingForTimelock -> tagged(
            "WaitingForTimelock",
            buildJsonObject {
                put("spendable_at_height", status.spendableAtHeight?.let { JsonPrimitive(it.toLong()) } ?: JsonNull)
            },
        )
        ExitTransactionStatus.Unverified -> JsonPrimitive("Unverified")
    }

    private fun statusFromJson(element: JsonElement): ExitTransactionStatus {
        val (tag, fields) = variant(element)
        return when (tag) {
            "Confirmed" -> ExitTransactionStatus.Confirmed(requireFields(fields).ulongOrNull("block_height")?.toUInt())
            "Ready" -> ExitTransactionStatus.Ready
            "WaitingForDependencies" -> ExitTransactionStatus.WaitingForDependencies
            "WaitingForTimelock" -> ExitTransactionStatus.WaitingForTimelock(
                requireFields(fields).ulongOrNull("spendable_at_height")?.toUInt(),
            )
            "Unverified" -> ExitTransactionStatus.Unverified
            else -> throw unknown(tag)
        }
    }

    private fun cpfpInputToJson(input: CpfpInput): JsonObject = when (input) {
        is CpfpInput.P2wpkh -> tagged(
            "P2wpkh",
            buildJsonObject {
                put("txid", input.txid)
                put("vout", input.vout.toLong())
                put("value_sats", input.valueSats.toLong())
                put("pubkey", input.pubkey)
            },
        )
        is CpfpInput.P2tr -> tagged(
            "P2tr",
            buildJsonObject {
                put("txid", input.txid)
                put("vout", input.vout.toLong())
                put("value_sats", input.valueSats.toLong())
                put("pubkey", input.pubkey)
            },
        )
        is CpfpInput.Custom -> tagged(
            "Custom",
            buildJsonObject {
                put("txid", input.txid)
                put("vout", input.vout.toLong())
                put("value_sats", input.valueSats.toLong())
                put("script_pubkey_hex", input.scriptPubkeyHex)
                put("signed_input_weight", input.signedInputWeight.toLong())
            },
        )
    }

    private fun cpfpInputFromJson(element: JsonElement): CpfpInput {
        val (tag, fields) = variant(element)
        val f = requireFields(fields)
        val txid = f.string("txid")
        val vout = f.ulong("vout").toUInt()
        val value = f.ulong("value_sats")
        return when (tag) {
            "P2wpkh" -> CpfpInput.P2wpkh(txid, vout, value, f.string("pubkey"))
            "P2tr" -> CpfpInput.P2tr(txid, vout, value, f.string("pubkey"))
            "Custom" -> CpfpInput.Custom(
                txid,
                vout,
                value,
                f.string("script_pubkey_hex"),
                f.ulong("signed_input_weight")
            )
            else -> throw unknown(tag)
        }
    }

    /** serde's default enum shape: a unit variant is its name, a variant with fields an object keyed by its name. */
    private fun variant(element: JsonElement): Pair<String, JsonObject?> {
        if (element is JsonPrimitive && element.isString) return element.content to null
        val o = element.jsonObject
        require(o.size == 1) { "Expected one variant key, got '${o.keys}'" }
        val (tag, fields) = o.entries.first()
        return tag to (fields as? JsonObject)
    }

    private fun tagged(tag: String, fields: JsonObject) = JsonObject(mapOf(tag to fields))

    private fun requireFields(fields: JsonObject?): JsonObject = requireNotNull(
        fields
    ) { "Variant is missing its fields" }

    private fun unknown(tag: String) = SparkExitFormatError("Unknown variant '$tag'")

    private fun JsonObject.value(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.string(key: String) = requireNotNull(value(key)) { "Missing '$key'" }.content

    private fun JsonObject.stringOrNull(key: String) = value(key)?.content

    private fun JsonObject.ulong(key: String) = string(key).toULong()

    private fun JsonObject.ulongOrNull(key: String) = value(key)?.content?.toULong()

    private fun JsonObject.int(key: String) = string(key).toInt()

    private fun JsonObject.intOrNull(key: String) = value(key)?.content?.toInt()

    private fun JsonObject.booleanOrNull(key: String) = value(key)?.content?.toBooleanStrictOrNull()

    /** Swift writes dates as a millisecond count that may carry a fraction. */
    private fun JsonObject.millis(key: String) = string(key).toBigDecimal().toLong()

    private fun JsonObject.millisOrNull(key: String) = value(key)?.content?.toBigDecimal()?.toLong()

    private fun JsonObject.array(key: String) = requireNotNull(this[key]) { "Missing '$key'" }.jsonArray

    private fun JsonObject.arrayOrEmpty(key: String) = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.objectOrNull(key: String) = this[key] as? JsonObject
}
