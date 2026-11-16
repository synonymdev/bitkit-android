package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.CooperativeRecoveryFailure
import breez_sdk_spark.CpfpFundingKind
import breez_sdk_spark.CpfpFundingStrategy
import breez_sdk_spark.CpfpInput
import breez_sdk_spark.CpfpSigner
import breez_sdk_spark.ExitChainState
import breez_sdk_spark.ExitLeafSelection
import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.ImportUnilateralExitStateResponse
import breez_sdk_spark.PrepareRecoverFundsRequest
import breez_sdk_spark.PrepareRecoverFundsResponse
import breez_sdk_spark.RecoverFundsLeaf
import breez_sdk_spark.RecoverFundsRequest
import breez_sdk_spark.RecoverFundsResponse
import breez_sdk_spark.RecoveryFunding
import breez_sdk_spark.RecoveryMethod
import breez_sdk_spark.RecoveryTransaction
import breez_sdk_spark.RecoveryTxKind
import breez_sdk_spark.SkippedLeaf
import breez_sdk_spark.SkippedLeafReason

/** The same fixtures as Bitkit iOS's `SparkExitTests.swift`, so both suites assert the same values. */
object SparkExitFixtures {
    const val PARENT_HEX = "020000000111111111111111111111111111111111111111111111111111111111111111110000000000" +
        "ffffffff01e803000000000000160014222222222222222222222222222222222222222200000000"

    /** The id Spark's regtest node reported for [PARENT_HEX]. */
    const val PARENT_TXID = "b91b720103788b9ae50dbff918bc375d185df91025b9f6e861efe208909b6adf"

    const val FUNDING_ADDRESS = "bcrt1qfunding"

    fun tx(
        txid: String,
        kind: RecoveryTxKind = RecoveryTxKind.NODE,
        cpfp: String? = null,
        dependsOn: List<String> = emptyList(),
        status: ExitTransactionStatus = ExitTransactionStatus.Ready,
        csv: UInt? = null,
    ) = RecoveryTransaction(
        kind = kind,
        nodeId = if (kind == RecoveryTxKind.SWEEP) null else "node-$txid",
        txid = txid,
        txHex = "02$txid",
        cpfpTxHex = cpfp,
        csvTimelockBlocks = csv,
        dependsOn = dependsOn,
        status = status,
    )

    fun recovery(
        transactions: List<RecoveryTransaction>,
        failed: List<CooperativeRecoveryFailure> = emptyList(),
    ) = RecoverFundsResponse(
        recoverableValueSats = 100_000uL,
        totalFeeSats = 9_000uL,
        cooperativeFeeSats = 0uL,
        cpfpFeeSats = 7_000uL,
        fanoutFeeSats = 500uL,
        refundFeeSats = 1_000uL,
        sweepFeeSats = 500uL,
        leaves = listOf(RecoverFundsLeaf("leaf-1", 100_000uL, RecoveryMethod.UNILATERAL)),
        failed = failed,
        transactions = transactions,
        fundingInputs = listOf(CpfpInput.P2wpkh("f0", 1u, 20_000uL, "02ab")),
        feeRateSatPerVbyte = 2uL,
        destination = "bcrt1qdestination",
    )

    fun prepared(
        funding: ULong? = 20_000uL,
        leaves: List<String> = listOf("leaf-1"),
        skipped: List<SkippedLeaf> = listOf(SkippedLeaf("dust", 300uL, SkippedLeafReason.FeeExceedsValue)),
        totalFeeSats: ULong = 9_000uL,
    ) = PrepareRecoverFundsResponse(
        leaves = leaves.map { RecoverFundsLeaf(it, 100_000uL, RecoveryMethod.UNILATERAL) },
        skipped = skipped,
        recoverableValueSats = 100_000uL,
        totalFeeSats = totalFeeSats,
        cooperativeFeeSats = 0uL,
        cpfpFeeSats = 7_000uL,
        fanoutFeeSats = 500uL,
        refundFeeSats = 1_000uL,
        sweepFeeSats = 500uL,
        funding = funding?.let { RecoveryFunding(it, emptyList(), CpfpFundingStrategy.PER_BRANCH) },
        feeRateSatPerVbyte = 2uL,
        destination = "bcrt1qdestination",
        exitChainState = ExitChainState(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
    )

    fun prepareRequest() = PrepareRecoverFundsRequest(
        feeRateSatPerVbyte = 2uL,
        fundingKind = CpfpFundingKind.P2wpkh,
        destination = "bcrt1qdestination",
        selection = ExitLeafSelection.All,
    )

    fun record(phase: SparkExitRecord.Phase = SparkExitRecord.Phase.AwaitingFunding(20_000uL)) = SparkExitRecord(
        network = "regtest",
        createdAt = 1_700_000_000_000L,
        phase = phase,
        feeRateSatPerVbyte = 2uL,
        destination = "bcrt1qdestination",
        leafIds = listOf("leaf-1"),
        funding = listOf(SparkExitRecord.Funding(index = 0u, address = FUNDING_ADDRESS, pubkey = "02ab")),
        transferId = "transfer-1",
        expectedSats = 111_000uL,
    )

    /**
     * A version 3 transaction spending output 0 of [parent] with [csv] blocks of relative timelock in its nSequence,
     * as a refund spends its node.
     */
    fun csvTxHex(parent: String, csv: Int): String {
        fun le32(value: Long) = (0 until 4).joinToString("") { "%02x".format((value shr (8 * it)) and 0xff) }
        val prevout = parent.chunked(2).reversed().joinToString("")
        return "03000000" + "01" + prevout + le32(0) + "00" + le32(csv.toLong()) +
            "01" + "e803000000000000" + "00" + le32(0)
    }

    /** A version 3 CPFP child spending output 0 of each of [spends]: its node's anchor and the change it chains on. */
    fun childTxHex(vararg spends: String): String {
        val inputs = spends.joinToString("") { parent ->
            parent.chunked(2).reversed().joinToString("") + "00000000" + "00" + "ffffffff"
        }
        return "03000000" + "%02x".format(spends.size) + inputs + "01" + "e803000000000000" + "00" + "00000000"
    }

    fun utxo(txid: String, vout: UInt = 0u, value: ULong, confirmed: Boolean = true) =
        EsploraUtxo(txid, vout, value, EsploraTxStatus(confirmed, if (confirmed) 100u else null))
}

class FakeSparkExitChain : SparkExitChain {
    var tip = 1_000u
    val utxosByAddress = mutableMapOf<String, List<EsploraUtxo>>()
    var utxosError: Throwable? = null
    var confirmedTxids = setOf<String>()

    /** Txids no host knows: in neither a block nor the mempool. */
    var unknownTxids = setOf<String>()
    val knownLookups = mutableListOf<String>()

    /** Block heights of confirmed transactions; 900 for any other. */
    val heights = mutableMapOf<String, UInt>()
    var outcome: SparkExitBroadcastOutcome = SparkExitBroadcastOutcome.Accepted
    val broadcasts = mutableListOf<RecoveryTransaction>()
    var onBroadcast: () -> Unit = {}

    override suspend fun tipHeight() = tip

    /** Addresses with a transaction though they hold nothing now. */
    var usedAddresses = setOf<String>()

    override suspend fun isAddressUsed(address: String) =
        address in usedAddresses || utxosByAddress[address].orEmpty().isNotEmpty()

    override suspend fun utxos(address: String): List<EsploraUtxo> {
        utxosError?.let { throw it }
        return utxosByAddress[address].orEmpty()
    }

    /** Txids whose status no host answers for. */
    var statusErrorTxids = setOf<String>()

    override suspend fun txStatus(txid: String): EsploraTxStatus {
        if (txid in statusErrorTxids) throw SparkExitChainError("No host answered for '$txid'")
        return EsploraTxStatus(txid in confirmedTxids, if (txid in confirmedTxids) heights[txid] ?: 900u else null)
    }

    override suspend fun isTxKnown(txid: String): Boolean {
        knownLookups += txid
        return txid !in unknownTxids
    }

    override suspend fun broadcast(tx: RecoveryTransaction): SparkExitBroadcastOutcome {
        onBroadcast()
        broadcasts += tx
        return outcome
    }

    override suspend fun broadcastHex(hex: String): SparkExitBroadcastOutcome {
        rawBroadcasts += hex
        return outcome
    }

    val rawBroadcasts = mutableListOf<String>()

    var nextBlockRate: ULong? = 5uL

    override suspend fun nextBlockFeeRate(): ULong = nextBlockRate ?: throw SparkExitChainError("No fee estimates")

    var fees = SparkExitNetworkFees(fastest = 5uL, halfHour = 3uL)

    override suspend fun networkFees(): SparkExitNetworkFees = fees

    var economyRate: ULong? = 1uL

    override suspend fun economyFeeRate(): ULong = economyRate ?: throw SparkExitChainError("No fee estimates")
}

class FakeSparkExitRecovering : SparkExitRecovering {
    var preparedResponse = SparkExitFixtures.prepared()
    val builds = ArrayDeque<RecoverFundsResponse>()
    val checks = ArrayDeque<CheckRecoverFundsResponse>()
    var recoverError: Throwable? = null
    val prepareRequests = mutableListOf<PrepareRecoverFundsRequest>()
    val recoverRequests = mutableListOf<RecoverFundsRequest>()
    val checkedRecoveries = mutableListOf<RecoverFundsResponse>()

    /** Errors the next recovers throw, in order, before [recoverError]. */
    val recoverErrors = ArrayDeque<Throwable>()

    /** Errors the next prepares throw, in order, before [preparedResponse] is returned. */
    val prepareErrors = ArrayDeque<Throwable>()

    /** Responses the next prepares return, in order, before [preparedResponse]. */
    val preparedResponses = ArrayDeque<PrepareRecoverFundsResponse>()

    override suspend fun prepare(request: PrepareRecoverFundsRequest): PrepareRecoverFundsResponse {
        prepareRequests += request
        prepareErrors.removeFirstOrNull()?.let { throw it }
        return preparedResponses.removeFirstOrNull() ?: preparedResponse
    }

    override suspend fun recover(request: RecoverFundsRequest, signer: CpfpSigner?): RecoverFundsResponse {
        recoverRequests += request
        recoverErrors.removeFirstOrNull()?.let { throw it }
        recoverError?.let { throw it }
        return builds.removeFirst()
    }

    override suspend fun check(recovery: RecoverFundsResponse): CheckRecoverFundsResponse {
        checkedRecoveries += recovery
        return checks.removeFirst()
    }

    var exitState = "exit-state-1"
    var exports = 0
    val imports = mutableListOf<String>()

    override suspend fun exportExitState(): String {
        exports++
        return exitState
    }

    override suspend fun importExitState(exitState: String): ImportUnilateralExitStateResponse {
        imports += exitState
        return ImportUnilateralExitStateResponse(0u, 0u, 0u, 17u)
    }
}

/** Finalizes every input of a PSBT with [witness], as the SDK's single-key signer does with its signature. */
class StubCpfpSigner(private val witness: String = P2WPKH_WITNESS) : CpfpSigner {
    companion object {
        /** A P2WPKH witness: two items, a 72-byte signature with its sighash flag and a 33-byte key. */
        val P2WPKH_WITNESS = "02" + "48" + "30".repeat(71) + "01" + "21" + "02".repeat(33)
    }

    override suspend fun signPsbt(psbtBytes: ByteArray): ByteArray {
        var offset = 5
        fun readCompactSize(): Int {
            val prefix = psbtBytes[offset++].toInt() and 0xff
            if (prefix < 0xfd) return prefix
            check(prefix == 0xfd)
            return (psbtBytes[offset++].toInt() and 0xff) or ((psbtBytes[offset++].toInt() and 0xff) shl 8)
        }
        var unsignedTx = ByteArray(0)
        fun copyMap(extra: ByteArray): ByteArray {
            val start = offset
            while (true) {
                val keyLength = readCompactSize()
                if (keyLength == 0) return psbtBytes.copyOfRange(start, offset - 1) + extra + byteArrayOf(0x00)
                val key = psbtBytes[offset]
                offset += keyLength
                val valueLength = readCompactSize()
                if (start == 5 && key == 0x00.toByte()) unsignedTx = psbtBytes.copyOfRange(offset, offset + valueLength)
                offset += valueLength
            }
        }
        val witnessBytes = witness.hexToByteArray()
        val finalWitness = byteArrayOf(0x01, 0x08, witnessBytes.size.toByte()) + witnessBytes
        var signed = psbtBytes.copyOfRange(0, 5) + copyMap(ByteArray(0))
        repeat(unsignedTx[4].toInt()) { signed += copyMap(finalWitness) }
        return signed + psbtBytes.copyOfRange(offset, psbtBytes.size)
    }
}
