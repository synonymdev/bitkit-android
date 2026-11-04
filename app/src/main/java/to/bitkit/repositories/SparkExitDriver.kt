package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.CpfpFundingKind
import breez_sdk_spark.CpfpSigner
import breez_sdk_spark.ExitLeafSelection
import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.PrepareRecoverFundsRequest
import breez_sdk_spark.PrepareRecoverFundsResponse
import breez_sdk_spark.RecoverFundsRequest
import breez_sdk_spark.RecoverFundsResponse
import breez_sdk_spark.RecoveryTransaction
import breez_sdk_spark.RecoveryVerdict
import breez_sdk_spark.SdkException
import breez_sdk_spark.SkippedLeafReason
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.Logger
import to.bitkit.utils.SignedTransactionId
import kotlin.time.Duration.Companion.minutes

/** The SDK calls an exit makes. The Breez SDK in the app, a fake in tests. */
interface SparkExitRecovering {
    suspend fun prepare(request: PrepareRecoverFundsRequest): PrepareRecoverFundsResponse
    suspend fun recover(request: RecoverFundsRequest, signer: CpfpSigner?): RecoverFundsResponse
    suspend fun check(recovery: RecoverFundsResponse): CheckRecoverFundsResponse
}

/**
 * Moves an exit one step forward each tick: builds it once its fee funding confirms, broadcasts what is ready,
 * rebuilds when the chain diverged, and finishes when everything confirmed. Without the SDK (a background start, or
 * operators down at connect) it still broadcasts the stored, already signed transactions whose parents confirmed.
 * Every built recovery is saved before anything of it is broadcast, and each broadcast as it happens, so a run killed
 * partway, as background runs can be, never sends the same transactions again before they are due.
 */
class SparkExitDriver(
    private val chain: SparkExitChain,
    /** Null while the Spark SDK is not connected. */
    private val recovering: SparkExitRecovering?,
    private val makeSigner: suspend () -> CpfpSigner,
    private val save: (SparkExitRecord) -> Unit,
    private val nowMillis: () -> Long,
) {
    companion object {
        private const val TAG = "SparkExitDriver"

        /** A ready transaction accepted in this generation is sent again after this long, in case a node dropped it. */
        val REBROADCAST_INTERVAL = 10.minutes

        /** After this many rejections of one transaction the progress screen shows the error. */
        const val REJECTIONS_TO_SURFACE = 3

        /** The least time between two builds, so a divergence that persists does not rebuild every tick. */
        val REBUILD_INTERVAL = 10.minutes
    }

    suspend fun tick(start: SparkExitRecord): SparkExitRecord {
        var record = start
        when (record.phase) {
            SparkExitRecord.Phase.Done, is SparkExitRecord.Phase.Failed -> return record
            is SparkExitRecord.Phase.AwaitingFunding, is SparkExitRecord.Phase.NeedsFunding -> {
                val sdk = recovering ?: return record
                val shortfall = record.fundingShortfallSats
                // Nothing to fund when every leaf is recovered cooperatively.
                if (shortfall != null) record = adoptFunding(record, shortfall) ?: return record
                record = build(record, sdk)
            }
            SparkExitRecord.Phase.Running if record.isRebuilding -> {
                // The diverged generation is never broadcast again; only a rebuild moves on from here.
                val sdk = recovering ?: return record
                if (!isRebuildDue(record)) return record
                record = build(record, sdk)
            }
            SparkExitRecord.Phase.Running -> {
                record = recovering?.let { check(record, it) } ?: broadcastWithoutSdk(record)
            }
        }
        record = record.copy(claimableAtHeight = record.recovery?.let(SparkExitMath::claimableAtHeight))
        save(record)
        return record
    }

    /**
     * Adds every confirmed UTXO at the funding address that is neither a funding input yet nor CPFP change the exit
     * chained there, once together they cover [requiredSats]; null while they do not. Several small payments count:
     * the SDK spends every funding input it is given.
     */
    private suspend fun adoptFunding(record: SparkExitRecord, requiredSats: ULong): SparkExitRecord? {
        val first = record.funding.firstOrNull() ?: return null
        val known = record.funding.mapNotNull { f -> f.txid?.let { "$it:${f.vout ?: 0u}" } }.toSet()
        val chained = record.recovery?.let(SparkExitTxids::cpfpChildTxids).orEmpty().toSet()
        val candidates = chain.utxos(first.address).filter { "${it.txid}:${it.vout}" !in known && it.txid !in chained }
        val utxos = SparkExitMath.fundingUtxos(candidates, requiredSats) ?: return null
        val adopted = utxos.map { first.copy(index = 0u, txid = it.txid, vout = it.vout, valueSats = it.value) }
        val funding = if (record.funding.size == 1 && first.txid == null) adopted else record.funding + adopted
        return record.copy(funding = funding)
    }

    private suspend fun build(record: SparkExitRecord, sdk: SparkExitRecovering): SparkExitRecord {
        val prepared = sdk.prepare(
            PrepareRecoverFundsRequest(
                feeRateSatPerVbyte = record.feeRateSatPerVbyte,
                fundingKind = CpfpFundingKind.P2wpkh,
                destination = record.destination,
                selection = ExitLeafSelection.Specific(record.leafIds),
            ),
        )
        if (prepared.leaves.isEmpty()) {
            // A leaf the chain could not be read for comes back on a later tick; finished leaves are not listed.
            if (prepared.skipped.any { it.reason == SkippedLeafReason.Unverified }) return record
            val ended = record.copy(
                skippedLeafCount = maxOf(record.skippedLeafCount, prepared.skipped.size),
                phase = if (record.recovery == null) {
                    SparkExitRecord.Phase.Failed("Nothing left to recover")
                } else {
                    SparkExitRecord.Phase.Done
                },
            )
            // Fee funding this exit no longer spends, such as a payment adopted just before, goes back to savings.
            return ended.copy(leftover = leftover(ended))
        }
        val needsFunding = prepared.funding != null
        val built = runSuspendCatching {
            sdk.recover(
                RecoverFundsRequest(prepared, if (needsFunding) record.fundingInputs else emptyList()),
                if (needsFunding) makeSigner() else null,
            )
        }.getOrElse {
            if (it !is SdkException.InsufficientCpfpFunds) throw it
            // The SDK names the total its funding inputs must cover (CPFP cost plus change dust), not what is
            // missing, so the shortfall is that total less what was already funded.
            Logger.warn("Needs '${it.requiredSats}' sats of fee funding in total for the Spark exit", context = TAG)
            return record.copy(phase = SparkExitRecord.Phase.NeedsFunding(maxOf(it.requiredSats, record.fundedSats)))
        }
        if (built.transactions.isEmpty()) {
            // Cooperative leaves the operators could not co-sign now are tried again on the next tick.
            if (built.failed.isNotEmpty()) return record.copy(failedLeafCount = built.failed.size)
            val failed = record.copy(phase = SparkExitRecord.Phase.Failed("Nothing to recover"))
            return failed.copy(leftover = leftover(failed))
        }
        val adopted = record.adopt(built, nowMillis())
        // Saved before any of it is broadcast, so a crash never leaves broadcast transactions without their record.
        save(adopted)
        Logger.info(
            "Built Spark exit generation '${adopted.generation}' of '${built.transactions.size}' transactions",
            context = TAG,
        )
        return broadcastReady(adopted)
    }

    private suspend fun check(record: SparkExitRecord, sdk: SparkExitRecovering): SparkExitRecord {
        val stored = record.recovery ?: return record.copy(phase = SparkExitRecord.Phase.AwaitingFunding(0uL))
        val checked = sdk.check(withConfirmations(stored))
        Logger.debug("Checked Spark exit generation '${record.generation}': '${checked.verdict}'", context = TAG)
        val updated = record.copy(recovery = checked.recovery)
        return when (checked.verdict) {
            RecoveryVerdict.Valid -> broadcastReady(updated)
            RecoveryVerdict.Done -> updated.copy(phase = SparkExitRecord.Phase.Done, leftover = leftover(updated))
            is RecoveryVerdict.Redo -> {
                Logger.warn(
                    "Rebuilding Spark exit diverged on-chain as generation '${record.generation + 1}'",
                    context = TAG,
                )
                val rebuilding = updated.copy(isRebuilding = true)
                save(rebuilding)
                if (!isRebuildDue(rebuilding)) return rebuilding
                build(rebuilding, sdk)
            }
        }
    }

    /**
     * Marks the next steps the chain host shows in a block as confirmed. The SDK asks the chain about a step only once
     * the caller saw it in a block; otherwise it looks for who spent its input, which an Electrum-backed mempool,
     * such as Spark's regtest, does not report.
     */
    private suspend fun withConfirmations(recovery: RecoverFundsResponse): RecoverFundsResponse {
        val confirmed = recovery.transactions
            .filter { it.status is ExitTransactionStatus.Confirmed }
            .map { it.txid }
            .toMutableSet()
        val transactions = recovery.transactions.map { tx ->
            if (tx.txid in confirmed || !tx.dependsOn.all { it in confirmed }) return@map tx
            val status = runSuspendCatching { chain.txStatus(tx.txid) }.getOrNull()
            if (status?.confirmed != true) return@map tx
            confirmed += tx.txid
            tx.copy(status = ExitTransactionStatus.Confirmed(status.blockHeight))
        }
        return recovery.copy(transactions = transactions)
    }

    private fun isRebuildDue(record: SparkExitRecord): Boolean {
        val last = record.lastBuildAt ?: return true
        return nowMillis() - last >= REBUILD_INTERVAL.inWholeMilliseconds
    }

    private suspend fun leftover(record: SparkExitRecord): List<SparkExitRecord.Leftover> {
        val address = record.funding.firstOrNull()?.address ?: return emptyList()
        return runSuspendCatching { chain.utxos(address) }.getOrDefault(emptyList())
            .map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) }
    }

    private suspend fun broadcastReady(record: SparkExitRecord): SparkExitRecord {
        var current = record
        val recovery = current.recovery ?: return current
        // Unverified: the SDK could not read its parent from the chain; nodes refuse it harmlessly if it is not final.
        recovery.transactions
            .filter { it.status == ExitTransactionStatus.Ready || it.status == ExitTransactionStatus.Unverified }
            .filter { isDue(it.txid, current) }
            .forEach { current = broadcastAndSave(it, current) }
        return current
    }

    /**
     * Without the SDK: each unconfirmed transaction whose parents are confirmed on the chain host and whose timelock
     * has matured. A stale one is refused by nodes as a conflict, and only the current generation is stored.
     */
    private suspend fun broadcastWithoutSdk(record: SparkExitRecord): SparkExitRecord {
        val recovery = record.recovery ?: return record
        val tip = chain.tipHeight()
        val confirmed = mutableMapOf<String, Boolean>()
        suspend fun isConfirmed(txid: String): Boolean = confirmed.getOrPut(txid) {
            runSuspendCatching { chain.txStatus(txid).confirmed }.getOrDefault(false)
        }
        var current = record
        val transactions = recovery.transactions.map { tx ->
            if (tx.status is ExitTransactionStatus.Confirmed) return@map tx
            val status = runSuspendCatching { chain.txStatus(tx.txid) }.getOrNull()
            if (status?.confirmed == true) {
                confirmed[tx.txid] = true
                return@map tx.copy(status = ExitTransactionStatus.Confirmed(status.blockHeight))
            }
            val height = (tx.status as? ExitTransactionStatus.WaitingForTimelock)?.spendableAtHeight
            if (height != null && height > tip) return@map tx
            if (tx.dependsOn.all { isConfirmed(it) } && isDue(tx.txid, current)) current = broadcastAndSave(tx, current)
            tx
        }
        return current.copy(recovery = recovery.copy(transactions = transactions))
    }

    private fun isDue(txid: String, record: SparkExitRecord): Boolean {
        val last = record.broadcastLog
            .firstOrNull { it.generation == record.generation && it.txid == txid }
            ?: return true
        return !last.isAccepted || nowMillis() - last.lastAttemptAt >= REBROADCAST_INTERVAL.inWholeMilliseconds
    }

    private suspend fun broadcastAndSave(tx: RecoveryTransaction, record: SparkExitRecord): SparkExitRecord =
        broadcast(tx, record).also(save)

    private suspend fun broadcast(tx: RecoveryTransaction, record: SparkExitRecord): SparkExitRecord {
        val outcome = chain.broadcast(tx)
        Logger.info("Broadcast Spark exit '${tx.kind}' '${tx.txid}': '${outcome.summary}'", context = TAG)
        val isAccepted = outcome == SparkExitBroadcastOutcome.Accepted
        val rejection = if (outcome is SparkExitBroadcastOutcome.Rejected) 1 else 0
        val existing = record.broadcastLog.firstOrNull { it.generation == record.generation && it.txid == tx.txid }
        val entry = existing?.copy(
            attempts = existing.attempts + 1,
            rejections = existing.rejections + rejection,
            lastAttemptAt = nowMillis(),
            lastOutcome = outcome.summary,
            isAccepted = isAccepted,
        ) ?: SparkExitRecord.BroadcastAttempt(
            generation = record.generation,
            txid = tx.txid,
            attempts = 1,
            rejections = rejection,
            lastAttemptAt = nowMillis(),
            lastOutcome = outcome.summary,
            isAccepted = isAccepted,
        )
        val log = if (existing == null) {
            record.broadcastLog + entry
        } else {
            record.broadcastLog.map { if (it === existing) entry else it }
        }
        return record.copy(broadcastLog = log)
    }
}

/** The rejection to show on the progress screen once a transaction was refused several times. */
val SparkExitRecord.surfacedRejection: String?
    get() = broadcastLog.firstOrNull {
        it.generation == generation && it.rejections >= SparkExitDriver.REJECTIONS_TO_SURFACE && !it.isAccepted
    }?.lastOutcome

/** Transaction ids of the CPFP children, which the SDK returns as hex only. */
object SparkExitTxids {
    fun cpfpChildTxids(recovery: RecoverFundsResponse): List<String> =
        recovery.transactions.mapNotNull { tx ->
            tx.cpfpTxHex?.let { runCatching { SignedTransactionId.fromHex(it) }.getOrNull() }
        }
}
