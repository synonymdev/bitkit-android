package to.bitkit.repositories

import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.PrepareRecoverFundsResponse
import breez_sdk_spark.RecoverFundsResponse
import breez_sdk_spark.RecoveryTransaction
import breez_sdk_spark.RecoveryTxKind
import org.lightningdevkit.ldknode.Network
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
 * ([sparkBalanceSats]), so value the quote cannot exit is named rather than silently left out.
 */
data class SparkExitQuote(val prepared: PrepareRecoverFundsResponse, val sparkBalanceSats: ULong? = null) {
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

    /**
     * What the quote cannot exit now: the leaves it skips, and Spark's balance beyond every leaf it lists, such as a
     * counter-swap still unclaimed. Null when nothing.
     */
    val unexitableSats: ULong? get() = SparkExitMath.unexitableSats(sparkBalanceSats, this)

    /** Spark's balance beyond every leaf the quote lists, skipped ones included. */
    val beyondLeavesSats: ULong?
        get() {
            val leaves = prepared.leaves.fold(0uL) { sum, leaf -> sum.safe() + leaf.valueSats.safe() }
            return SparkExitMath.outsideExitSats(leaves.safe() + leftBehindSats.safe(), sparkBalanceSats)
        }

    /** Breez's rule: only exit when the value is more than its fees. */
    val isWorthExiting: Boolean get() = prepared.leaves.isNotEmpty() && exitSats > networkFeeSats
}

object SparkExitMath {
    /** The block time of Spark's regtest, which mines one every ~30 seconds. */
    private val REGTEST_BLOCK_TIME = 30.seconds

    private val BLOCK_TIME = 10.minutes

    /** The countdown target: the latest block at which a timelocked step of the exit can confirm. */
    fun claimableAtHeight(recovery: RecoverFundsResponse): UInt? = recovery.transactions
        .mapNotNull { (it.status as? ExitTransactionStatus.WaitingForTimelock)?.spendableAtHeight }
        .maxOrNull()

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
     * UTXOs at the funding address that are new funding: neither an adopted input nor change a CPFP child of this or
     * an earlier generation chained there.
     */
    fun newFunding(utxos: List<EsploraUtxo>, record: SparkExitRecord): List<EsploraUtxo> {
        val adopted = record.funding.mapNotNull { f -> f.txid?.let { "$it:${f.vout ?: 0u}" } }.toSet()
        val chained = (record.recovery?.let(SparkExitTxids::cpfpChildTxids).orEmpty() + record.earlierCpfpTxids).toSet()
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
     * reading of the address, `max(requiredSats, fundedSats)`, right for a first build where nothing is spent yet.
     */
    fun neededFunding(requiredSats: ULong, fundedSats: ULong, held: ULong?): ULong {
        if (held == null) return maxOf(requiredSats, fundedSats)
        val missing = if (requiredSats > held) requiredSats - held else 0uL
        return fundedSats.safe() + maxOf(missing, 1uL).safe()
    }

    /** The skipped leaves of [quote] plus Spark's [balanceSats] beyond every leaf it lists, or null when nothing. */
    fun unexitableSats(balanceSats: ULong?, quote: SparkExitQuote): ULong? {
        val beyond = quote.copy(sparkBalanceSats = balanceSats).beyondLeavesSats ?: 0uL
        return (quote.leftBehindSats.safe() + beyond.safe()).takeIf { it > 0uL }
    }

    /**
     * What Spark's [balanceSats] holds beyond [record]'s leaves, in any phase: what a stopped exit leaves on Spark,
     * such as value it could not exit. Lost leaves stay in the leaf total, so they are never claimed to stay.
     */
    fun staysOnSparkSats(balanceSats: ULong?, record: SparkExitRecord?): ULong? =
        record?.let { outsideExitSats(it.leafTotalSats, balanceSats) }

    /** Spark's balance beyond the exit's leaves while it still counts them, or null. */
    fun outsideExitSats(leafTotalSats: ULong?, balanceSats: ULong?): ULong? {
        if (leafTotalSats == null || balanceSats == null || balanceSats <= leafTotalSats) return null
        return balanceSats - leafTotalSats
    }

    /**
     * What progress shows while the exit still needs fee funding, or null when it does not. [utxos] is what the funding
     * address holds, null when it could not be read. A payment from savings that has not confirmed, or new funding
     * covering the shortfall that has not, is waited for; only what is still missing is asked for, at least dust.
     */
    fun fundingState(record: SparkExitRecord, utxos: List<EsploraUtxo>?): SparkExitFundingState? {
        val shortfall = record.fundingShortfallSats ?: return null
        record.fundingPaymentTxid?.let { txid ->
            val isAdopted = record.funding.any { it.txid == txid }
            val isConfirmed = utxos?.any { it.txid == txid && it.status.confirmed } == true
            if (!isAdopted && !isConfirmed) return SparkExitFundingState.Paid(txid)
        }
        val fresh = utxos?.let { newFunding(it, record) }.orEmpty()
        val seen = fresh.fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }
        if (fresh.isNotEmpty() && seen >= shortfall) {
            // Covered: waiting for it to confirm, or for the next tick to take it once it has.
            val txid = (fresh.firstOrNull { !it.status.confirmed } ?: fresh.first()).txid
            return SparkExitFundingState.Seen(txid)
        }
        // Nodes refuse an output under its dust limit, so a smaller remainder is asked as the funding output's dust.
        return SparkExitFundingState.Ask(maxOf(shortfall - seen, FUNDING_DUST_SATS), seen, shortfall)
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

    /** Every confirmed UTXO of new funding, the largest first, once together they cover [requiredSats]. */
    fun fundingTopUp(utxos: List<EsploraUtxo>, requiredSats: ULong): List<EsploraUtxo>? {
        val confirmed = utxos.filter { it.status.confirmed }.sortedByDescending { it.value }
        if (confirmed.isEmpty()) return null
        val total = confirmed.fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }
        return confirmed.takeIf { total >= requiredSats }
    }

    /** About how long until [height]. */
    fun timeUntil(height: UInt, tip: UInt, network: Network): Duration {
        val blockTime = if (network == Network.REGTEST) REGTEST_BLOCK_TIME else BLOCK_TIME
        return blockTime * BlockTimeHelpers.blocksRemaining(height, tip)
    }

    /** The dust limit of the funding address's P2WPKH output, the least a top-up can pay. */
    const val FUNDING_DUST_SATS = 294uL

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

/** When to warn that the exit fell behind, and when its next step's operator window closes, both from now. */
data class SparkExitDeadline(val warnIn: Duration, val deadlineIn: Duration)
