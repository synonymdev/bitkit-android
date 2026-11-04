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
import to.bitkit.models.safe
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

        const val LOST_REASON = "Claimed by an older state"

        const val NO_LEAF_REASON = "No leaf can be exited"
    }

    suspend fun tick(start: SparkExitRecord): SparkExitRecord {
        var record = start
        when (record.phase) {
            SparkExitRecord.Phase.Done, is SparkExitRecord.Phase.Failed -> {
                if (!record.isLeftoverSweepPending) return record
                record = sweepLeftover(record)
                save(record)
                return record
            }
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
        // The tick that ends the exit also sends its leftover fee funding to savings.
        if (record.isLeftoverSweepPending) record = sweepLeftover(record)
        save(record)
        return record
    }

    /**
     * Adds every confirmed UTXO of new funding at the funding address once together they cover [requiredSats]; null
     * while they do not. Several small payments count: the SDK spends every funding input it is given.
     */
    private suspend fun adoptFunding(record: SparkExitRecord, requiredSats: ULong): SparkExitRecord? {
        val first = record.funding.firstOrNull() ?: return null
        val candidates = SparkExitMath.newFunding(chain.utxos(first.address), record)
        val utxos = SparkExitMath.fundingTopUp(candidates, requiredSats) ?: return null
        val adopted = utxos.map { first.copy(txid = it.txid, vout = it.vout, valueSats = it.value) }
        val funding = if (record.funding.size == 1 && first.txid == null) adopted else record.funding + adopted
        return record.copy(funding = funding)
    }

    private suspend fun build(start: SparkExitRecord, sdk: SparkExitRecovering): SparkExitRecord {
        val (record, prepared) = prepare(start, sdk)
        if (prepared == null) {
            val failed = record.copy(phase = SparkExitRecord.Phase.Failed(NO_LEAF_REASON), isRebuilding = false)
            return end(failed, record)
        }
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
            return end(ended, record)
        }
        val needsFunding = prepared.funding != null
        val built = runSuspendCatching {
            sdk.recover(
                RecoverFundsRequest(prepared, if (needsFunding) record.fundingInputs else emptyList()),
                if (needsFunding) makeSigner() else null,
            )
        }.getOrElse {
            // A leaf the SDK refuses to build is left behind, and the rest rebuilt on the next tick.
            SparkExitUnexitable.parse(it)?.takeIf { refusal -> refusal.leafId in record.exitLeafIds }?.let { refusal ->
                Logger.warn("Leaving behind Spark exit leaf '${refusal.leafId}': '${refusal.reason}'", context = TAG)
                return record.leaveBehind(refusal.leafId, refusal.reason).copy(isRebuilding = true, lastBuildAt = null)
            }
            if (it !is SdkException.InsufficientCpfpFunds) throw it
            // The SDK names the total its funding inputs must hold (CPFP cost plus change dust), not what is missing.
            // After a Redo the inputs are partly spent, so what the address still holds decides the shortfall.
            Logger.warn("Needs '${it.requiredSats}' sats of fee funding in total for the Spark exit", context = TAG)
            val held = record.funding.firstOrNull()?.address?.let { address ->
                runSuspendCatching { chain.utxos(address) }.getOrNull()?.let { SparkExitMath.heldSats(it, record) }
            }
            val needed = SparkExitMath.neededFunding(it.requiredSats, record.fundedSats, held)
            return record.copy(phase = SparkExitRecord.Phase.NeedsFunding(needed))
        }
        if (built.transactions.isEmpty()) {
            // Cooperative leaves the operators could not co-sign now are tried again on the next tick.
            if (built.failed.isNotEmpty()) return record.copy(failedLeafCount = built.failed.size)
            return end(record.copy(phase = SparkExitRecord.Phase.Failed("Nothing to recover")), record)
        }
        val lost = record.lostLeaves(built)
        if (lost.isNotEmpty()) return loseLeaves(record, lost)
        val adopted = record.adopt(built, nowMillis())
        // Saved before any of it is broadcast, so a crash never leaves broadcast transactions without their record.
        save(adopted)
        Logger.info(
            "Built Spark exit generation '${adopted.generation}' of '${built.transactions.size}' transactions",
            context = TAG,
        )
        return broadcastReady(adopted)
    }

    private fun prepareRequest(record: SparkExitRecord, selection: ExitLeafSelection) = PrepareRecoverFundsRequest(
        feeRateSatPerVbyte = record.feeRateSatPerVbyte,
        fundingKind = CpfpFundingKind.P2wpkh,
        destination = record.destination,
        selection = selection,
    )

    /**
     * Prepares the exit's leaves. A leaf the SDK refuses to exit, such as a fee-ladder leaf whose refund cannot pay its
     * own fee at this rate, fails the whole prepare: it is dropped and the rest prepared again. When the refused leaf
     * cannot be told, the leaves still recoverable are prepared instead, and the exit's leaves left out are dropped.
     * The prepared response is null once no leaves remain.
     */
    private suspend fun prepare(
        start: SparkExitRecord,
        sdk: SparkExitRecovering,
    ): Pair<SparkExitRecord, PrepareRecoverFundsResponse?> {
        var record = start
        while (true) {
            val ids = record.exitLeafIds
            if (ids.isEmpty()) return record to null
            val result = runSuspendCatching { sdk.prepare(prepareRequest(record, ExitLeafSelection.Specific(ids))) }
            result.getOrNull()?.let { return record to it }
            val error = requireNotNull(result.exceptionOrNull())
            val refusal = SparkExitUnexitable.parse(error) ?: throw error
            Logger.warn("Leaving behind Spark exit leaf '${refusal.leafId}': '${refusal.reason}'", context = TAG)
            if (refusal.leafId in ids) {
                record = record.leaveBehind(refusal.leafId, refusal.reason)
                continue
            }
            val prepared = sdk.prepare(prepareRequest(record, ExitLeafSelection.RecoverableOnly))
            val listed = prepared.leaves.map { it.leafId }.toSet()
            ids.filter { it !in listed }.forEach {
                record = record.leaveBehind(it, SparkExitUnexitable.NOT_RECOVERABLE)
            }
            return record to prepared
        }
    }

    /**
     * Leaves the rebuild unadopted and drops [lost] from the exit: an older state of this wallet claimed them, and
     * every rebuild would reproduce the same spent refund. The next tick rebuilds at once without them.
     */
    private suspend fun loseLeaves(record: SparkExitRecord, lost: List<SparkExitRecord.LostLeaf>): SparkExitRecord {
        Logger.warn("Dropping Spark exit leaves '${lost.map { it.leafId }}' claimed by an older state", context = TAG)
        val updated = record.copy(lostLeaves = record.lostLeaves + lost, isRebuilding = true, lastBuildAt = null)
        if (updated.exitLeafIds.isNotEmpty()) return updated
        return end(updated.copy(phase = SparkExitRecord.Phase.Failed(LOST_REASON), isRebuilding = false), updated)
    }

    private suspend fun check(record: SparkExitRecord, sdk: SparkExitRecovering): SparkExitRecord {
        val stored = record.recovery ?: return record.copy(phase = SparkExitRecord.Phase.AwaitingFunding(0uL))
        val checked = sdk.check(withConfirmations(stored))
        Logger.debug("Checked Spark exit generation '${record.generation}': '${checked.verdict}'", context = TAG)
        val updated = record.copy(recovery = checked.recovery)
        return when (checked.verdict) {
            RecoveryVerdict.Valid -> broadcastReady(updated)
            RecoveryVerdict.Done -> end(updated.copy(phase = SparkExitRecord.Phase.Done), updated)
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

    /**
     * Sends what the ended exit left at its funding address to [SparkExitRecord.destination] in one transaction, once
     * every UTXO there confirmed, at the exit's fee rate. Built and saved before it is sent, then sent again each tick
     * until a node accepts it; not after a rejection. Dust is not sent.
     */
    private suspend fun sweepLeftover(record: SparkExitRecord): SparkExitRecord {
        val built = record.leftoverSweep
        if (built?.txHex != null) {
            if (built.isAccepted) {
                val status = runSuspendCatching { chain.txStatus(requireNotNull(built.txid)) }.getOrNull()
                if (status?.confirmed == true) return record.copy(leftoverSweep = built.copy(isConfirmed = true))
            }
            // Sent again until it confirms; a node that already has it accepts it again.
            return sendSweep(record, built, built.txHex)
        }
        val address = record.funding.firstOrNull()?.address ?: return record
        val utxos = runSuspendCatching { chain.utxos(address) }.getOrNull() ?: return record
        if (utxos.isEmpty()) return record.copy(leftover = emptyList())
        if (utxos.any { !it.status.confirmed }) return record
        val inputs = utxos.map { SparkExitSweep.Input(it.txid, it.vout, it.value) }
        val total = utxos.fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }
        val plan = runSuspendCatching { SparkExitSweep.plan(inputs, record.destination, record.feeRateSatPerVbyte) }
            .getOrElse {
                Logger.error("Failed to plan the Spark exit leftover sweep", it, context = TAG)
                return record
            }
            ?: return record.copy(leftoverSweep = SparkExitRecord.LeftoverSweep(null, null, total))
        val sweep = runSuspendCatching {
            val signed = makeSigner().signPsbt(SparkExitSweep.psbt(plan, address))
            val hex = SparkExitSweep.signedTxHex(plan, signed)
            SparkExitRecord.LeftoverSweep(SignedTransactionId.fromHex(hex), hex, plan.amountSats)
        }.getOrElse {
            Logger.error("Failed to build the Spark exit leftover sweep", it, context = TAG)
            return record
        }
        val hex = requireNotNull(sweep.txHex)
        val saved = record.copy(leftoverSweep = sweep)
        save(saved)
        return sendSweep(saved, sweep, hex)
    }

    private suspend fun sendSweep(
        record: SparkExitRecord,
        sweep: SparkExitRecord.LeftoverSweep,
        hex: String,
    ): SparkExitRecord {
        val outcome = chain.broadcastHex(hex)
        Logger.info("Broadcast Spark exit leftover sweep '${sweep.txid}': '${outcome.summary}'", context = TAG)
        return when (outcome) {
            SparkExitBroadcastOutcome.Accepted -> record.copy(leftoverSweep = sweep.copy(isAccepted = true))
            is SparkExitBroadcastOutcome.Rejected -> record.copy(leftoverSweep = sweep.copy(rejection = outcome.reason))
            is SparkExitBroadcastOutcome.RetryLater,
            is SparkExitBroadcastOutcome.NotMature,
            is SparkExitBroadcastOutcome.InputsMissing,
            -> record
        }
    }

    private fun isRebuildDue(record: SparkExitRecord): Boolean {
        val last = record.lastBuildAt ?: return true
        return nowMillis() - last >= REBUILD_INTERVAL.inWholeMilliseconds
    }

    /** What is left at the funding address, null when it could not be read. */
    private suspend fun leftover(record: SparkExitRecord): List<SparkExitRecord.Leftover>? {
        val address = record.funding.firstOrNull()?.address ?: return emptyList()
        return runSuspendCatching { chain.utxos(address) }.getOrNull()
            ?.map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) }
    }

    /**
     * [ended] with the leftover at its funding address, or [unchanged] while the address cannot be read: an exit that
     * ended without its leftover read would never sweep it.
     */
    private suspend fun end(ended: SparkExitRecord, unchanged: SparkExitRecord): SparkExitRecord {
        val leftover = leftover(ended) ?: return unchanged
        return ended.copy(leftover = leftover)
    }

    private suspend fun broadcastReady(record: SparkExitRecord): SparkExitRecord {
        var current = record
        val recovery = current.recovery ?: return current
        // Unverified: the SDK could not read its parent from the chain; nodes refuse it harmlessly if it is not final.
        recovery.transactions
            .filter { it.status == ExitTransactionStatus.Ready || it.status == ExitTransactionStatus.Unverified }
            .filter { isDue(it.txid, current) }
            // A ready step's parents are confirmed, as the SDK saw them.
            .forEach {
                current = broadcastAndSave(it, current, parentsConfirmed = it.status == ExitTransactionStatus.Ready)
            }
        return current
    }

    /**
     * Without the SDK: each unconfirmed transaction whose parents are confirmed on the chain host and whose relative
     * timelock has matured, by the SDK's own rule. A stale one is refused by nodes as a conflict, and only the current
     * generation is stored. A step whose input someone else spent is not sent again; the next SDK check rebuilds.
     */
    private suspend fun broadcastWithoutSdk(record: SparkExitRecord): SparkExitRecord {
        val recovery = record.recovery ?: return record
        val tip = chain.tipHeight()
        val statuses = recovery.transactions
            .mapNotNull { tx -> (tx.status as? ExitTransactionStatus.Confirmed)?.let { tx.txid to it.blockHeight } }
            .associate { (txid, height) -> txid to EsploraTxStatus(true, height) }
            .toMutableMap<String, EsploraTxStatus?>()
        suspend fun status(txid: String): EsploraTxStatus? {
            if (txid !in statuses) statuses[txid] = runSuspendCatching { chain.txStatus(txid) }.getOrNull()
            return statuses[txid]
        }
        var current = record
        val transactions = recovery.transactions.map { tx ->
            if (tx.status is ExitTransactionStatus.Confirmed) return@map tx
            val own = status(tx.txid)
            if (own?.confirmed == true) return@map tx.copy(status = ExitTransactionStatus.Confirmed(own.blockHeight))
            if (!tx.dependsOn.all { status(it)?.confirmed == true }) return@map tx
            // A height the SDK already worked out still holds.
            val known = (tx.status as? ExitTransactionStatus.WaitingForTimelock)?.spendableAtHeight
            if (known != null && !SparkExitMath.isMature(known, tip)) return@map tx
            if (tx.csvTimelockBlocks != null) {
                val parents = SparkExitTxInputs.decode(tx.txHex)?.filter { it.relativeLockBlocks > 0u }
                    ?.map { it.prevTxid } ?: tx.dependsOn
                val heights = parents.associateWith { parentHeight(it, status(it)) }
                // A parent whose height is unknown leaves maturity unknown: wait.
                val spendable = SparkExitMath.spendableAtHeight(tx, heights) ?: return@map tx
                if (!SparkExitMath.isMature(spendable, tip)) {
                    return@map tx.copy(status = ExitTransactionStatus.WaitingForTimelock(spendable))
                }
            }
            if (isInputSpent(tx.txid, current)) return@map tx
            if (isDue(tx.txid, current)) current = broadcastAndSave(tx, current, parentsConfirmed = true)
            tx
        }
        return current.copy(recovery = recovery.copy(transactions = transactions))
    }

    /** A confirmed parent's height, read from the chain host when the stored confirmation carries none. */
    private suspend fun parentHeight(txid: String, known: EsploraTxStatus?): UInt? {
        if (known?.confirmed != true) return null
        return known.blockHeight ?: runSuspendCatching { chain.txStatus(txid) }.getOrNull()?.blockHeight
    }

    private fun isInputSpent(txid: String, record: SparkExitRecord): Boolean =
        record.broadcastLog.any { it.generation == record.generation && it.txid == txid && it.isInputSpent }

    private fun isDue(txid: String, record: SparkExitRecord): Boolean {
        val last = record.broadcastLog
            .firstOrNull { it.generation == record.generation && it.txid == txid }
            ?: return true
        return !last.isAccepted || nowMillis() - last.lastAttemptAt >= REBROADCAST_INTERVAL.inWholeMilliseconds
    }

    private suspend fun broadcastAndSave(
        tx: RecoveryTransaction,
        record: SparkExitRecord,
        parentsConfirmed: Boolean,
    ): SparkExitRecord = broadcast(tx, record, parentsConfirmed).also(save)

    /**
     * Sends [tx] and logs the outcome. With every parent confirmed, missing inputs mean someone else spent one first:
     * the operators, which a rebuild recovers, or an older state of this wallet, which rebuilds keep reproducing.
     */
    private suspend fun broadcast(
        tx: RecoveryTransaction,
        record: SparkExitRecord,
        parentsConfirmed: Boolean,
    ): SparkExitRecord {
        val outcome = chain.broadcast(tx)
        val isInputSpent = parentsConfirmed && outcome is SparkExitBroadcastOutcome.InputsMissing
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
            isInputSpent = existing.isInputSpent || isInputSpent,
        ) ?: SparkExitRecord.BroadcastAttempt(
            generation = record.generation,
            txid = tx.txid,
            attempts = 1,
            rejections = rejection,
            lastAttemptAt = nowMillis(),
            lastOutcome = outcome.summary,
            isAccepted = isAccepted,
            isInputSpent = isInputSpent,
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

/** A leaf the SDK refused to exit, read from its untyped `SparkError`: `Leaf {id} cannot be exited: {reason}`. */
object SparkExitUnexitable {
    const val NOT_RECOVERABLE = "Not recoverable at this fee rate"

    private val LEAF = Regex("""Leaf (\S+?) cannot be exited""")
    private const val MARKER = "cannot be exited"

    /** [leafId] is empty when the message names no leaf that can be read. */
    data class Refusal(val leafId: String, val reason: String)

    /** The refusal [error] reports, or null for any other error. */
    fun parse(error: Throwable): Refusal? {
        if (error !is SdkException.SparkException) return null
        val message = error.v1
        if (MARKER !in message) return null
        return Refusal(LEAF.find(message)?.groupValues?.get(1).orEmpty(), message)
    }
}

/** The inputs of a raw transaction, for relative timelocks: what each spends and its BIP 68 block lock. */
object SparkExitTxInputs {
    private const val DISABLE_FLAG = 1L shl 31
    private const val TYPE_FLAG = 1L shl 22
    private const val VALUE_MASK = 0xffffL

    data class Input(val prevTxid: String, val vout: UInt, val sequence: Long) {
        /** The relative lock in blocks, 0 when the input has none or a time-based one. */
        val relativeLockBlocks: UInt
            get() = if (sequence and DISABLE_FLAG != 0L || sequence and TYPE_FLAG != 0L) {
                0u
            } else {
                (sequence and VALUE_MASK).toUInt()
            }
    }

    /** The inputs, with no relative lock before version 2 (BIP 68), or null when [hex] is not a transaction. */
    fun decode(hex: String): List<Input>? = runCatching {
        val bytes = hex.hexToByteArray()
        var offset = 0
        fun take(length: Int): ByteArray {
            require(length >= 0 && offset + length <= bytes.size)
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
        fun le(length: Int): Long = take(
            length
        ).foldIndexed(0L) { i, sum, b -> sum or ((b.toLong() and 0xff) shl (8 * i)) }
        fun compactSize(): Int = when (val prefix = le(1).toInt()) {
            0xfd -> le(2).toInt()
            0xfe -> le(4).toInt()
            0xff -> error("Too many items")
            else -> prefix
        }
        val version = le(4)
        if (bytes[offset] == 0.toByte()) take(2)
        val count = compactSize()
        require(count > 0)
        List(count) {
            val prevTxid = take(32).reversedArray().toHexString()
            val vout = le(4).toUInt()
            take(compactSize())
            val sequence = le(4)
            Input(prevTxid, vout, if (version >= 2) sequence else DISABLE_FLAG)
        }
    }.getOrNull()
}
