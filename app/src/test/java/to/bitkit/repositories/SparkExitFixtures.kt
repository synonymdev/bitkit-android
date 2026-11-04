package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.CooperativeRecoveryFailure
import breez_sdk_spark.CpfpInput
import breez_sdk_spark.CpfpSigner
import breez_sdk_spark.ExitChainState
import breez_sdk_spark.ExitTransactionStatus
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
        funding = funding?.let { RecoveryFunding(it, emptyList()) },
        feeRateSatPerVbyte = 2uL,
        destination = "bcrt1qdestination",
        exitChainState = ExitChainState(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
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

    fun utxo(txid: String, vout: UInt = 0u, value: ULong, confirmed: Boolean = true) =
        EsploraUtxo(txid, vout, value, EsploraTxStatus(confirmed, if (confirmed) 100u else null))
}

class FakeSparkExitChain : SparkExitChain {
    var tip = 1_000u
    val utxosByAddress = mutableMapOf<String, List<EsploraUtxo>>()
    var confirmedTxids = setOf<String>()
    var outcome: SparkExitBroadcastOutcome = SparkExitBroadcastOutcome.Accepted
    val broadcasts = mutableListOf<RecoveryTransaction>()
    var onBroadcast: () -> Unit = {}

    override suspend fun tipHeight() = tip

    override suspend fun utxos(address: String) = utxosByAddress[address].orEmpty()

    override suspend fun txStatus(txid: String) =
        EsploraTxStatus(txid in confirmedTxids, if (txid in confirmedTxids) 900u else null)

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
}

class FakeSparkExitRecovering : SparkExitRecovering {
    var preparedResponse = SparkExitFixtures.prepared()
    val builds = ArrayDeque<RecoverFundsResponse>()
    val checks = ArrayDeque<CheckRecoverFundsResponse>()
    var recoverError: Throwable? = null
    val prepareRequests = mutableListOf<PrepareRecoverFundsRequest>()
    val recoverRequests = mutableListOf<RecoverFundsRequest>()
    val checkedRecoveries = mutableListOf<RecoverFundsResponse>()

    override suspend fun prepare(request: PrepareRecoverFundsRequest): PrepareRecoverFundsResponse {
        prepareRequests += request
        return preparedResponse
    }

    override suspend fun recover(request: RecoverFundsRequest, signer: CpfpSigner?): RecoverFundsResponse {
        recoverRequests += request
        recoverError?.let { throw it }
        return builds.removeFirst()
    }

    override suspend fun check(recovery: RecoverFundsResponse): CheckRecoverFundsResponse {
        checkedRecoveries += recovery
        return checks.removeFirst()
    }
}

class StubCpfpSigner : CpfpSigner {
    override suspend fun signPsbt(psbtBytes: ByteArray) = psbtBytes
}
