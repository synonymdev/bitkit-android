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

    private fun base58Decode(text: String): ByteArray? {
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

/** What an exit costs and returns, from a `prepareRecoverFunds` quote. */
data class SparkExitQuote(val prepared: PrepareRecoverFundsResponse) {
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

    /** The steps already in a block, for progress. */
    fun confirmedCount(recovery: RecoverFundsResponse): Int =
        recovery.transactions.count { it.status is ExitTransactionStatus.Confirmed }

    /** Every confirmed UTXO at the funding address, the largest first, once together they cover [requiredSats]. */
    fun fundingUtxos(utxos: List<EsploraUtxo>, requiredSats: ULong): List<EsploraUtxo>? {
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

    /** About how many blocks after a step matures the operators may publish their own version of it. */
    private const val OPERATOR_WINDOW_BLOCKS = 50

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

    /** How long the operators leave a matured step to us: about 8 hours on mainnet, 25 minutes on Spark's regtest. */
    fun operatorWindow(network: Network): Duration =
        (if (network == Network.REGTEST) REGTEST_BLOCK_TIME else BLOCK_TIME) * OPERATOR_WINDOW_BLOCKS

    /**
     * When Bitkit warns that the background run fell behind, and the deadline it names: [untilMaturity] is how long
     * until the next step matures, zero while steps already wait to be published. The deadline is the end of the
     * operators' window; the warning comes halfway through it, and never before two background runs could have
     * happened, so a run that is merely late does not alarm the user.
     */
    fun deadline(untilMaturity: Duration, network: Network, period: Duration): SparkExitDeadline {
        val window = operatorWindow(network)
        return SparkExitDeadline(
            warnIn = maxOf(untilMaturity + window / 2, period * 2),
            deadlineIn = untilMaturity + window,
        )
    }

    /** Savings can pay the fee funding when its spendable balance covers the funding and the payment's own fee. */
    fun canFundFromSavings(spendableSats: ULong, fundingSats: ULong, paymentFeeSats: ULong): Boolean =
        fundingSats > 0uL && spendableSats >= fundingSats.safe() + paymentFeeSats.safe()
}

/** When to warn that the exit fell behind, and when its next step's operator window closes, both from now. */
data class SparkExitDeadline(val warnIn: Duration, val deadlineIn: Duration)
