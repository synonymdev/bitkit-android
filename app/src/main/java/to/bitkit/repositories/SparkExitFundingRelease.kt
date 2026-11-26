package to.bitkit.repositories

import to.bitkit.env.Env
import java.io.File

/**
 * A savings send to a Spark exit's funding address, told apart by that address alone. Once the exit no longer needs
 * it, the send can be released like a Shop payment whose request ended, instead of blocking every new send.
 */
object SparkExitFundingRelease {
    /** The exit state the checks read: the wallet's record and its earlier exits' addresses. */
    data class Exit(val record: SparkExitRecord?, val earlier: List<SparkExitEarlierAddress>)

    private fun OnchainSendAttempt.hasCandidate(txid: String?): Boolean {
        if (txid == null) return false
        return this.txid.equals(txid, ignoreCase = true) || candidateTxids.any { it.equals(txid, ignoreCase = true) }
    }

    /** Whether [attempt] pays the funding address of the exit in [exit]'s record or of an earlier exit. */
    fun isExitFunding(attempt: OnchainSendAttempt, exit: Exit): Boolean =
        attempt.mayFundSparkExit && (
            exit.record?.funding?.firstOrNull()?.address == attempt.address ||
                exit.earlier.any { it.address == attempt.address }
            )

    /**
     * Whether the exit no longer needs [attempt]: it was cancelled or ended, or it holds all the funding it asked for
     * and none of it is [attempt]'s. While the exit still waits for one of [attempt]'s transactions, or asks for
     * funding at its address, it needs it; one it adopted means the payment landed, so it is never released.
     */
    fun exitNoLongerNeeds(attempt: OnchainSendAttempt, exit: Exit): Boolean {
        if (!attempt.mayFundSparkExit) return false
        val record = exit.record?.takeIf { it.funding.firstOrNull()?.address == attempt.address }
            ?: return exit.earlier.any { it.address == attempt.address }
        val landed = record.funding.mapNotNull { it.txid } + record.leftover.map { it.txid } +
            listOfNotNull(record.fundingPaymentTxid)
        if (landed.any { attempt.hasCandidate(it) }) return false
        return !record.isActive || record.fundingShortfallSats == null
    }

    /** Whether the exit adopted one of [attempt]'s transactions as funding: the payment landed. */
    fun isAdopted(attempt: OnchainSendAttempt, exit: Exit): Boolean =
        exit.record?.funding.orEmpty().any { attempt.hasCandidate(it.txid) }

    /**
     * Whether savings may pay [attempt] to the exit again now, over what its funding address holds ([utxos], null
     * when it could not be read): only while the exit still asks for funding there and nothing reached it, or while it
     * waits for this very payment. A send to no exit's address is not this check's to refuse.
     */
    fun mayPayAgain(attempt: OnchainSendAttempt, exit: Exit, utxos: List<EsploraUtxo>?): Boolean {
        if (!isExitFunding(attempt, exit)) return true
        val record = exit.record?.takeIf { it.isActive && it.funding.firstOrNull()?.address == attempt.address }
        if (record == null || utxos == null) return false
        return when (val state = SparkExitMath.fundingState(record, utxos)) {
            is SparkExitFundingState.Ask -> state.receivedSats == 0uL
            SparkExitFundingState.Paid -> attempt.hasCandidate(record.fundingPaymentTxid)
            else -> false
        }
    }

    /** The stored exit of [attempt]'s wallet; null when it cannot be read, so nothing is released. */
    fun storedExit(attempt: OnchainSendAttempt): Exit? = runCatching {
        Exit(
            record = SparkExitStore(File(Env.sparkExitRecordPath(attempt.walletIndex))).load(),
            earlier = SparkExitEarlierAddressStore(File(Env.sparkExitEarlierAddressesPath(attempt.walletIndex))).load(),
        )
    }.getOrNull()
}
