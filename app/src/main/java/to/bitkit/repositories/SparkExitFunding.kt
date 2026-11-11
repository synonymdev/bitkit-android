package to.bitkit.repositories

import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.PrepareRecoverFundsResponse
import breez_sdk_spark.RecoverFundsResponse
import breez_sdk_spark.RecoveryTransaction
import breez_sdk_spark.RecoveryTxKind
import com.synonym.bitkitcore.FeeRates
import org.lightningdevkit.ldknode.Network
import to.bitkit.models.FeeRate
import to.bitkit.models.safe
import to.bitkit.utils.AppError
import to.bitkit.utils.BlockTimeHelpers
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The key that pays a Spark exit's CPFP fees. It derives from the wallet seed on an account no wallet of Bitkit's
 * derives, so ldk-node never spends the funding UTXO or the CPFP change chained back to it.
 */
object SparkExitFundingKey {
    /** The BIP84 account of the funding key, outside every account ldk-node and the sweep tools derive. */
    const val ACCOUNT = 7797

    private const val WIF_LENGTH = 38
    private const val PAYLOAD_LENGTH = 34
    private const val SECRET_LENGTH = 32
    private const val COMPRESSED_FLAG: Byte = 0x01
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    data class Derived(
        val address: String,
        /** Compressed public key, hex, as `CpfpInput.P2wpkh` takes it. */
        val pubkey: String,
        val secretKey: ByteArray,
    )

    class InvalidWif : AppError("Invalid WIF private key")

    fun path(index: Int, network: Network): String {
        val coin = if (network == Network.BITCOIN) 0 else 1
        return "m/84'/$coin'/$ACCOUNT'/0/$index"
    }

    /** The 32-byte secret of a compressed-key WIF: base58check, version byte first, `0x01` compression flag last. */
    fun secretKey(wif: String): ByteArray {
        val decoded = base58Decode(wif) ?: throw InvalidWif()
        if (decoded.size != WIF_LENGTH) throw InvalidWif()
        val payload = decoded.copyOfRange(0, PAYLOAD_LENGTH)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val checksum = sha256.digest(sha256.digest(payload)).copyOfRange(0, 4)
        if (!checksum.contentEquals(decoded.copyOfRange(PAYLOAD_LENGTH, WIF_LENGTH))) throw InvalidWif()
        if (payload[PAYLOAD_LENGTH - 1] != COMPRESSED_FLAG) throw InvalidWif()
        return payload.copyOfRange(1, 1 + SECRET_LENGTH)
    }

    internal fun base58Decode(text: String): ByteArray? {
        var value = BigInteger.ZERO
        for (char in text) {
            val digit = ALPHABET.indexOf(char).takeIf { it >= 0 } ?: return null
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val leadingZeros = text.takeWhile { it == '1' }.length
        return ByteArray(leadingZeros) + bytes
    }
}

/**
 * What an exit costs and returns, from a `prepareRecoverFunds` quote, next to Spark's balance when it was taken
 * ([sparkBalanceSats]), so value the quote cannot exit is named rather than silently left out. [lostLeafSats] are the
 * values of the leaves an older state claimed that the operators still list, which the quote leaves out.
 */
data class SparkExitQuote(
    val prepared: PrepareRecoverFundsResponse,
    val sparkBalanceSats: ULong? = null,
    val lostLeafSats: List<ULong> = emptyList(),
) {
    /** The value of the leaves the exit takes on-chain. */
    val exitSats: ULong get() = prepared.recoverableValueSats

    val networkFeeSats: ULong get() = prepared.totalFeeSats

    /** What savings gains overall: the exited value less every fee, those paid from the fee funding included. */
    val receiveSats: ULong get() = exitSats.safe() - networkFeeSats.safe()

    /** The on-chain payment that funds the CPFP fees, or 0 when every leaf is recovered cooperatively. */
    val fundingSats: ULong get() = prepared.funding?.singleUtxoSats ?: 0uL

    /**
     * What the sweep and the cooperative recoveries pay to savings: [receiveSats] plus the fee funding the exit did
     * not spend, which the sweep returns.
     */
    val incomingSats: ULong get() = receiveSats.safe() + fundingSats.safe()

    val leftBehindSats: ULong get() = prepared.skipped.fold(0uL) { sum, leaf -> sum.safe() + leaf.valueSats.safe() }

    val leafIds: List<String> get() = prepared.leaves.map { it.leafId }

    /** The value of the leaves the quote lists, which the exit takes on-chain. */
    val leafTotalSats: ULong get() = prepared.leaves.fold(0uL) { sum, leaf -> sum.safe() + leaf.valueSats.safe() }

    /**
     * What the quote cannot exit now: the leaves it skips, and Spark's balance beyond every leaf it lists, such as a
     * counter-swap still unclaimed. Null when nothing.
     */
    val unexitableSats: ULong? get() = SparkExitMath.unexitableSats(sparkBalanceSats, this)

    /**
     * What stays on Spark once the exit took its leaves: Spark's balance beyond them, skipped leaves included, less the
     * lost leaves it still counts until a sync drops them. Null when nothing.
     */
    val outsideSats: ULong?
        get() = SparkExitMath.outsideExitSats(leafTotalSats, sparkBalanceSats)
            ?.let { SparkExitMath.beyondLostLeaves(it, lostLeafSats) }
            ?.takeIf { it > 0uL }

    /** Spark's balance beyond every leaf the quote lists, skipped ones included, less the lost leaves it holds. */
    val beyondLeavesSats: ULong?
        get() {
            val leaves = prepared.leaves.fold(0uL) { sum, leaf -> sum.safe() + leaf.valueSats.safe() }
            val beyond = SparkExitMath.outsideExitSats(leaves.safe() + leftBehindSats.safe(), sparkBalanceSats)
            return beyond?.let { SparkExitMath.beyondLostLeaves(it, lostLeafSats) }?.takeIf { it > 0uL }
        }

    /**
     * How many UTXOs the SDK splits the fee funding into, one per transaction it fee-bumps under per-node funding: the
     * funding screen names it when more than one.
     */
    val fundedSteps: Int get() = prepared.funding?.perBranch?.size ?: 0

    /** Breez's rule: only exit when the value is more than its fees. */
    val isWorthExiting: Boolean get() = prepared.leaves.isNotEmpty() && exitSats > networkFeeSats
}

object SparkExitMath {
    /** The block time of Spark's regtest, which mines one every ~30 seconds. */
    private val REGTEST_BLOCK_TIME = 30.seconds

    private val BLOCK_TIME = 10.minutes

    /**
     * The countdown target: [tip] plus [blocksToFinish], so it does not jump as steps confirm. Without a tip, the
     * latest block at which a step already waiting for its timelock can confirm.
     */
    fun claimableAtHeight(recovery: RecoverFundsResponse, tip: UInt? = null): UInt? {
        if (tip != null) return blocksToFinish(recovery.transactions, tip)?.let { tip + it }
        return recovery.transactions
            .mapNotNull { (it.status as? ExitTransactionStatus.WaitingForTimelock)?.spendableAtHeight }
            .maxOrNull()
    }

    /**
     * Blocks until the last step can confirm: the longest dependency path left, each step costing its remaining
     * timelock plus a block to confirm in. A ready step's timelock is behind it; one waiting for its timelock counts
     * down from the height the SDK gave; one not started yet waits its whole relative timelock after its parents.
     * Null once every step confirmed.
     */
    fun blocksToFinish(transactions: List<RecoveryTransaction>, tip: UInt): UInt? {
        val byTxid = transactions.associateBy { it.txid }
        val memo = mutableMapOf<String, UInt>()
        fun after(tx: RecoveryTransaction, depth: Int): UInt = memo.getOrPut(tx.txid) {
            val status = tx.status
            if (status is ExitTransactionStatus.Confirmed || depth > transactions.size) return@getOrPut 0u
            val timelock = when (status) {
                ExitTransactionStatus.Ready -> 0u
                is ExitTransactionStatus.WaitingForTimelock ->
                    status.spendableAtHeight?.let { if (it > tip) it - tip else 0u } ?: tx.csvTimelockBlocks ?: 0u
                else -> tx.csvTimelockBlocks ?: 0u
            }
            val waits = tx.dependsOn.mapNotNull { byTxid[it] }.maxOfOrNull { after(it, depth + 1) } ?: 0u
            waits + timelock + 1u
        }
        return transactions.filter { it.status !is ExitTransactionStatus.Confirmed }.maxOfOrNull { after(it, 0) }
    }

    /**
     * The rate the leftover is swept at: the network's cheapest ([economy]), so as little of it as possible goes to
     * fees, never above the exit's own rate, at least 1 sat/vB. The exit's rate when the network's cannot be read.
     */
    fun sweepFeeRate(economy: ULong?, exitRate: ULong): ULong = maxOf(1uL, minOf(economy ?: exitRate, exitRate))

    /** The fee one more P2WPKH funding input adds to a build at [feeRateSatPerVbyte]. */
    fun inputFeeSats(feeRateSatPerVbyte: ULong): ULong = P2WPKH_INPUT_VBYTES.safe() * feeRateSatPerVbyte.safe()

    /**
     * What reached savings: what the SWEEP and COOPERATIVE steps of [record]'s recovery pay its destination, with
     * those of replaced generations that had confirmed. Null when the destination cannot be read.
     */
    fun receivedSats(record: SparkExitRecord): ULong? {
        val script = runCatching { AddressScript.of(record.destination) }.getOrNull() ?: return null
        val current = record.recovery?.let { paidTo(it.transactions, script) }.orEmpty()
        return (record.earlierReceivedSats + current).values.fold(0uL) { sum, sats -> sum.safe() + sats.safe() }
    }

    /** What the confirmed SWEEP and COOPERATIVE steps of [recovery] paid [destination], by txid. */
    fun confirmedReceived(recovery: RecoverFundsResponse, destination: String): Map<String, ULong> {
        val script = runCatching { AddressScript.of(destination) }.getOrNull() ?: return emptyMap()
        return paidTo(recovery.transactions.filter { it.status is ExitTransactionStatus.Confirmed }, script)
    }

    private fun paidTo(transactions: List<RecoveryTransaction>, script: ByteArray): Map<String, ULong> = transactions
        .filter { it.kind == RecoveryTxKind.SWEEP || it.kind == RecoveryTxKind.COOPERATIVE }
        .associate { tx ->
            val outputs = SparkExitTxOutputs.decode(tx.txHex).orEmpty().filter { it.script.contentEquals(script) }
            tx.txid to outputs.fold(0uL) { sum, output -> sum.safe() + output.valueSats.safe() }
        }
        .filterValues { it > 0uL }

    /**
     * Whether a build that waits for a leaf that may still move tries again at [tip]: six blocks after it started
     * waiting at [since], then at doubling gaps up to a day.
     */
    fun isRetryDue(since: UInt, retriedAt: UInt?, tip: UInt): Boolean {
        val last = maxOf(since, retriedAt ?: since)
        val gap = (last - since).coerceIn(RETRY_BLOCKS, RETRY_MAX_BLOCKS)
        return tip >= last && tip - last >= gap
    }

    /** A wait that started at [since] has lasted about a week by [tip]: what is still stuck is let go. */
    fun isRetryReleased(since: UInt, tip: UInt): Boolean = tip >= since && tip - since >= RELEASE_BLOCKS

    /** The next height above [tip] at which a timelocked step matures, for the reminder. */
    fun nextMaturityHeight(recovery: RecoverFundsResponse, tip: UInt): UInt? = recovery.transactions
        .mapNotNull { (it.status as? ExitTransactionStatus.WaitingForTimelock)?.spendableAtHeight }
        .filter { it > tip }
        .minOrNull()

    /** Whether every refund's timelock is behind it: none still waits for its parents or its height. */
    fun areTimelocksMatured(transactions: List<RecoveryTransaction>): Boolean {
        val refunds = transactions.filter { it.kind == RecoveryTxKind.REFUND }
        return refunds.isNotEmpty() && refunds.none {
            it.status == ExitTransactionStatus.WaitingForDependencies ||
                it.status is ExitTransactionStatus.WaitingForTimelock
        }
    }

    /**
     * The first block that can include [tx], as the SDK computes it: for each input with a BIP 68 block lock, the
     * confirmation height of the transaction it spends plus the lock, the highest of them. [parentHeights] holds the
     * heights read for the parents, null where unknown, which leaves the whole step unknown. A transaction that cannot
     * be decoded counts [RecoveryTransaction.csvTimelockBlocks] from each of its `dependsOn`. Zero when nothing is
     * locked.
     */
    fun spendableAtHeight(tx: RecoveryTransaction, parentHeights: Map<String, UInt?>): UInt? {
        val locks = SparkExitTxInputs.decode(tx.txHex)
            ?.filter { it.relativeLockBlocks > 0u }
            ?.map { it.prevTxid to it.relativeLockBlocks }
            ?: tx.dependsOn.map { it to (tx.csvTimelockBlocks ?: 0u) }
        var spendable = 0u
        for ((parent, blocks) in locks) {
            val height = parentHeights[parent] ?: return null
            spendable = maxOf(spendable, height + blocks)
        }
        return spendable
    }

    /** Nodes accept a transaction that would be valid in the next block. */
    fun isMature(spendableAtHeight: UInt, tip: UInt): Boolean = tip + 1u >= spendableAtHeight

    /** The steps already in a block, for progress. */
    fun confirmedCount(recovery: RecoverFundsResponse): Int =
        recovery.transactions.count { it.status is ExitTransactionStatus.Confirmed }

    /**
     * UTXOs at the funding address that are new funding: neither an adopted input nor an output a CPFP child or the
     * fan-out of this or an earlier generation paid there.
     */
    fun newFunding(utxos: List<EsploraUtxo>, record: SparkExitRecord): List<EsploraUtxo> {
        val adopted = record.funding.mapNotNull { f -> f.txid?.let { "$it:${f.vout ?: 0u}" } }.toSet()
        val chained = (record.recovery?.let(SparkExitTxids::fundingTxids).orEmpty() + record.earlierCpfpTxids).toSet()
        return utxos.filter { "${it.txid}:${it.vout}" !in adopted && it.txid !in chained }
    }

    /** The confirmed value at the funding address that is not new funding: unspent inputs and their CPFP change. */
    fun heldSats(utxos: List<EsploraUtxo>, record: SparkExitRecord): ULong {
        val fresh = newFunding(utxos, record).toSet()
        return utxos
            .filter { it.status.confirmed && it !in fresh }
            .fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }
    }

    /**
     * The funding a rebuild needs in all, from the SDK's [requiredSats]: the total its inputs must hold, not the extra.
     * What is still [held] at the address counts, so the shortfall is `requiredSats - held`, at least 1; without a
     * reading of the address, `max(requiredSats, fundedSats)`, right for a first build where nothing is spent yet. The
     * SDK sized [requiredSats] on the inputs it had, so a missing amount adds [inputFeeSats] for the coin that pays it.
     */
    fun neededFunding(requiredSats: ULong, fundedSats: ULong, held: ULong?, inputFeeSats: ULong = 0uL): ULong {
        if (held == null) {
            return if (requiredSats > fundedSats) requiredSats.safe() + inputFeeSats.safe() else fundedSats
        }
        val missing = if (requiredSats > held) (requiredSats - held).safe() + inputFeeSats.safe() else 0uL
        return fundedSats.safe() + maxOf(missing, 1uL).safe()
    }

    /** The skipped leaves of [quote] plus Spark's [balanceSats] beyond every leaf it lists, or null when nothing. */
    fun unexitableSats(balanceSats: ULong?, quote: SparkExitQuote): ULong? {
        val beyond = quote.copy(sparkBalanceSats = balanceSats).beyondLeavesSats ?: 0uL
        return (quote.leftBehindSats.safe() + beyond.safe()).takeIf { it > 0uL }
    }

    /**
     * What Spark's [balanceSats] holds beyond [record]'s leaves, in any phase: what a stopped exit leaves on Spark,
     * such as value it could not exit. Lost leaves count only while the balance still holds them ([beyondLostLeaves]).
     */
    fun staysOnSparkSats(balanceSats: ULong?, record: SparkExitRecord?): ULong? {
        val leafTotal = record?.leafTotalSats ?: return null
        val liveLeafTotal = if (leafTotal > record.lostSats) leafTotal - record.lostSats else 0uL
        val beyond = outsideExitSats(liveLeafTotal, balanceSats) ?: return null
        return beyondLostLeaves(beyond, record.lostLeaves.map { it.valueSats }).takeIf { it > 0uL }
    }

    /**
     * [sats] less the lost leaves it still holds. Spark's balance keeps a leaf an older state claimed until a later
     * sync drops it, so each lost leaf, largest first, is taken out only while what is left still covers it.
     */
    fun beyondLostLeaves(sats: ULong, lostLeafSats: List<ULong>): ULong =
        lostLeafSats.sortedDescending().fold(sats) { left, lost -> if (left >= lost) left - lost else left }

    /** Spark's balance beyond the exit's leaves while it still counts them, or null. */
    fun outsideExitSats(leafTotalSats: ULong?, balanceSats: ULong?): ULong? {
        if (leafTotalSats == null || balanceSats == null || balanceSats <= leafTotalSats) return null
        return balanceSats - leafTotalSats
    }

    /**
     * A node refused the savings payment of [record]'s fee funding, and none was adopted since: only before the exit is
     * funded, for the payment the start made.
     */
    fun isSavingsPaymentRefused(record: SparkExitRecord, attempt: OnchainSendAttempt?): Boolean {
        if (!record.isActive || record.phase !is SparkExitRecord.Phase.AwaitingFunding) return false
        if (record.fundingPaymentTxid != null) return false
        val address = record.funding.firstOrNull()?.address ?: return false
        return attempt != null && attempt.address == address && attempt.evidence == OnchainSendEvidence.Rejected
    }

    /**
     * What progress shows while the exit still needs fee funding, or null when it does not. [utxos] is what the funding
     * address holds, null when it could not be read. A payment from savings that has not confirmed, or new funding
     * covering the shortfall that has not, is waited for; only what is still missing is asked for, at least dust.
     */
    fun fundingState(record: SparkExitRecord, utxos: List<EsploraUtxo>?): SparkExitFundingState? {
        val needed = record.fundingShortfallSats ?: return null
        record.fundingPaymentTxid?.let { txid ->
            val isAdopted = record.funding.any { it.txid == txid }
            val isConfirmed = utxos?.any { it.txid == txid && it.status.confirmed } == true
            if (!isAdopted && !isConfirmed) return SparkExitFundingState.Paid(txid)
        }
        val fresh = utxos?.let { newFunding(it, record) }.orEmpty()
        val seen = fresh.fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }
        // The shortfall counts the coin that pays it; each coin more adds its own input fee.
        val inputFee = inputFeeSats(record.feeRateSatPerVbyte)
        val shortfall = needed.safe() + (inputFee.safe() * maxOf(fresh.size - 1, 0).toULong().safe()).safe()
        if (fresh.isNotEmpty() && seen >= shortfall) {
            // Covered: waiting for it to confirm, or for the next tick to take it once it has.
            val txid = (fresh.firstOrNull { !it.status.confirmed } ?: fresh.first()).txid
            return SparkExitFundingState.Seen(txid)
        }
        // A partial payment leaves another coin to send, with its own input fee. Nodes refuse an output under its dust
        // limit, so a smaller remainder is asked as the funding output's dust.
        val required = if (fresh.isEmpty()) shortfall else shortfall.safe() + inputFee.safe()
        return SparkExitFundingState.Ask(maxOf(required - seen, FUNDING_DUST_SATS), seen, required)
    }

    /**
     * The pre-start funding screen's reading of the funding address: [receivedSats] in [payments] UTXOs against the
     * quote's [fundingSats] at [feeRateSatPerVbyte]. Each payment beyond the first adds its input's fee to what the
     * exit needs, and a payment still to send adds one more, as the progress screen's ask does.
     */
    fun prestartFunding(
        fundingSats: ULong,
        payments: Int,
        receivedSats: ULong,
        feeRateSatPerVbyte: ULong,
    ): SparkExitPrestartFunding {
        val inputFee = inputFeeSats(feeRateSatPerVbyte)
        val covered = fundingSats.safe() + (inputFee.safe() * maxOf(payments - 1, 0).toULong().safe()).safe()
        val isCovered = fundingSats > 0uL && payments > 0 && receivedSats >= covered
        val partial = if (isCovered || receivedSats == 0uL) null else partialFunding(receivedSats, covered + inputFee)
        return SparkExitPrestartFunding(isCovered, partial)
    }

    /** What reached the funding address against [requiredSats], once some but not all of it did; else null. */
    fun partialFunding(receivedSats: ULong, requiredSats: ULong): SparkExitPartialFunding? {
        if (receivedSats == 0uL || receivedSats >= requiredSats) return null
        return SparkExitPartialFunding(
            receivedSats,
            requiredSats,
            maxOf(requiredSats - receivedSats, FUNDING_DUST_SATS)
        )
    }

    /**
     * The fewest confirmed UTXOs of new funding, the largest first, that cover [requiredSats] plus [inputFeeSats] for
     * each coin beyond the first; a coin worth no more than its input's fee is never taken. The SDK spends every input
     * it is given, so the rest stays for the leftover sweep.
     */
    fun fundingTopUp(utxos: List<EsploraUtxo>, requiredSats: ULong, inputFeeSats: ULong = 0uL): List<EsploraUtxo>? {
        val confirmed = utxos.filter { it.status.confirmed }.sortedByDescending { it.value }
        val taken = mutableListOf<EsploraUtxo>()
        var total = 0uL
        for (utxo in confirmed) {
            if (taken.isNotEmpty() && utxo.value <= inputFeeSats) return null
            taken += utxo
            total = total.safe() + utxo.value.safe()
            val needed = requiredSats.safe() + (inputFeeSats.safe() * (taken.size - 1).toULong().safe()).safe()
            if (total >= needed) return taken
        }
        return null
    }

    /** About how long until [height]. */
    fun timeUntil(height: UInt, tip: UInt, network: Network): Duration {
        val blockTime = if (network == Network.REGTEST) REGTEST_BLOCK_TIME else BLOCK_TIME
        return blockTime * BlockTimeHelpers.blocksRemaining(height, tip)
    }

    /** The dust limit of the funding address's P2WPKH output, the least a top-up can pay. */
    const val FUNDING_DUST_SATS = 294uL

    /** A signed P2WPKH input's size: each funding coin adds this much to the CPFP children it pays for. */
    private const val P2WPKH_INPUT_VBYTES = 68uL

    /** Blocks before a build that waits for a leaf that may still move tries again; gaps double from here. */
    private const val RETRY_BLOCKS = 6u

    /** The longest gap between those tries, about a day. */
    private const val RETRY_MAX_BLOCKS = 144u

    /** About a week: what is still stuck after it is let go, so the exit can finish. */
    const val RELEASE_BLOCKS = 1_008u

    /** Blocks a leaf the operators' watchtower stopped may stay so before it is let go. */
    const val STOPPED_BLOCKS = 6u

    /** About how many blocks after a step matures the operators may publish their own version of it. */
    private const val OPERATOR_WINDOW_BLOCKS = 50

    /**
     * How many blocks a matured refund leads an older state's refund of the same leaf by, the hard limit while the
     * operators are down.
     */
    private const val OLDER_STATE_LEAD_BLOCKS = 100

    /** Blocks after a step matures before Bitkit warns that it was not published: 38 of the 50 are left to publish. */
    private const val WARN_GRACE_BLOCKS = 12

    /**
     * About how long until a step can be published: zero while one already can (its parents confirmed, and ready or
     * its timelock behind [tip]) or when it is unknown, else until the next step matures.
     */
    fun untilNextMaturity(recovery: RecoverFundsResponse?, tip: UInt?, network: Network): Duration {
        if (recovery == null || tip == null) return Duration.ZERO
        val height: UInt = tip
        val confirmed = recovery.transactions.filter { it.status is ExitTransactionStatus.Confirmed }.map { it.txid }
        val isPublishable = recovery.transactions.any { tx ->
            val maturesAt = (tx.status as? ExitTransactionStatus.WaitingForTimelock)?.spendableAtHeight
            val isDue = tx.status == ExitTransactionStatus.Ready || tx.status == ExitTransactionStatus.Unverified ||
                (maturesAt != null && maturesAt <= height)
            isDue && tx.dependsOn.all { it in confirmed }
        }
        if (isPublishable) return Duration.ZERO
        val maturity = nextMaturityHeight(recovery, height) ?: return Duration.ZERO
        return timeUntil(maturity, height, network)
    }

    /**
     * How long a matured step is ours to publish: the smaller of the operators' window and an older state's lead, about
     * 8 hours on mainnet, 25 minutes on Spark's regtest.
     */
    fun operatorWindow(network: Network): Duration =
        (if (network == Network.REGTEST) REGTEST_BLOCK_TIME else BLOCK_TIME) *
            minOf(OPERATOR_WINDOW_BLOCKS, OLDER_STATE_LEAD_BLOCKS)

    /**
     * When Bitkit warns that the background run fell behind, and the deadline it names: [untilMaturity] is how long
     * until the next step matures, zero while steps already wait to be published. The deadline is the end of the
     * window; the warning comes 12 blocks after maturity, well before the operators or an older state may publish.
     */
    fun deadline(untilMaturity: Duration, network: Network): SparkExitDeadline {
        val grace = blockTime(network) * WARN_GRACE_BLOCKS
        return SparkExitDeadline(warnIn = untilMaturity + grace, deadlineIn = untilMaturity + operatorWindow(network))
    }

    private fun blockTime(network: Network) = if (network == Network.REGTEST) REGTEST_BLOCK_TIME else BLOCK_TIME

    /**
     * The funding key index of the next exit: past the one the last exit took ([kept]), and past the [stored] record's,
     * which covers exits started before the index was kept.
     */
    fun nextFundingIndex(kept: Int?, stored: SparkExitRecord?): UInt {
        val pastStored = stored?.funding?.maxOfOrNull { it.index + 1u } ?: 0u
        return maxOf(kept?.toUInt() ?: 0u, pastStored)
    }

    /** Savings can pay the fee funding when its spendable balance covers the funding and the payment's own fee. */
    fun canFundFromSavings(spendableSats: ULong, fundingSats: ULong, paymentFeeSats: ULong): Boolean =
        fundingSats > 0uL && spendableSats >= fundingSats.safe() + paymentFeeSats.safe()
}

/** The fee funding an exit still needs, as progress shows it. */
sealed interface SparkExitFundingState {
    /** Savings paid it in [txid], which has not confirmed yet. */
    data class Paid(val txid: String) : SparkExitFundingState

    /** A payment covering it, [txid] among them, was seen and waits to confirm. */
    data class Seen(val txid: String) : SparkExitFundingState

    /**
     * [sats] are still missing at the funding address, at least dust, after [receivedSats] of new funding seen there
     * against a shortfall of [requiredSats].
     */
    data class Ask(
        val sats: ULong,
        val receivedSats: ULong = 0uL,
        val requiredSats: ULong = sats,
    ) : SparkExitFundingState {
        val partial: SparkExitPartialFunding? get() = SparkExitMath.partialFunding(receivedSats, requiredSats)
    }
}

/** A funding payment short of the need: [receivedSats] of [requiredSats] arrived, [missingSats] (at least dust) due. */
data class SparkExitPartialFunding(val receivedSats: ULong, val requiredSats: ULong, val missingSats: ULong)

/**
 * A higher rate a running exit can move to, at the send flow's [speed], with [extraSats] its funding address must
 * still receive and [totalSats] what that costs from savings, the payment's own fee included.
 */
data class SparkExitFeeBumpOption(
    val speed: FeeRate,
    val rateSatPerVbyte: ULong,
    val extraSats: ULong,
    val totalSats: ULong,
    /** The rates [totalSats] was priced at, which the payment sends at, so it never costs more than shown. */
    val feeRates: FeeRates? = null,
) {
    companion object {
        /**
         * The rates to offer above [exitRate], each strictly above it so the rebuild replaces what is in the mempool:
         * Fast at the next block's rate, or one above the exit's when the network asks no more; Normal at the half-hour
         * rate, only between the two.
         */
        fun rates(exitRate: ULong, fees: SparkExitNetworkFees): List<Pair<FeeRate, ULong>> {
            val fast = maxOf(fees.fastest, exitRate + 1uL)
            val normal = fees.halfHour.takeIf { it > exitRate && it < fast }
            return listOfNotNull(FeeRate.FAST to fast, normal?.let { FeeRate.NORMAL to it })
        }
    }
}

/** Whether the funding address covers the quote before the exit starts, and the hint while it does not. */
data class SparkExitPrestartFunding(val isCovered: Boolean, val partial: SparkExitPartialFunding?)

/** When to warn that the exit fell behind, and when its next step's operator window closes, both from now. */
data class SparkExitDeadline(val warnIn: Duration, val deadlineIn: Duration)
