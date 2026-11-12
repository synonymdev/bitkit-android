package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.CpfpFundingKind
import breez_sdk_spark.CpfpSigner
import breez_sdk_spark.ExitLeafSelection
import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.ImportUnilateralExitStateResponse
import breez_sdk_spark.PrepareRecoverFundsRequest
import breez_sdk_spark.PrepareRecoverFundsResponse
import breez_sdk_spark.RecoverFundsRequest
import breez_sdk_spark.RecoverFundsResponse
import breez_sdk_spark.RecoveryTransaction
import breez_sdk_spark.RecoveryTxKind
import breez_sdk_spark.RecoveryVerdict
import breez_sdk_spark.SdkException
import breez_sdk_spark.SkippedLeaf
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

    /** The SDK's exit data, as `exportUnilateralExitState` gives it. */
    suspend fun exportExitState(): String

    /** Merges exit data from [exportExitState] back in; an older one never costs the wallet a leaf. */
    suspend fun importExitState(exitState: String): ImportUnilateralExitStateResponse
}

/**
 * Prepares every leaf the operators list less [lost], the leaves an older state of this wallet claimed on-chain, which
 * the operators keep listing. A quote that lists one is prepared again for the rest, so its amount, fees and funding
 * leave it out, dropping a leaf the SDK refuses to exit; nothing is left to exit when every listed leaf is lost. The
 * quote carries the values of the lost leaves the operators still list.
 */
suspend fun SparkExitRecovering.prepareAll(
    request: PrepareRecoverFundsRequest,
    lost: Set<String>,
): SparkExitQuote {
    val all = prepare(request.copy(selection = ExitLeafSelection.All))
    val lostLeafSats = all.leaves.filter { it.leafId in lost }.map { it.valueSats } +
        all.skipped.filter { it.leafId in lost }.map { it.valueSats }
    val skipped = all.skipped.filter { it.leafId !in lost }
    if (all.leaves.none { it.leafId in lost }) return SparkExitQuote(all.copy(skipped = skipped), null, lostLeafSats)
    var ids = all.leaves.map { it.leafId }.filter { it !in lost }
    while (ids.isNotEmpty()) {
        val result = runSuspendCatching { prepare(request.copy(selection = ExitLeafSelection.Specific(ids))) }
        result.getOrNull()?.let { prepared ->
            val withSkipped = prepared.copy(skipped = (prepared.skipped + skipped).distinctBy { it.leafId })
            return SparkExitQuote(withSkipped, null, lostLeafSats)
        }
        val error = requireNotNull(result.exceptionOrNull())
        val refusal = SparkExitUnexitable.parse(error)?.takeIf { it.leafId in ids } ?: throw error
        ids = ids - refusal.leafId
    }
    val nothing = all.copy(
        leaves = emptyList(),
        skipped = skipped,
        recoverableValueSats = 0uL,
        totalFeeSats = 0uL,
        cooperativeFeeSats = 0uL,
        cpfpFeeSats = 0uL,
        fanoutFeeSats = 0uL,
        refundFeeSats = 0uL,
        sweepFeeSats = 0uL,
        funding = null,
    )
    return SparkExitQuote(nothing, null, lostLeafSats)
}

/**
 * Moves an exit one step forward each tick: builds it once its fee funding confirms, broadcasts what is ready,
 * rebuilds when the chain diverged or its fee chain lost a race, and finishes when everything confirmed. Without the
 * SDK (a background start, or operators down at connect) it still broadcasts the stored, already signed transactions
 * whose parents confirmed.
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
    /** The chain tip this tick, null when it could not be read. */
    private val tip: UInt? = null,
    /**
     * The fast rate of Bitkit's own fee source, null when it cannot be read: with the exit's own rate, the most a
     * leftover sweep pays, whatever the chain host asks.
     */
    private val trustedFeeRate: suspend () -> ULong? = { null },
) {
    companion object {
        private const val TAG = "SparkExitDriver"

        /** A ready transaction accepted in this generation is sent again after this long, in case a node dropped it. */
        val REBROADCAST_INTERVAL = 10.minutes

        /** After this many rejections of one transaction the exit rebuilds over it, as over a broken chain. */
        const val REJECTIONS_TO_SURFACE = 3

        /** After this many unpaid sends in a row of a step whose parents confirmed, the exit rebuilds over it. */
        const val FEE_CHAIN_FAILURES_TO_REBUILD = 3

        /** Rebuilds over a broken chain with no step confirming in between, before the exit says it is not moving. */
        const val STALL_REBUILDS_TO_SURFACE = 3

        /** Blocks an unconfirmed leftover sweep waits before it is replaced at a higher rate, about a day. */
        const val SWEEP_REPLACE_BLOCKS = 144u

        /** Blocks between two reads of the rate while the leftover waits for cheaper fees, about a day. */
        const val SWEEP_RECHECK_BLOCKS = 144u

        /** Blocks the leftover waits for cheaper fees before it is kept as dust, about four weeks. */
        const val SWEEP_RELEASE_BLOCKS = 4_032u

        /** The least time between two builds, so a divergence that persists does not rebuild every tick. */
        val REBUILD_INTERVAL = 10.minutes

        const val LOST_REASON = "Claimed by an older state"

        const val NO_LEAF_REASON = "No leaf can be exited"
    }

    suspend fun tick(start: SparkExitRecord): SparkExitRecord {
        var record = start
        when (record.phase) {
            SparkExitRecord.Phase.Done, is SparkExitRecord.Phase.Failed -> {
                if (!record.isLeftoverSweepPending && !record.isEndedPaymentPending) return record
                record = watchEndedPayment(record)
                if (record.isLeftoverSweepPending) record = sweepLeftover(record)
                save(record)
                return record
            }
            is SparkExitRecord.Phase.AwaitingFunding, is SparkExitRecord.Phase.NeedsFunding -> {
                // A higher fee paid from savings: the generation out there keeps going while that payment confirms.
                if (record.isDrivingWhileFunding) record = driveWhileFunding(record)
                record = lookForOwnPayment(record)
                val sdk = recovering
                val since = record.retrySince
                if (record.isFundingWaitingLeaves && since != null && tip != null &&
                    SparkExitMath.isRetryReleased(since, tip)
                ) {
                    // A skipped leaf's fee funding that never came: let go after the same week as its retries.
                    Logger.warn("Releasing Spark exit leaves '${record.waitingLeafIds}' unfunded", context = TAG)
                    record = end(record.ended(), record)
                } else if (sdk != null && record.phase.requiredFundingSats != null) {
                    record = buildOnceFunded(record, sdk, stored = start.recovery)
                }
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
        // Without a tip the countdown keeps its last value rather than jump to another rule.
        if (tip != null) {
            record = record.copy(claimableAtHeight = record.recovery?.let { SparkExitMath.claimableAtHeight(it, tip) })
        }
        // The tick that ends the exit also sends its leftover fee funding to savings.
        if (!record.isActive) record = watchEndedPayment(record)
        if (record.isLeftoverSweepPending) record = sweepLeftover(record)
        save(record)
        return record
    }

    /**
     * Builds once the funding [record] waits for arrived, or from what it holds when that pays for a Redo rebuild.
     * [stored] is the recovery from before this tick's check.
     */
    private suspend fun buildOnceFunded(
        record: SparkExitRecord,
        sdk: SparkExitRecovering,
        stored: RecoverFundsResponse?,
    ): SparkExitRecord {
        val shortfall = record.fundingShortfallSats
        // Nothing to fund when every leaf is recovered cooperatively.
        val funded = if (shortfall != null) adoptFunding(record, shortfall) else record
        return when {
            // A generation that diverged while a top-up was awaited rebuilds from the funding it already has when
            // that pays for it, so a top-up that never comes does not leave the exit waiting.
            funded == null && record.isRedoRebuild && record.isRebuilding && isRebuildDue(record) ->
                build(record, sdk).let { tried ->
                    if (tried.phase is SparkExitRecord.Phase.NeedsFunding) {
                        countShortOwnPayment(tried.copy(lastBuildAt = nowMillis()))
                    } else {
                        tried
                    }
                }
            funded == null -> countShortOwnPayment(record)
            // Funding for leaves a partial build skipped: only they are built, once it arrived.
            funded.isFundingWaitingLeaves -> retryWaitingLeaves(funded, sdk, isFunded = true)
                ?: end(funded.ended(), funded)
            // Funding an unverified rebuild asked for resumes that rebuild, with its guards, once a block like the
            // check's rebuild: the valid generation's retry clock does not pace it.
            funded.isUnverifiedAsk && !funded.isRebuilding -> {
                // A clean check while the payment came can read the unread step again, and it is then sent: a
                // rebuild now would conflict with it at the same fee. The ask ends, and the funding it adopted stays
                // for a later rebuild or the leftover sweep.
                val unilateral = funded.recovery?.transactions.orEmpty()
                    .filter { it.kind != RecoveryTxKind.COOPERATIVE }
                if (unilateral.any { it.status == ExitTransactionStatus.Ready } ||
                    unilateral.none { it.status == ExitTransactionStatus.Unverified }
                ) {
                    Logger.info("Spark exit steps read again: no funded unverified rebuild", context = TAG)
                    return funded.copy(phase = SparkExitRecord.Phase.Running, isUnverifiedAsk = false)
                }
                // A step this check just turned unverified may have been sent on an earlier clean check: the resume
                // waits for the next check, as the check's own rebuild does.
                val unreadBefore = stored?.transactions.orEmpty()
                    .filter { it.status == ExitTransactionStatus.Unverified }
                    .map { it.txid }
                    .toSet()
                if (unilateral.any { it.status == ExitTransactionStatus.Unverified && it.txid !in unreadBefore }) {
                    return funded
                }
                if (tip != null && funded.unverifiedRebuiltAtHeight == tip) return funded
                val resumed = funded.copy(unverifiedRebuiltAtHeight = tip)
                build(resumed, sdk, isUnverifiedRebuild = true).let {
                    // A resume that adopted nothing keeps the valid generation's retry clock, as a check's rebuild
                    // does.
                    if (it.generation != resumed.generation || !it.isActive) return@let it
                    it.copy(retrySince = resumed.retrySince, retriedAt = resumed.retriedAt)
                }
            }
            else -> build(funded, sdk)
        }
    }

    private suspend fun adoptFunding(record: SparkExitRecord, requiredSats: ULong): SparkExitRecord? {
        val first = record.funding.firstOrNull() ?: return null
        val candidates = SparkExitMath.newFunding(chain.utxos(first.address), record)
        val inputFee = SparkExitMath.inputFeeSats(record.feeRateSatPerVbyte)
        val utxos = SparkExitMath.fundingTopUp(candidates, requiredSats, inputFee) ?: return null
        val adopted = utxos.map { first.copy(txid = it.txid, vout = it.vout, valueSats = it.value) }
        val funding = if (record.funding.size == 1 && first.txid == null) adopted else record.funding + adopted
        return record.copy(funding = funding)
    }

    /**
     * Builds [start]'s leaves as a new generation. Over a generation that did not diverge, however the build was
     * reached (an unverified rebuild, a fee bump, a rebuild after a leaf was left behind), a build that leaves
     * waiting a leaf that generation recovers is not adopted: the generation stays the exit's, sent every tick.
     * With [isUnverifiedRebuild], a build that asks for more fee funding also keeps sending it while the top-up waits.
     */
    private suspend fun build(
        start: SparkExitRecord,
        sdk: SparkExitRecovering,
        isUnverifiedRebuild: Boolean = false,
    ): SparkExitRecord {
        // A pending fee bump builds at its rate at most once a block, however the build was reached.
        if (start.isFeeBumpPending && start.feeBumpBuiltAtHeight != tip) {
            return build(start.copy(feeBumpBuiltAtHeight = tip), sdk, isUnverifiedRebuild)
        }
        // A build waiting for a leaf that may still move tries again only at its paced heights, or once its wait ends.
        val since = start.retrySince
        if (since != null && tip != null && !SparkExitMath.isRetryDue(since, start.retriedAt, tip) &&
            !SparkExitMath.isRetryReleased(since, tip)
        ) {
            return start
        }
        val (preparedRecord, prepared) = prepare(withExitState(start, sdk), sdk)
        val stopped = prepared?.exitChainState?.stoppedLeafIds.orEmpty().toSet()
        val record = preparedRecord.seeStopped(stopped)
        if (prepared == null) {
            // The last leaf lost on a tick that could not read the funding address ends here, still as lost.
            val reason = if (record.isEveryLeafLost) LOST_REASON else NO_LEAF_REASON
            val failed = record.copy(phase = SparkExitRecord.Phase.Failed(reason), isRebuilding = false)
            return end(failed, record)
        }
        if (prepared.leaves.isEmpty()) {
            // A leaf that may still move, such as one the chain could not be read for, is waited for, about a week at
            // most; finished leaves are not listed.
            if (isWaiting(record, prepared.skipped, stopped)) {
                Logger.info("Waiting for Spark exit leaves that may still move", context = TAG)
                return record.copy(retrySince = record.retrySince ?: tip, retriedAt = tip ?: record.retriedAt)
            }
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
                val left = record.leaveBehind(refusal.leafId, refusal.reason)
                // Over a valid generation that recovers a leaf this build left waiting, that generation stays,
                // rebuilt over again under this guard once a block.
                if (left.keepsValidGenerationOver(waitingLeafIds(record, prepared.skipped, stopped))) {
                    return left.keepingValidGeneration()
                }
                return left.copy(isRebuilding = true, lastBuildAt = null)
            }
            if (it !is SdkException.InsufficientCpfpFunds) throw it
            val asked = askForFunding(record, it)
            // Like a fee bump's, the valid generation keeps going while the funding it asks for comes.
            if (isUnverifiedRebuild && asked.phase is SparkExitRecord.Phase.NeedsFunding) {
                return asked.copy(isRebuilding = false, isUnverifiedAsk = true)
            }
            return asked
        }
        if (built.transactions.isEmpty()) {
            // A build of only cooperative leaves the operators could not co-sign is kept, a rebuild's too: they are
            // retried for a week like any failed leaf, then let go.
            if (built.failed.isNotEmpty()) {
                val waiting = waitingLeafIds(record, prepared.skipped, stopped)
                // Adopted, it would drop the signed steps of the leaf it could not read for the week the failed
                // leaves are retried, past the window in which they are ours to publish.
                if (record.keepsValidGenerationOver(waiting)) return record.keepingValidGeneration()
                val adopted = record.adopt(built, nowMillis()).copy(waitingLeafIds = waiting)
                save(adopted)
                Logger.info("Built Spark exit of only '${built.failed.size}' failed cooperative leaves", context = TAG)
                return adopted
            }
            return end(record.copy(phase = SparkExitRecord.Phase.Failed("Nothing to recover")), record)
        }
        // A leaf the operators' watchtower stopped is waited for, not struck, until its wait is over.
        val lost = record.lostLeaves(built).filter { !isStoppedWaiting(record, it.leafId, stopped) }
        if (lost.isNotEmpty()) return loseLeaves(record, lost)
        // A leaf that may still move is kept apart and tried again before the exit ends.
        val waiting = waitingLeafIds(record, prepared.skipped, stopped)
        // A rebuild of other leaves would drop the signed steps of a waiting leaf the valid generation recovers, until
        // the other leaves' chains confirm: the valid generation stays, rebuilt over once a block.
        if (record.keepsValidGenerationOver(waiting)) return record.keepingValidGeneration()
        val adopted = record.adopt(built, nowMillis()).copy(waitingLeafIds = waiting)
        // Saved before any of it is broadcast, so a crash never leaves broadcast transactions without their record.
        save(adopted)
        Logger.info(
            "Built Spark exit generation '${adopted.generation}' of '${built.transactions.size}' transactions",
            context = TAG,
        )
        return broadcastReady(adopted)
    }

    private suspend fun withExitState(record: SparkExitRecord, sdk: SparkExitRecovering): SparkExitRecord {
        record.exitStateSnapshot?.let { snapshot ->
            runSuspendCatching { sdk.importExitState(snapshot) }
                .onSuccess {
                    Logger.info(
                        "Imported the Spark exit's own exit data: '${it.importedLeaves}' leaves imported, " +
                            "'${it.skippedChains}' already held, '${it.skippedForeignLeaves}' foreign, " +
                            "'${it.skippedConflictingLeaves}' conflicting",
                        context = TAG,
                    )
                }
                .onFailure { Logger.warn("Failed to import the Spark exit data snapshot", it, context = TAG) }
            return record
        }
        // A salvaged record rebuilds from what the SDK holds now; a fresh export would not hold more.
        if (record.recovery != null || record.generation > 0 || record.isRebuiltFromChain) return record
        val exported = runSuspendCatching { sdk.exportExitState() }
            .onFailure { Logger.warn("Failed to export the Spark exit data snapshot", it, context = TAG) }
            .getOrNull()
            ?: return record
        return record.copy(exitStateSnapshot = exported)
    }

    private fun SparkExitRecord.seeStopped(stopped: Set<String>): SparkExitRecord {
        val height = tip ?: return this
        val fresh = stopped.filter { it in leafIds && it !in stoppedLeafSince }
        if (fresh.isEmpty()) return this
        return copy(stoppedLeafSince = stoppedLeafSince + fresh.associateWith { height })
    }

    private fun isStoppedWaiting(record: SparkExitRecord, leafId: String, stopped: Set<String>): Boolean {
        if (leafId !in stopped) return false
        val since = record.stoppedLeafSince[leafId] ?: return true
        val height = tip ?: return true
        return height < since || height - since < SparkExitMath.STOPPED_BLOCKS
    }

    private fun isWaiting(record: SparkExitRecord, skipped: List<SkippedLeaf>, stopped: Set<String>): Boolean =
        waitingLeafIds(record, skipped, stopped).isNotEmpty()

    private fun waitingLeafIds(
        record: SparkExitRecord,
        skipped: List<SkippedLeaf>,
        stopped: Set<String>,
    ): List<String> {
        val since = record.retrySince
        if (since != null && tip != null && SparkExitMath.isRetryReleased(since, tip)) return emptyList()
        return skipped.filter {
            it.leafId in record.exitLeafIds && when (it.reason) {
                SkippedLeafReason.Unverified,
                SkippedLeafReason.FundsNotFound,
                SkippedLeafReason.OperatorsUnavailable,
                -> true
                is SkippedLeafReason.NotRecoverable -> isStoppedWaiting(record, it.leafId, stopped)
                SkippedLeafReason.FeeExceedsValue -> false
            }
        }.map { it.leafId }
    }

    private fun prepareRequest(record: SparkExitRecord, selection: ExitLeafSelection) = PrepareRecoverFundsRequest(
        feeRateSatPerVbyte = record.feeRateSatPerVbyte,
        fundingKind = CpfpFundingKind.P2wpkh,
        fundingStrategy = record.cpfpFundingStrategy,
        destination = record.destination,
        selection = selection,
    )

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
            if (listed.all { it in ids }) return record to prepared
            // It also lists leaves outside the exit, such as one an older state claimed: only the exit's are built.
            val remaining = record.exitLeafIds
            if (remaining.isEmpty()) return record to null
            return record to sdk.prepare(prepareRequest(record, ExitLeafSelection.Specific(remaining)))
        }
    }

    private suspend fun loseLeaves(record: SparkExitRecord, lost: List<SparkExitRecord.LostLeaf>): SparkExitRecord {
        Logger.warn("Dropping Spark exit leaves '${lost.map { it.leafId }}' claimed by an older state", context = TAG)
        val updated = record.copy(lostLeaves = record.lostLeaves + lost, isRebuilding = true, lastBuildAt = null)
        if (updated.exitLeafIds.isNotEmpty()) return updated
        return end(updated.copy(phase = SparkExitRecord.Phase.Failed(LOST_REASON), isRebuilding = false), updated)
    }

    /**
     * A payment from savings that confirmed at the address without covering the funding is no longer waited for: it
     * counts as funding seen there, and the rest is asked for and reminded about at once.
     */
    private suspend fun countShortOwnPayment(record: SparkExitRecord): SparkExitRecord {
        val txid = record.fundingPaymentTxid?.takeIf { record.isAwaitingOwnPayment } ?: return record
        val address = record.funding.firstOrNull()?.address ?: return record
        val utxos = runSuspendCatching { chain.utxos(address) }.getOrNull() ?: return record
        if (utxos.none { it.txid == txid && it.status.confirmed }) return record
        Logger.info("Spark exit savings payment '$txid' confirmed short, asking for the rest", context = TAG)
        return record.copy(
            fundingPaymentTxid = null,
            fundingPaymentSeenAtHeight = null,
            fundingPaymentMissingSince = null,
        )
    }

    private suspend fun lookForOwnPayment(record: SparkExitRecord): SparkExitRecord {
        val txid = record.fundingPaymentTxid?.takeIf { record.isAwaitingOwnPayment } ?: return record
        val height = tip ?: return record
        val isKnown = runSuspendCatching { chain.isTxKnown(txid) }.getOrDefault(true)
        return record.withOwnPaymentLooked(height, isKnown)
    }

    private suspend fun driveWhileFunding(record: SparkExitRecord): SparkExitRecord {
        val sdk = recovering ?: return broadcastWithoutSdk(record)
        val stored = requireNotNull(record.recovery)
        val checked = runSuspendCatching { sdk.check(withConfirmations(stored)) }
            .onFailure { Logger.warn("Failed to check the Spark exit, sending without the SDK", it, context = TAG) }
            .getOrNull()
            ?: return broadcastWithoutSdk(record)
        val updated = record.copy(recovery = checked.recovery)
        return when (checked.verdict) {
            RecoveryVerdict.Valid -> {
                // An unverified rebuild's ask ends once every step reads again: the valid generation needs nothing
                // more. Funding paid meanwhile stays at the address, for a later rebuild or the leftover sweep.
                val isAskOver = record.isUnverifiedAsk && record.fundingPaymentTxid == null &&
                    checked.recovery.transactions.none { it.status == ExitTransactionStatus.Unverified }
                val current = if (isAskOver) {
                    updated.copy(phase = SparkExitRecord.Phase.Running, isUnverifiedAsk = false)
                } else {
                    updated
                }
                broadcastReady(current)
            }
            // Finished before the higher fee was needed: running again, the next check ends it and the payment goes
            // back with the leftover.
            RecoveryVerdict.Done -> updated.copy(phase = SparkExitRecord.Phase.Running, isUnverifiedAsk = false)
            // The diverged generation is never sent again: its rebuild, a Redo one, waits for the funding it was paid
            // for.
            is RecoveryVerdict.Redo -> {
                Logger.warn("Spark exit diverged on-chain while a top-up is awaited", context = TAG)
                updated.copy(isRebuilding = true, isRedoRebuild = true, isUnverifiedAsk = false)
            }
        }
    }

    private suspend fun check(record: SparkExitRecord, sdk: SparkExitRecovering): SparkExitRecord {
        val stored = record.recovery ?: return record.copy(phase = SparkExitRecord.Phase.AwaitingFunding(0uL))
        // Nothing to check or send: only the failed cooperative leaves are left, and their retry decides the end.
        if (stored.transactions.isEmpty() && stored.failed.isNotEmpty()) {
            if (!record.isFailedLeavesReleased) return retryFailedLeaves(record, stored.failed.map { it.leafId }, sdk)
            return retryWaitingLeaves(record, sdk) ?: end(record.ended(), record)
        }
        // An SDK that cannot check this tick still leaves the stored, signed steps to send.
        val checked = runSuspendCatching { sdk.check(withConfirmations(stored)) }
            .onFailure { Logger.warn("Failed to check the Spark exit, sending without the SDK", it, context = TAG) }
            .getOrNull()
            ?: return broadcastWithoutSdk(record)
        Logger.debug("Checked Spark exit generation '${record.generation}': '${checked.verdict}'", context = TAG)
        val updated = record.copy(
            recovery = checked.recovery
        ).withProgress(checked.recovery).withRebuildLanded(checked.recovery)
        return when (checked.verdict) {
            RecoveryVerdict.Valid -> {
                val confirmed = checked.recovery.transactions
                    .filter { it.status is ExitTransactionStatus.Confirmed }
                    .map { it.txid }
                    .toSet()
                if (!updated.isStalled) {
                    val dead = deadChildren(stored) { it in confirmed }
                    if (dead.isNotEmpty()) {
                        // The SDK still calls it valid, but the steps that spend a dead child's change never confirm.
                        Logger.warn("Rebuilding Spark exit whose CPFP children '$dead' lost a race", context = TAG)
                        return stallRebuild(updated, sdk)
                    }
                }
                val sent = broadcastReady(updated)
                val stuck = sent.stuckStep
                if (stuck != null && !sent.isStalled) {
                    Logger.warn("Rebuilding Spark exit over step '${stuck.txid}' that keeps failing", context = TAG)
                    return stallRebuild(sent, sdk)
                }
                if (sent.isFeeBumpPending && tip != null && sent.feeBumpBuiltAtHeight != tip) {
                    return buildFeeBump(sent, sdk)
                }
                // A check marks a step unverified when one chain read fails and clears it on the next clean check, so
                // only steps that were all unverified before this check are rebuilt over: one this check just turned
                // unverified may be in flight, and holds the rebuild as a ready one does. A step that stays unread is
                // rebuilt at the first check, at most once a block, where no other step just turned unverified.
                if (!needsUnverifiedRebuild(sent, stored)) return sent
                // A step that stays unread past a check is settled by a fresh build, which reads the chain again.
                Logger.info("Rebuilding Spark exit over steps the SDK could not read", context = TAG)
                val rebuilding = sent.copy(isRebuilding = true, unverifiedRebuiltAtHeight = tip, isRedoRebuild = false)
                // Saved as the valid generation: a run killed during the rebuild goes on sending it.
                save(rebuilding.copy(isRebuilding = false))
                val rebuilt = build(rebuilding, sdk, isUnverifiedRebuild = true)
                // A rebuild that adopted nothing, such as one waiting for a leaf it could not read either, leaves the
                // valid generation going: checked and sent every tick, and rebuilt over again once a block.
                if (!rebuilt.isAdoptionPending(sent)) return rebuilt
                rebuilt.copy(isRebuilding = false, retrySince = sent.retrySince, retriedAt = sent.retriedAt)
            }
            RecoveryVerdict.Done -> {
                // Leaves the operators moved on-chain but could not co-sign stay part of the exit, retried for a week.
                if (checked.recovery.failed.isNotEmpty() && !updated.isFailedLeavesReleased) {
                    return retryFailedLeaves(updated, checked.recovery.failed.map { it.leafId }, sdk)
                }
                retryWaitingLeaves(updated, sdk)?.let { return it }
                end(updated.ended(), updated)
            }
            is RecoveryVerdict.Redo -> {
                Logger.warn(
                    "Rebuilding Spark exit diverged on-chain as generation '${record.generation + 1}'",
                    context = TAG,
                )
                // Only a rebuild over a diverged chain can find a leaf an older state claimed.
                rebuild(updated.copy(isRedoRebuild = true), sdk)
            }
        }
    }

    /**
     * A fee bump whose build kept [sent], the valid generation, over a waiting leaf is built at its rate again once a
     * block, whatever the leaf waits for, until a build at it is adopted; that build also settles any unread step. A
     * build it does not adopt leaves [sent] going, checked and sent every tick, and one that asks for more fee funding
     * keeps sending it while the top-up waits.
     */
    private suspend fun buildFeeBump(sent: SparkExitRecord, sdk: SparkExitRecovering): SparkExitRecord {
        Logger.info("Rebuilding Spark exit at its higher fee rate '${sent.feeRateSatPerVbyte}' sat/vB", context = TAG)
        val rebuilding = sent.copy(
            isRebuilding = true,
            feeBumpBuiltAtHeight = tip,
            unverifiedRebuiltAtHeight = tip,
            isRedoRebuild = false,
        )
        // Saved as the valid generation: a run killed during the rebuild goes on sending it.
        save(rebuilding.copy(isRebuilding = false))
        val rebuilt = build(rebuilding, sdk)
        return when {
            rebuilt.isAdoptionPending(sent) ->
                rebuilt.copy(isRebuilding = false, retrySince = sent.retrySince, retriedAt = sent.retriedAt)
            rebuilt.generation == sent.generation && rebuilt.phase is SparkExitRecord.Phase.NeedsFunding ->
                rebuilt.copy(isRebuilding = false)
            else -> rebuilt
        }
    }

    /**
     * What the exit's funding address must still receive to rebuild at [rate]: a build of the exit's leaves with the
     * funding it holds, nothing broadcast, on a copy with the rebuild's drop-and-retry. A leaf the prepare or the build
     * refuses at that rate throws [SparkExitFeeBumpLeavesBehind]. Zero when that funding covers it; otherwise the
     * SDK's shortfall less what the address still holds, with one more input's fee for the payment, at least dust.
     */
    suspend fun feeBumpShortfall(record: SparkExitRecord, rate: ULong): ULong {
        val sdk = checkNotNull(recovering) { "Spark is not connected" }
        val atRate = withExitState(record, sdk).copy(feeRateSatPerVbyte = rate)
        val (priced, prepared) = prepare(atRate, sdk)
        // A rate at which a leaf can't pay its own exit would leave that leaf on Spark: never offered.
        val dropped = priced.leftBehindLeaves.drop(record.leftBehindLeaves.size)
        if (dropped.isNotEmpty() || prepared == null) throw SparkExitFeeBumpLeavesBehind(leavesSats(record, dropped))
        // A prepare that succeeds can still skip an exiting leaf whose exit costs more than it holds at this rate.
        val unpaid = prepared.skipped.filter {
            it.leafId in priced.exitLeafIds && it.reason == SkippedLeafReason.FeeExceedsValue
        }
        if (unpaid.isNotEmpty()) {
            throw SparkExitFeeBumpLeavesBehind(unpaid.fold(0uL) { sum, leaf -> sum.safe() + leaf.valueSats.safe() })
        }
        if (prepared.funding == null) return 0uL
        val built =
            runSuspendCatching { sdk.recover(RecoverFundsRequest(prepared, priced.fundingInputs), makeSigner()) }
        val error = built.exceptionOrNull() ?: return 0uL
        SparkExitUnexitable.parse(error)?.takeIf { it.leafId in priced.exitLeafIds }?.let {
            throw SparkExitFeeBumpLeavesBehind(record.leafValueSats(it.leafId))
        }
        val required = (error as? SdkException.InsufficientCpfpFunds ?: throw error).requiredSats
        val held = priced.funding.firstOrNull()?.address?.let { address ->
            runSuspendCatching { chain.utxos(address) }.getOrNull()?.let { SparkExitMath.heldSats(it, priced) }
        }
        val needed = SparkExitMath.neededFunding(required, priced.fundedSats, held, SparkExitMath.inputFeeSats(rate))
        val extra = if (needed > priced.fundedSats) needed - priced.fundedSats else 0uL
        return maxOf(extra, SparkExitMath.FUNDING_DUST_SATS)
    }

    private fun leavesSats(record: SparkExitRecord, leaves: List<SparkExitRecord.LeftBehindLeaf>): ULong =
        leaves.fold(0uL) { sum, leaf -> sum.safe() + record.leafValueSats(leaf.leafId).safe() }

    private suspend fun retryFailedLeaves(
        start: SparkExitRecord,
        failedIds: List<String>,
        sdk: SparkExitRecovering,
    ): SparkExitRecord {
        val known = start.failedLeaves.associate { it.leafId to it.valueSats }
        var record = start.copy(failedLeaves = failedIds.map { SparkExitRecord.FailedLeaf(it, known[it] ?: 0uL) })
        val height = tip ?: return record
        val since = record.failedLeafRetrySince
        if (since != null && SparkExitMath.isRetryReleased(since, height)) {
            Logger.warn("Releasing Spark exit leaves '$failedIds' the operators did not co-sign", context = TAG)
            val released = record.copy(isFailedLeavesReleased = true)
            retryWaitingLeaves(released, sdk)?.let { return it }
            return end(released.ended(), record)
        }
        if (since != null && !SparkExitMath.isRetryDue(since, record.failedLeafRetriedAt, height)) return record
        record = record.copy(failedLeafRetrySince = since ?: height, failedLeafRetriedAt = height)
        var skipped = emptyList<String>()
        val retried = runSuspendCatching {
            record = withExitState(record, sdk)
            val prepared = sdk.prepare(prepareRequest(record, ExitLeafSelection.Specific(failedIds)))
            val values = (
                prepared.leaves.map { it.leafId to it.valueSats } +
                    prepared.skipped.map { it.leafId to it.valueSats }
                ).toMap()
            record = record.copy(
                failedLeaves = record.failedLeaves.map { it.copy(valueSats = values[it.leafId] ?: it.valueSats) },
            )
            if (prepared.leaves.isEmpty()) return@runSuspendCatching null
            // A failed leaf the prepare skipped while it may still move is kept and tried again on its own.
            val stopped = prepared.exitChainState.stoppedLeafIds.toSet()
            skipped = waitingLeafIds(record.seeStopped(stopped), prepared.skipped, stopped)
            val needsFunding = prepared.funding != null
            sdk.recover(
                RecoverFundsRequest(prepared, if (needsFunding) record.fundingInputs else emptyList()),
                if (needsFunding) makeSigner() else null,
            )
        }.onFailure { Logger.warn("Failed to retry Spark exit leaves '$failedIds'", it, context = TAG) }.getOrNull()
        if (retried == null || retried.transactions.isEmpty()) return record
        val adopted = record.adopt(retried, nowMillis())
            .copy(waitingLeafIds = (record.waitingLeafIds + skipped).distinct())
        save(adopted)
        Logger.info("Built a recovery of Spark exit leaves '$failedIds' as '${adopted.generation}'", context = TAG)
        return broadcastReady(adopted)
    }

    /**
     * The SDK names the total its funding inputs must hold (CPFP cost plus change dust), not what is missing. After a
     * Redo the inputs are partly spent, so what the address still holds decides the shortfall.
     */
    private suspend fun askForFunding(
        record: SparkExitRecord,
        error: SdkException.InsufficientCpfpFunds,
    ): SparkExitRecord {
        Logger.warn("Needs '${error.requiredSats}' sats of fee funding in total for the Spark exit", context = TAG)
        val held = record.funding.firstOrNull()?.address?.let { address ->
            runSuspendCatching { chain.utxos(address) }.getOrNull()?.let { SparkExitMath.heldSats(it, record) }
        }
        // Without a reading of the address the shortfall is unknown; the next tick builds again.
        if (held == null && record.fundedSats > 0uL) return record
        val inputFee = SparkExitMath.inputFeeSats(record.feeRateSatPerVbyte)
        val needed = SparkExitMath.neededFunding(error.requiredSats, record.fundedSats, held, inputFee)
        return record.copy(phase = SparkExitRecord.Phase.NeedsFunding(needed))
    }

    /**
     * Leaves a build skipped while they may still move, tried again on their own once the rest of the exit is done:
     * at once, then at doubling gaps, until they build or about a week passed. One that needs more fee funding asks
     * for it and is built as soon as it arrives ([isFunded]). Null once nothing waits, so the exit can end.
     */
    private suspend fun retryWaitingLeaves(
        start: SparkExitRecord,
        sdk: SparkExitRecovering,
        isFunded: Boolean = false,
    ): SparkExitRecord? {
        val ids = start.waitingLeafIds.filter { it in start.exitLeafIds }
        if (ids.isEmpty()) return null
        val height = tip ?: return start
        val since = start.retrySince
        if (since != null && SparkExitMath.isRetryReleased(since, height)) {
            Logger.warn("Releasing Spark exit leaves '$ids' that did not move in about a week", context = TAG)
            return null
        }
        if (!isFunded && since != null && !SparkExitMath.isRetryDue(since, start.retriedAt, height)) return start
        var record = start.copy(
            phase = SparkExitRecord.Phase.Running,
            retrySince = since ?: height,
            retriedAt = height,
        )
        val prepared = runSuspendCatching {
            record = withExitState(record, sdk)
            sdk.prepare(prepareRequest(record, ExitLeafSelection.Specific(ids)))
        }.onFailure { Logger.warn("Failed to prepare waiting Spark exit leaves '$ids'", it, context = TAG) }
            .getOrNull()
            ?: return record
        val stopped = prepared.exitChainState.stoppedLeafIds.toSet()
        record = record.seeStopped(stopped)
        val still = waitingLeafIds(record, prepared.skipped, stopped)
        if (prepared.leaves.isEmpty()) return record.takeIf { still.isNotEmpty() }?.copy(waitingLeafIds = still)
        val needsFunding = prepared.funding != null
        val result = runSuspendCatching {
            sdk.recover(
                RecoverFundsRequest(prepared, if (needsFunding) record.fundingInputs else emptyList()),
                if (needsFunding) makeSigner() else null,
            )
        }
        (result.exceptionOrNull() as? SdkException.InsufficientCpfpFunds)?.let { error ->
            val asked = askForFunding(record, error)
            // Unasked while the address can't be read: the next tick tries again, not the next retry gap.
            if (asked.phase is SparkExitRecord.Phase.NeedsFunding) return asked
            return asked.copy(retrySince = start.retrySince, retriedAt = start.retriedAt)
        }
        val built = result
            .onFailure { Logger.warn("Failed to build waiting Spark exit leaves '$ids'", it, context = TAG) }
            .getOrNull()
        if (built == null || built.transactions.isEmpty()) return record
        val adopted = record.adopt(built, nowMillis()).copy(waitingLeafIds = still)
        save(adopted)
        Logger.info("Built a recovery of waiting Spark exit leaves '$ids' as '${adopted.generation}'", context = TAG)
        return broadcastReady(adopted)
    }

    /** Done, with the leaves it waited for let go; failed leaves it released stay listed. */
    private fun SparkExitRecord.ended() = copy(
        phase = SparkExitRecord.Phase.Done,
        failedLeaves = if (isFailedLeavesReleased) failedLeaves else emptyList(),
        // The quote's skips never were exit leaves: the leaves let go here add to them.
        skippedLeafCount = skippedLeafCount + waitingLeafIds.size,
        waitingLeafIds = emptyList(),
        retrySince = null,
        retriedAt = null,
    )

    private suspend fun rebuild(record: SparkExitRecord, sdk: SparkExitRecovering): SparkExitRecord {
        val rebuilding = record.copy(isRebuilding = true)
        save(rebuilding)
        if (!isRebuildDue(rebuilding)) return rebuilding
        return build(rebuilding, sdk)
    }

    private suspend fun stallRebuild(record: SparkExitRecord, sdk: SparkExitRecovering): SparkExitRecord {
        val confirmed = record.recovery?.let(SparkExitMath::confirmedCount) ?: 0
        val counted = record.copy(
            stallRebuilds = record.stallRebuilds + 1,
            stallConfirmedCount = confirmed,
            isRedoRebuild = false,
        )
        return rebuild(counted, sdk)
    }

    private fun SparkExitRecord.withRebuildLanded(checked: RecoverFundsResponse): SparkExitRecord {
        val since = rebuiltFromChainConfirmedCount ?: return this
        if (SparkExitMath.confirmedCount(checked) <= since) return this
        return copy(isRebuiltFromChain = false, rebuiltFromChainConfirmedCount = null)
    }

    private fun SparkExitRecord.withProgress(checked: RecoverFundsResponse): SparkExitRecord {
        val since = stallConfirmedCount ?: return this
        if (SparkExitMath.confirmedCount(checked) <= since) return this
        return copy(stallRebuilds = 0, stallConfirmedCount = null)
    }

    private fun SparkExitRecord.isAdoptionPending(before: SparkExitRecord): Boolean =
        isRebuilding && phase == SparkExitRecord.Phase.Running && generation == before.generation &&
            lostLeaves == before.lostLeaves

    private fun needsUnverifiedRebuild(record: SparkExitRecord, stored: RecoverFundsResponse): Boolean {
        val height = tip ?: return false
        if (record.unverifiedRebuiltAtHeight == height) return false
        val unreadBefore = stored.transactions
            .filter { it.status == ExitTransactionStatus.Unverified }
            .map { it.txid }
            .toSet()
        val transactions = record.recovery?.transactions.orEmpty().filter { it.kind != RecoveryTxKind.COOPERATIVE }
        val unread = transactions.filter { it.status == ExitTransactionStatus.Unverified }
        return unread.isNotEmpty() && unread.all { it.txid in unreadBefore } &&
            transactions.none { it.status == ExitTransactionStatus.Ready }
    }

    private suspend fun deadChildren(
        recovery: RecoverFundsResponse,
        isConfirmed: (String) -> Boolean,
    ): Set<String> {
        val children = SparkExitFeeChain.children(recovery)
        val spends = SparkExitFeeChain.spends(recovery)
        val stepOf = children.entries.associate { (step, child) -> child to step }
        val suspects = children
            .filterKeys { !isConfirmed(it) }
            .values
            .flatMap { spends[it].orEmpty() }
            .filter { spent -> stepOf[spent]?.let(isConfirmed) == true }
            .distinct()
        val lost = suspects.filter { isUnknownToChain(it) }.toSet()
        return SparkExitFeeChain.withDescendants(spends, lost)
    }

    private suspend fun isUnknownToChain(txid: String): Boolean =
        runSuspendCatching { !chain.isTxKnown(txid) }.getOrDefault(false)

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

    private suspend fun sweepLeftover(record: SparkExitRecord): SparkExitRecord {
        val built = record.leftoverSweep
        if (built?.txHex != null) {
            // The sweep, or a version it replaced, confirmed.
            built.sentTxids.firstOrNull { isConfirmed(it) }?.let { return sweepLanded(record, built, it) }
            // Refused for its fee: wait for the next block and try again at a rate nodes take.
            if (built.isFeeRejection) return waitForFees(record, built)
            // No node took it in a day: built again from what the address holds.
            val builtAt = built.builtAtHeight
            if (!built.isAccepted && builtAt != null && tip != null && tip >= builtAt + SWEEP_RECHECK_BLOCKS) {
                return readAddressAgain(record, built)
            }
            val sentAt = built.sentAtHeight
            val isOverdue = built.isAccepted && sentAt != null && tip != null && tip >= sentAt + SWEEP_REPLACE_BLOCKS
            if (isOverdue) replaceSweep(record, built)?.let { return it }
            // Sent again until it confirms; a node that already has it accepts it again.
            return sendSweep(record, built, built.txHex)
        }
        if (built?.isWaitingForFees == true) {
            // A version a node accepted before a later refusal may still confirm.
            built.replacedTxids.firstOrNull { isConfirmed(it) }?.let { return sweepLanded(record, built, it) }
            val height = tip ?: return record
            val since = requireNotNull(built.waitingSince)
            if (height >= since + SWEEP_RELEASE_BLOCKS) {
                Logger.info("Keeping the Spark exit leftover as dust after waiting for cheaper fees", context = TAG)
                // Shown once more with its path, even after the result was opened; the earlier exit addresses keep
                // listing it after that.
                return record.copy(leftoverSweep = built.copy(isDustKept = true), isResultSeen = false)
            }
            val next = built.retryAtHeight ?: ((built.checkedAtHeight ?: since) + SWEEP_RECHECK_BLOCKS)
            if (height < next) return record
        }
        val address = record.funding.firstOrNull()?.address ?: return record
        val utxos = runSuspendCatching { chain.utxos(address) }.getOrNull() ?: return record
        if (utxos.isEmpty()) return record.copy(leftover = emptyList())
        // Unconfirmed outputs hold the sweep block by block, for a day at most; then the confirmed ones go.
        val confirmed = utxos.filter { it.status.confirmed }
        if (confirmed.size < utxos.size) {
            val height = tip ?: return record
            val since = built?.takeIf { it.isWaitingForFees }?.waitingSince ?: height
            if (confirmed.isEmpty() || height < since + SWEEP_RECHECK_BLOCKS) {
                val total = utxos.fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }
                val waiting = SparkExitRecord.LeftoverSweep(
                    null,
                    null,
                    total,
                    replacedTxids = built?.replacedTxids.orEmpty(),
                    waitingSince = since,
                    checkedAtHeight = height,
                    refusedFeeRate = built?.refusedFeeRate,
                    retryAtHeight = height + 1u,
                )
                return record.copy(
                    leftover = utxos.map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) },
                    leftoverSweep = waiting,
                )
            }
        }
        val current = record.copy(leftover = confirmed.map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) })
        // What not even the lowest relay rate can send stays as dust at once: no wait for fees would change it.
        val all = confirmed.map { SparkExitSweep.Input(it.txid, it.vout, it.value) }
        val isEverSweepable = runCatching {
            SparkExitSweep.plan(SparkExitSweep.worthSweeping(all, 1uL), record.destination, 1uL) != null
        }.getOrDefault(true)
        if (!isEverSweepable) return keepAsDust(record, current, utxos, built)
        val refused = built?.takeIf { it.isWaitingForFees }?.refusedFeeRate ?: 0uL
        // A refusal raises the rate up to the next block's or the exit's, never above: a node asking more is refused
        // again, and the sweep waits a block for fees to drop.
        val cap = if (refused > 0uL) {
            minOf(maxOf(nextBlockRate() ?: 0uL, record.feeRateSatPerVbyte), sweepFeeCeiling(record))
        } else {
            0uL
        }
        val floor = minOf(refused, cap)
        val rate = maxOf(SparkExitMath.sweepFeeRate(economyRate(), record.feeRateSatPerVbyte), floor)
        val inputs = SparkExitSweep.worthSweeping(
            confirmed.map { SparkExitSweep.Input(it.txid, it.vout, it.value) },
            rate,
        )
        return buildSweep(current, inputs, rate, built?.replacedTxids?.takeIf { it.isNotEmpty() })
    }

    /**
     * Confirmed outputs not even the lowest relay rate can send stay as dust. Funding still unconfirmed beside them is
     * not let go: the exit keeps every output and looks again after a recheck.
     */
    private fun keepAsDust(
        record: SparkExitRecord,
        confirmedOnly: SparkExitRecord,
        utxos: List<EsploraUtxo>,
        built: SparkExitRecord.LeftoverSweep?,
    ): SparkExitRecord {
        val isAllConfirmed = utxos.all { it.status.confirmed }
        val confirmed = if (isAllConfirmed) utxos else utxos.filter { it.status.confirmed }
        val total = confirmed.fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }
        if (isAllConfirmed) {
            val kept = SparkExitRecord.LeftoverSweep(null, null, total, isDustKept = true)
            return confirmedOnly.copy(leftoverSweep = kept)
        }
        val height = tip ?: return record
        val waiting = SparkExitRecord.LeftoverSweep(
            null,
            null,
            utxos.fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() },
            replacedTxids = built?.replacedTxids.orEmpty(),
            waitingSince = built?.takeIf { it.isWaitingForFees }?.waitingSince ?: height,
            checkedAtHeight = height,
        )
        return record.copy(
            leftover = utxos.map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) },
            leftoverSweep = waiting,
        )
    }

    /**
     * The sweep confirmed. Funding that reached the address after it was built is still there: it waits and goes out
     * in a sweep of its own, and the confirmed one is kept to show.
     */
    private suspend fun sweepLanded(
        record: SparkExitRecord,
        current: SparkExitRecord.LeftoverSweep,
        txid: String,
    ): SparkExitRecord {
        // A version it replaced confirmed instead: that one's amount and inputs are what reached savings.
        val sweep = record.acceptedSweeps.firstOrNull { it.txid == txid && it.txid != current.txid }
            ?.let { current.copy(txid = it.txid, txHex = it.txHex, valueSats = it.valueSats) }
            ?: current
        val landed = record.copy(leftoverSweep = sweep.copy(isConfirmed = true))
        val address = record.funding.firstOrNull()?.address ?: return landed
        // Read again on the next tick: what is left at the address decides how the sweep ends.
        // A host behind the block that confirmed it may still list what it spent.
        val spent = sweep.txHex?.let { hex -> SparkExitTxInputs.decode(hex)?.map { "${it.prevTxid}:${it.vout}" } }
            ?: record.leftover.map { "${it.txid}:${it.vout}" }
        val utxos = runSuspendCatching { chain.utxos(address) }.getOrNull()
            ?.filter { "${it.txid}:${it.vout}" !in spent }
            ?: return record
        if (utxos.isEmpty()) return landed
        val height = tip ?: return record
        Logger.info("Spark exit leftover sweep '$txid' confirmed with more funding left to sweep", context = TAG)
        val total = utxos.fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }
        return record.copy(
            sweptLeftover = record.sweptLeftover + SparkExitRecord.SweptLeftover(txid, sweep.valueSats),
            leftover = utxos.map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) },
            leftoverSweep = SparkExitRecord.LeftoverSweep(
                null,
                null,
                total,
                waitingSince = height,
                checkedAtHeight = height,
                retryAtHeight = height,
            ),
        )
    }

    /** What the address holds is read again on the next tick, still looking for the versions a node accepted. */
    private fun readAddressAgain(record: SparkExitRecord, sweep: SparkExitRecord.LeftoverSweep): SparkExitRecord {
        val height = tip ?: return record.copy(leftoverSweep = null)
        val total = record.leftover.fold(0uL) { sum, leftover -> sum.safe() + leftover.valueSats.safe() }
        return record.copy(
            leftoverSweep = SparkExitRecord.LeftoverSweep(
                null,
                null,
                total,
                replacedTxids = sweep.sentTxids,
                waitingSince = record.leftoverSweep?.waitingSince ?: height,
                checkedAtHeight = height,
                retryAtHeight = height,
            ),
        )
    }

    private suspend fun nextBlockRate(): ULong? = runSuspendCatching { chain.nextBlockFeeRate() }
        .onFailure { Logger.warn("Failed to read the next-block fee rate for the leftover sweep", it, context = TAG) }
        .getOrNull()

    /**
     * The most a leftover sweep pays per vbyte: the exit's own rate, which the user confirmed, or Bitkit's own fast
     * rate when higher. The chain host's estimates and refusals are not trusted past it.
     */
    private suspend fun sweepFeeCeiling(record: SparkExitRecord): ULong =
        maxOf(record.feeRateSatPerVbyte, runSuspendCatching { trustedFeeRate() }.getOrNull() ?: 0uL)

    private suspend fun economyRate(): ULong? = runSuspendCatching { chain.economyFeeRate() }
        .onFailure { Logger.warn("Failed to read the economy fee rate for the leftover sweep", it, context = TAG) }
        .getOrNull()

    private fun waitForFees(
        record: SparkExitRecord,
        refused: SparkExitRecord.LeftoverSweep? = null,
    ): SparkExitRecord {
        val totalSats = record.leftover.fold(0uL) { sum, leftover -> sum.safe() + leftover.valueSats.safe() }
        val height = tip ?: return record
        Logger.info("Waiting for cheaper fees to sweep the Spark exit leftover", context = TAG)
        val earlier = record.leftoverSweep
        val since = earlier?.takeIf { it.isWaitingForFees }?.waitingSince ?: earlier?.waitingSince ?: height
        val floor = refused?.let {
            SparkExitSweep.refusedFeeRate(it.rejection, it.feeRateSatPerVbyte ?: record.feeRateSatPerVbyte)
        }
        return record.copy(
            leftoverSweep = SparkExitRecord.LeftoverSweep(
                null,
                null,
                totalSats,
                replacedTxids = (refused ?: earlier)?.sentTxids.orEmpty(),
                waitingSince = since,
                checkedAtHeight = height,
                refusedFeeRate = floor,
                retryAtHeight = floor?.let { height + 1u },
            ),
        )
    }

    private suspend fun replaceSweep(record: SparkExitRecord, old: SparkExitRecord.LeftoverSweep): SparkExitRecord? {
        val hex = old.txHex ?: return null
        val nextBlock = runSuspendCatching { chain.nextBlockFeeRate() }
            .onFailure { Logger.warn("Failed to read the Spark exit leftover sweep fee rate", it, context = TAG) }
            .getOrNull()
            ?: return null
        val oldRate = old.feeRateSatPerVbyte ?: record.feeRateSatPerVbyte
        val rate = minOf(maxOf(nextBlock, oldRate + 1uL), sweepFeeCeiling(record))
        // At the ceiling already: sent again as it is until it confirms.
        if (rate <= oldRate) return null
        val values = record.leftover.associate { "${it.txid}:${it.vout}" to it.valueSats }
        val inputs = SparkExitTxInputs.decode(hex)?.map {
            SparkExitSweep.Input(it.prevTxid, it.vout, values["${it.prevTxid}:${it.vout}"] ?: return null)
        } ?: return null
        // Every input kept: a replacement must pay more in all than the sweep it replaces.
        Logger.info("Replacing Spark exit leftover sweep '${old.txid}' at '$rate' sat/vB", context = TAG)
        // A sweep a node accepted may still confirm, so its txid is kept to look for.
        val replaced = old.replacedTxids + listOfNotNull(old.txid.takeIf { old.isAccepted })
        return buildSweep(record, inputs, rate, replaced)
    }

    private suspend fun isConfirmed(txid: String): Boolean =
        runSuspendCatching { chain.txStatus(txid) }.getOrNull()?.confirmed == true

    private suspend fun buildSweep(
        record: SparkExitRecord,
        inputs: List<SparkExitSweep.Input>,
        feeRate: ULong,
        replaced: List<String>? = null,
    ): SparkExitRecord {
        val address = record.funding.firstOrNull()?.address ?: return record
        val plan = runSuspendCatching { SparkExitSweep.plan(inputs, record.destination, feeRate) }
            .getOrElse {
                Logger.error("Failed to plan the Spark exit leftover sweep", it, context = TAG)
                return record
            }
            // A replacement of an accepted sweep keeps that one; otherwise the leftover waits for cheaper fees.
            ?: return if (!replaced.isNullOrEmpty() && record.leftoverSweep?.txHex != null) {
                record
            } else {
                waitForFees(record)
            }
        val sweep = runSuspendCatching {
            val signed = makeSigner().signPsbt(SparkExitSweep.psbt(plan, address))
            val hex = SparkExitSweep.signedTxHex(plan, signed)
            SparkExitRecord.LeftoverSweep(
                txid = SignedTransactionId.fromHex(hex),
                txHex = hex,
                valueSats = plan.amountSats,
                feeRateSatPerVbyte = feeRate.takeIf { it != record.feeRateSatPerVbyte },
                replacedTxids = replaced.orEmpty(),
                builtAtHeight = tip,
            )
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
            SparkExitBroadcastOutcome.Accepted -> record.copy(
                leftoverSweep = sweep.copy(
                    isAccepted = true,
                    rejection = null,
                    sentAtHeight = sweep.sentAtHeight ?: tip
                ),
                acceptedSweeps = record.acceptedSweepsWith(sweep, hex),
            )
            // A replacement refused for another reason while the version it replaced may still confirm waits again.
            is SparkExitBroadcastOutcome.Rejected if sweep.replacedTxids.isNotEmpty() && tip != null &&
                !outcome.reason.contains("fee", ignoreCase = true) ->
                waitForFees(record.copy(leftoverSweep = sweep))
            is SparkExitBroadcastOutcome.Rejected -> {
                val refused = sweep.copy(isAccepted = false, rejection = outcome.reason)
                // A refusal for anything but its fee is final: shown once more with its address, as kept change is.
                record.copy(leftoverSweep = refused, isResultSeen = record.isResultSeen && refused.isFeeRejection)
            }
            // Its inputs went elsewhere: what is still at the address is read and swept again.
            is SparkExitBroadcastOutcome.InputsMissing -> readAddressAgain(record, sweep)
            is SparkExitBroadcastOutcome.RetryLater,
            is SparkExitBroadcastOutcome.NotMature,
            -> record
        }
    }

    private fun SparkExitRecord.acceptedSweepsWith(
        sweep: SparkExitRecord.LeftoverSweep,
        hex: String,
    ): List<SparkExitRecord.AcceptedSweep> {
        val txid = sweep.txid ?: return acceptedSweeps
        if (acceptedSweeps.any { it.txid == txid }) return acceptedSweeps
        return acceptedSweeps + SparkExitRecord.AcceptedSweep(txid, hex, sweep.valueSats)
    }

    private fun isRebuildDue(record: SparkExitRecord): Boolean {
        val last = record.lastBuildAt ?: return true
        return nowMillis() - last >= REBUILD_INTERVAL.inWholeMilliseconds
    }

    private suspend fun leftover(record: SparkExitRecord): List<SparkExitRecord.Leftover>? {
        val address = record.funding.firstOrNull()?.address ?: return emptyList()
        return runSuspendCatching { chain.utxos(address) }.getOrNull()
            ?.map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) }
    }

    private suspend fun end(ended: SparkExitRecord, unchanged: SparkExitRecord): SparkExitRecord {
        val leftover = leftover(ended) ?: return unchanged
        return ended.copy(leftover = leftover)
    }

    private suspend fun broadcastReady(record: SparkExitRecord): SparkExitRecord {
        var current = record
        val recovery = current.recovery ?: return current
        // A cooperative step the SDK could not read goes out too: nodes refuse it if its funds already moved. A
        // unilateral one waits for the next clean check, or a rebuild once it stays unread.
        recovery.transactions
            .filter { it.status == ExitTransactionStatus.Ready || it.isCooperativeUnverified }
            .filter { isDue(it.txid, current) }
            // A ready step's parents are confirmed, as the SDK saw them.
            .forEach {
                current = broadcastAndSave(it, current, parentsConfirmed = it.status == ExitTransactionStatus.Ready)
            }
        return current
    }

    private suspend fun broadcastWithoutSdk(record: SparkExitRecord): SparkExitRecord {
        val recovery = record.recovery ?: return record
        val tip = chain.tipHeight()
        // A saved confirmation is asked again, since a reorg can undo it; it stands only while no host answers.
        val saved = recovery.transactions
            .mapNotNull { tx -> (tx.status as? ExitTransactionStatus.Confirmed)?.let { tx.txid to it.blockHeight } }
            .associate { (txid, height) -> txid to EsploraTxStatus(true, height) }
        val statuses = mutableMapOf<String, EsploraTxStatus?>()
        suspend fun status(txid: String): EsploraTxStatus? {
            if (txid !in statuses) {
                statuses[txid] = runSuspendCatching { chain.txStatus(txid) }.getOrNull() ?: saved[txid]
            }
            return statuses[txid]
        }
        val reorged = saved.keys.filter { status(it)?.confirmed == false }.toSet()
        var current = if (reorged.isEmpty()) record else withReorgUndone(record, recovery, reorged)
        val transactions = recovery.transactions.map { saved ->
            val own = status(saved.txid)
            if (own?.confirmed == true) {
                val height = own.blockHeight ?: (saved.status as? ExitTransactionStatus.Confirmed)?.blockHeight
                return@map saved.copy(status = ExitTransactionStatus.Confirmed(height))
            }
            val tx = if (saved.txid in reorged) {
                saved.copy(status = ExitTransactionStatus.WaitingForDependencies)
            } else {
                saved
            }
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
        val checked = current.copy(recovery = recovery.copy(transactions = transactions))
        // Every step confirmed and no leaf failed: the exit is done without an SDK verdict.
        val isAllConfirmed = transactions.isNotEmpty() &&
            transactions.all { it.status is ExitTransactionStatus.Confirmed }
        val isLeafLeft = recovery.failed.isNotEmpty() || checked.waitingLeafIds.isNotEmpty()
        if (!isAllConfirmed || isLeafLeft || checked.phase != SparkExitRecord.Phase.Running) {
            return checked
        }
        Logger.info("Every Spark exit step confirmed; ending the exit without the SDK", context = TAG)
        return end(checked.ended(), checked)
    }

    /**
     * A payment from savings still unresolved when the exit ended, such as a higher fee sent with an unknown result:
     * its outputs join the leftover once they reach the address, and the own-payment wait forgets one no host sees.
     */
    private suspend fun watchEndedPayment(record: SparkExitRecord): SparkExitRecord {
        val txid = record.fundingPaymentTxid ?: return record
        // Already in the leftover: the sweep takes it, and nothing more is watched.
        if (!record.isActive && record.leftover.any { it.txid == txid }) {
            return record.copy(
                fundingPaymentTxid = null,
                fundingPaymentSeenAtHeight = null,
                fundingPaymentMissingSince = null,
            )
        }
        if (!record.isEndedPaymentPending) return record
        val height = tip ?: return record
        val address = record.funding.firstOrNull()?.address ?: return record
        val arrived = runSuspendCatching { chain.utxos(address) }.getOrNull()?.filter { it.txid == txid }
            ?: return record
        if (arrived.isEmpty()) {
            val isKnown = runSuspendCatching { chain.isTxKnown(txid) }.getOrDefault(true)
            return record.withOwnPaymentLooked(height, isKnown)
        }
        val sweep = record.leftoverSweep
        // A sweep still out there settles first; then the payment goes in a sweep of its own.
        if (sweep?.txHex != null && !sweep.isConfirmed && sweep.rejection == null) return record
        Logger.info(
            "Adding Spark exit payment '$txid' that arrived after the exit ended to its leftover",
            context = TAG
        )
        val swept = sweep?.takeIf { it.isConfirmed && it.txid != null }
            ?.let { record.sweptLeftover + SparkExitRecord.SweptLeftover(requireNotNull(it.txid), it.valueSats) }
        val payment = arrived.map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) }
        return record.copy(
            leftover = if (swept != null) payment else (record.leftover + payment).distinct(),
            sweptLeftover = swept ?: record.sweptLeftover,
            leftoverSweep = null,
            fundingPaymentTxid = null,
            fundingPaymentSeenAtHeight = null,
            fundingPaymentMissingSince = null,
        )
    }

    private fun withReorgUndone(
        record: SparkExitRecord,
        recovery: RecoverFundsResponse,
        reorged: Set<String>,
    ): SparkExitRecord {
        Logger.warn("Asking again after a reorg undid Spark exit steps '$reorged'", context = TAG)
        // What was concluded from those confirmations, a spent input or a failed fee chain, no longer holds.
        val undone = reorged.toMutableSet()
        while (true) {
            val added = recovery.transactions
                .filter { it.txid !in undone && it.dependsOn.any { parent -> parent in undone } }
                .map { it.txid }
            if (added.isEmpty()) break
            undone += added
        }
        val log = record.broadcastLog.map {
            if (it.generation != record.generation || it.txid !in undone) return@map it
            it.copy(isInputSpent = false, feeChainFailures = 0)
        }
        return record.copy(broadcastLog = log)
    }

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

    private suspend fun broadcast(
        tx: RecoveryTransaction,
        record: SparkExitRecord,
        parentsConfirmed: Boolean,
    ): SparkExitRecord {
        val outcome = chain.broadcast(tx)
        val isInputSpent = parentsConfirmed && outcome is SparkExitBroadcastOutcome.InputsMissing
        val isUnpaid = SparkExitBroadcastClassifier.isUnpaid(outcome)
        val isStuck = parentsConfirmed && (isUnpaid || outcome is SparkExitBroadcastOutcome.InputsMissing)
        Logger.info("Broadcast Spark exit '${tx.kind}' '${tx.txid}': '${outcome.summary}'", context = TAG)
        val isAccepted = outcome == SparkExitBroadcastOutcome.Accepted
        // An unpaid step is a broken fee chain, and a fee under the network's floor an underpriced exit: neither is a
        // rejection a rebuild could fix.
        val isRejected = outcome is SparkExitBroadcastOutcome.Rejected && !isUnpaid &&
            !SparkExitBroadcastClassifier.isFeeTooLow(outcome)
        val rejection = if (isRejected) 1 else 0
        val existing = record.broadcastLog.firstOrNull { it.generation == record.generation && it.txid == tx.txid }
        val entry = existing?.copy(
            attempts = existing.attempts + 1,
            rejections = existing.rejections + rejection,
            lastAttemptAt = nowMillis(),
            lastOutcome = outcome.summary,
            isAccepted = isAccepted,
            isInputSpent = existing.isInputSpent || isInputSpent,
            feeChainFailures = if (isStuck) existing.feeChainFailures + 1 else 0,
            acceptedAtHeight = if (isAccepted) existing.acceptedAtHeight ?: tip else null,
        ) ?: SparkExitRecord.BroadcastAttempt(
            generation = record.generation,
            txid = tx.txid,
            attempts = 1,
            rejections = rejection,
            lastAttemptAt = nowMillis(),
            lastOutcome = outcome.summary,
            isAccepted = isAccepted,
            isInputSpent = isInputSpent,
            feeChainFailures = if (isStuck) 1 else 0,
            acceptedAtHeight = if (isAccepted) tip else null,
        )
        val log = if (existing == null) {
            record.broadcastLog + entry
        } else {
            record.broadcastLog.map { if (it === existing) entry else it }
        }
        return record.copy(broadcastLog = log)
    }
}

/**
 * What the progress screen shows once rebuilding over a broken chain stopped moving the exit: the last answer of the
 * step that keeps failing, else of any step of the generation, else empty. Null while the exit moves or still rebuilds.
 */
val SparkExitRecord.surfacedRejection: String?
    get() {
        if (!isStalled) return null
        val refused = broadcastLog.lastOrNull { it.generation == generation && !it.isAccepted }
        val last = broadcastLog.lastOrNull { it.generation == generation }
        return (stuckStep ?: refused ?: last)?.lastOutcome.orEmpty()
    }

private val RecoveryTransaction.isCooperativeUnverified: Boolean
    get() = status == ExitTransactionStatus.Unverified && kind == RecoveryTxKind.COOPERATIVE

/**
 * A step of this generation went unpaid too many times in a row. Without the SDK nothing rebuilds it and it counts no
 * rejection, so the exit is not keeping up: the deadline alarm must be left to fire.
 */
val SparkExitRecord.isFeeChainStuck: Boolean
    get() = broadcastLog.any {
        it.generation == generation && it.feeChainFailures >= SparkExitDriver.FEE_CHAIN_FAILURES_TO_REBUILD
    }

/** Transaction ids of what the exit's funding became, which the SDK returns as hex only for the CPFP children. */
object SparkExitTxids {
    fun cpfpChildTxids(recovery: RecoverFundsResponse): List<String> =
        recovery.transactions.mapNotNull { tx ->
            tx.cpfpTxHex?.let { runCatching { SignedTransactionId.fromHex(it) }.getOrNull() }
        }

    /** The CPFP children and the fan-out: their outputs at the funding address are the exit's own, not new funding. */
    fun fundingTxids(recovery: RecoverFundsResponse): List<String> =
        cpfpChildTxids(recovery) + recovery.transactions.filter { it.kind == RecoveryTxKind.FAN_OUT }.map { it.txid }
}

/** How the exit's CPFP children chain: each spends its node's anchor and the change of the child before it. */
object SparkExitFeeChain {
    /** Each step's CPFP child txid, by the step's txid. */
    fun children(recovery: RecoverFundsResponse): Map<String, String> =
        recovery.transactions.mapNotNull { tx ->
            val child = tx.cpfpTxHex?.let { runCatching { SignedTransactionId.fromHex(it) }.getOrNull() }
            child?.let { tx.txid to it }
        }.toMap()

    /** The txids each CPFP child spends outputs of, by the child's txid. */
    fun spends(recovery: RecoverFundsResponse): Map<String, Set<String>> =
        recovery.transactions.mapNotNull { tx ->
            val hex = tx.cpfpTxHex ?: return@mapNotNull null
            val child = runCatching { SignedTransactionId.fromHex(hex) }.getOrNull() ?: return@mapNotNull null
            val inputs = SparkExitTxInputs.decode(hex) ?: return@mapNotNull null
            child to inputs.map { it.prevTxid }.toSet()
        }.toMap()

    /** [dead] and every child that spends one of their outputs, directly or through other children. */
    fun withDescendants(spends: Map<String, Set<String>>, dead: Set<String>): Set<String> {
        val all = dead.toMutableSet()
        while (true) {
            val added = spends.filter { (child, inputs) -> child !in all && inputs.any { it in all } }.keys
            if (added.isEmpty()) return all
            all += added
        }
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

/** The outputs of a raw transaction, for what an exit paid to savings. */
object SparkExitTxOutputs {
    class Output(val valueSats: ULong, val script: ByteArray)

    /** The outputs, or null when [hex] is not a transaction. */
    fun decode(hex: String): List<Output>? = runCatching {
        val bytes = hex.hexToByteArray()
        var offset = 0
        fun take(length: Int): ByteArray {
            require(length >= 0 && offset + length <= bytes.size)
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
        fun le(
            length: Int
        ): Long = take(length).foldIndexed(0L) { i, sum, b -> sum or ((b.toLong() and 0xff) shl (8 * i)) }
        fun compactSize(): Int = when (val prefix = le(1).toInt()) {
            0xfd -> le(2).toInt()
            0xfe -> le(4).toInt()
            0xff -> error("Too many items")
            else -> prefix
        }
        take(4)
        if (bytes[offset] == 0.toByte()) take(2)
        repeat(compactSize()) {
            take(36)
            take(compactSize())
            take(4)
        }
        List(compactSize()) { Output(le(8).toULong(), take(compactSize())) }
    }.getOrNull()
}

/**
 * The valid generation stays the exit's over a rebuild it does not adopt, running again and sent every tick. Funding
 * adopted for the rebuild, such as a fee bump's or an unverified rebuild's ask, and a bumped rate stay the exit's for
 * the next rebuild.
 */
private fun SparkExitRecord.keepingValidGeneration(): SparkExitRecord =
    copy(phase = SparkExitRecord.Phase.Running, isRebuilding = false, isUnverifiedAsk = false)

/**
 * The current generation did not diverge and recovers one of [leafIds]: its signed steps for them would go with a new
 * generation that leaves them waiting.
 */
private fun SparkExitRecord.keepsValidGenerationOver(leafIds: List<String>): Boolean =
    !isRedoRebuild && recovery?.leaves?.any { it.leafId in leafIds } == true
