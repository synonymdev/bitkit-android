package to.bitkit.services

import com.synonym.bitkitcore.OnchainActivity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.Event
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PrimaryNode
import to.bitkit.models.WalletScope
import to.bitkit.models.safe
import to.bitkit.repositories.OnchainSendAttemptStore
import to.bitkit.repositories.OnchainSendNotDispatchedError
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.utils.ServiceError
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class PrimaryWalletService @Inject constructor(
    private val nodeService: PrimaryWalletNodeService,
    private val store: PrimaryWalletOperationStore,
    private val onchainSendAttemptStore: OnchainSendAttemptStore,
    private val coreService: CoreService,
    private val settingsStore: SettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        /** Minimum confirmed deposit before automatic channel funding. */
        internal const val MINIMUM_FUNDING_SATS = 25_000uL

        /** Maximum estimated funding fee as a share of the confirmed deposit, in percent. */
        private const val MAXIMUM_FUNDING_FEE_PERCENT = 5uL

        /** Conservative transaction overhead including the shared funding input for a splice. */
        private const val FUNDING_BASE_VBYTES = 300uL

        /** Conservative allowance for each selected wallet input. */
        private const val FUNDING_INPUT_VBYTES = 300uL

        /** Wait for native evidence without authorizing another dispatch when it does not arrive. */
        private val SEND_EVIDENCE_TIMEOUT = 45.seconds
    }

    private val operationMutex = Mutex()

    suspend fun currentOperation(): PrimaryWalletOperation? = withContext(ioDispatcher) {
        store.load(nodeService.walletIndex)
    }

    suspend fun isFundingTransaction(txid: String, walletId: String): Boolean = withContext(ioDispatcher) {
        store.isFundingTransaction(nodeService.walletIndex, walletId, txid)
    }

    suspend fun verifiedPaymentConfirmation(activity: OnchainActivity): ConfirmationStatus? =
        withContext(ioDispatcher) { nodeService.verifiedPaymentConfirmation(activity) }

    suspend fun <T> withWalletOperation(allowPending: Boolean = false, block: suspend () -> T): T =
        withContext(ioDispatcher) {
            operationMutex.withLock {
                val operation = store.load(nodeService.walletIndex)
                if (!allowPending && operation?.blocksWallet == true) throw PrimaryWalletPendingError(operation)
                block()
            }
        }

    suspend fun fundIfNeeded(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            operationMutex.withLock {
                val snapshot = nodeService.snapshot() ?: return@withLock
                val pending = reconcile(snapshot)
                if (pending?.blocksWallet == true) throw PrimaryWalletPendingError(pending)
                if (settingsStore.data.first().primaryFundingPaused) return@withLock
                if (!snapshot.isConnected || snapshot.hasPendingFunding) return@withLock
                if (onchainSendAttemptStore.currentDurable()?.blocksNextSend == true) return@withLock
                val plan = fundingPlan(snapshot) ?: return@withLock
                val operation = newOperation(snapshot, plan.channel, plan.amountSats, plan.kind)
                dispatch(operation, snapshot.primary)
            }
        }
    }

    suspend fun closeConnection(channel: ChannelDetails, close: suspend () -> Unit) = withContext(ioDispatcher) {
        operationMutex.withLock {
            val settings = settingsStore.data.first()
            val primaryId = settings.primaryNodeUri.takeIf { it.isNotBlank() }?.let(PrimaryNode::parse)?.nodeId
            if (channel.counterpartyNodeId != primaryId) {
                close()
                return@withLock
            }
            settingsStore.update { it.copy(primaryFundingPaused = true) }
            runSuspendCatching { close() }.onFailure { error ->
                if (error is ServiceError.NodeNotStarted) {
                    withContext(NonCancellable) {
                        settingsStore.update {
                            if (it.primaryNodeUri == settings.primaryNodeUri) {
                                it.copy(primaryFundingPaused = settings.primaryFundingPaused)
                            } else {
                                it
                            }
                        }
                    }
                }
            }.getOrThrow()
        }
    }

    suspend fun maxSpliceOutSats(feeRate: ULong? = null): ULong = withContext(ioDispatcher) {
        if (store.load(nodeService.walletIndex)?.blocksWallet == true) return@withContext 0uL
        val snapshot = nodeService.snapshot() ?: return@withContext 0uL
        if (!snapshot.isConnected || snapshot.hasPendingFunding) return@withContext 0uL
        val channel = usableHome(snapshot) ?: return@withContext 0uL
        (channel.outboundCapacityMsat / 1_000uL).safe() - spliceOutFee(feeRate ?: snapshot.fundingFeeRate).safe()
    }

    suspend fun estimateSpliceOutFee(amountSats: ULong, feeRate: ULong): ULong? = withContext(ioDispatcher) {
        if (store.load(nodeService.walletIndex)?.blocksWallet == true) return@withContext null
        val snapshot = nodeService.snapshot() ?: return@withContext null
        if (!snapshot.isConnected || snapshot.hasPendingFunding) return@withContext null
        val channel = usableHome(snapshot) ?: return@withContext null
        val fee = spliceOutFee(feeRate)
        fee.takeIf { amountSats.safe() + fee.safe() <= channel.outboundCapacityMsat / 1_000uL }
    }

    suspend fun sendOnchain(
        address: String,
        amountSats: ULong,
        feeRate: ULong,
        tags: List<String> = emptyList(),
        beforeSendAttempt: suspend () -> Unit = {},
    ): Result<OnchainSendOutcome> = withContext(ioDispatcher) {
        runSuspendCatching {
            operationMutex.withLock {
                val snapshot = nodeService.snapshot() ?: throw PrimaryWalletError.NotReady()
                val pending = reconcile(snapshot)
                if (pending?.blocksWallet == true) throw PrimaryWalletPendingError(pending)
                if (!snapshot.isConnected || snapshot.hasPendingFunding) {
                    throw PrimaryWalletError.NotReady()
                }
                if (onchainSendAttemptStore.currentDurable()?.blocksNextSend == true) {
                    throw PrimaryWalletError.PaymentPending()
                }
                val channel = usableHome(snapshot) ?: throw PrimaryWalletError.NotReady()
                val fee = spliceOutFee(feeRate)
                if (amountSats == 0uL || amountSats.safe() + fee.safe() > channel.outboundCapacityMsat / 1_000uL) {
                    throw PrimaryWalletError.InsufficientFunds()
                }
                val operation = newOperation(
                    snapshot,
                    channel,
                    amountSats,
                    PrimaryWalletOperationKind.SpliceOut,
                    address,
                    feeRate = feeRate,
                ).copy(tags = tags.distinct())
                dispatch(operation, snapshot.primary, beforeSendAttempt)
                awaitSendEvidence(operation)
            }
        }
    }

    suspend fun handleEvent(event: Event): Unit = withContext(ioDispatcher) {
        val walletIndex = nodeService.walletIndex
        var current = store.load(walletIndex) ?: return@withContext
        if (!current.blocksWallet || current.stage == PrimaryWalletOperationStage.Prepared) return@withContext
        if (current.kind == PrimaryWalletOperationKind.Open && current.userChannelId == null) {
            nodeService.snapshot()?.let { current = bindOpeningChannel(current, it) }
        }
        var updated = operationAfterEvent(current, event) ?: return@withContext
        if (updated.stage == PrimaryWalletOperationStage.FailureAwaitingAck) {
            val nativeState = nodeService.failureAcknowledgementState(walletIndex)
            updated = updated.copy(failureNodeInstanceId = nativeState.nodeInstanceId)
        }
        val persisted = store.update(walletIndex) { if (it?.id == current.id) updated else requireNotNull(it) }
        completePaymentFollowup(persisted)
    }

    suspend fun onEventAcknowledged(event: Event): Unit = withContext(ioDispatcher) {
        val fingerprint = primaryFailureEventFingerprint(event) ?: return@withContext
        val operation = store.load(nodeService.walletIndex) ?: return@withContext
        if (operation.stage != PrimaryWalletOperationStage.FailureAwaitingAck ||
            operation.failureEventFingerprint != fingerprint
        ) {
            return@withContext
        }
        store.update(operation.walletIndex) {
            if (it?.id == operation.id && it.stage == PrimaryWalletOperationStage.FailureAwaitingAck &&
                it.failureEventFingerprint == fingerprint
            ) {
                it.afterTerminalAcknowledgement()
            } else {
                requireNotNull(it)
            }
        }
    }

    private suspend fun dispatch(
        operation: PrimaryWalletOperation,
        primary: PrimaryNode,
        beforeSendAttempt: suspend () -> Unit = {},
    ) {
        store.update(operation.walletIndex) { previous ->
            if (previous?.blocksWallet == true) throw PrimaryWalletPendingError(previous)
            operation
        }
        runSuspendCatching { beforeSendAttempt() }.onFailure { error ->
            withContext(NonCancellable) {
                updateOperation(
                    operation
                ) { it.copy(stage = PrimaryWalletOperationStage.Failed, failure = error.message) }
            }
        }.getOrThrow()
        withContext(NonCancellable) {
            updateOperation(operation) { it.copy(stage = PrimaryWalletOperationStage.Dispatched) }
            runSuspendCatching { nodeService.dispatch(operation, primary) }
                .onSuccess { userChannelId -> updateOperation(operation) { it.copy(userChannelId = userChannelId) } }
                .onFailure { error ->
                    updateOperation(operation) {
                        it.copy(
                            failure = error.message,
                            stage = if (error is PrimaryWalletDispatchRejected) {
                                PrimaryWalletOperationStage.Failed
                            } else {
                                it.stage
                            },
                        )
                    }
                }
                .getOrElse {
                    if (it is PrimaryWalletDispatchRejected && operation.kind == PrimaryWalletOperationKind.SpliceOut) {
                        throw OnchainSendNotDispatchedError(it)
                    }
                    throw it
                }
        }
    }

    private suspend fun awaitSendEvidence(operation: PrimaryWalletOperation): OnchainSendOutcome {
        val observed = withTimeoutOrNull(SEND_EVIDENCE_TIMEOUT) {
            store.version.map { store.load(operation.walletIndex) }.first {
                it?.id == operation.id && (it.fundingTxid != null || it.stage == PrimaryWalletOperationStage.Failed)
            }
        } ?: throw PrimaryWalletPendingError(requireNotNull(store.load(operation.walletIndex)))
        if (observed.stage == PrimaryWalletOperationStage.Failed) {
            throw OnchainSendNotDispatchedError(PrimaryWalletError.PaymentFailed())
        }
        val txid = requireNotNull(observed.fundingTxid)
        return if (completePaymentFollowup(observed)) {
            OnchainSendOutcome.Accepted(txid, observed.amountSats)
        } else {
            OnchainSendOutcome.Unknown(txid)
        }
    }

    private suspend fun reconcile(snapshot: PrimaryWalletSnapshot): PrimaryWalletOperation? {
        var operation = store.load(snapshot.walletIndex) ?: return null
        if (!operation.blocksWallet) return operation
        operation = bindOpeningChannel(operation, snapshot)
        if (operation.stage == PrimaryWalletOperationStage.FailureAwaitingAck) {
            return reconcileTerminalAcknowledgement(operation)
        }
        val followupComplete = completePaymentFollowup(operation)
        if (operation.stage == PrimaryWalletOperationStage.Closed ||
            (operation.stage == PrimaryWalletOperationStage.Ready && followupComplete)
        ) {
            return store.load(snapshot.walletIndex)
        }
        if (operation.stage == PrimaryWalletOperationStage.Prepared) {
            return updateOperation(operation) {
                it.copy(
                    stage = PrimaryWalletOperationStage.Failed,
                    failure = "The request was interrupted before dispatch."
                )
            }
        }
        val channel = snapshot.channels.firstOrNull {
            it.userChannelId == operation.userChannelId && it.counterpartyNodeId == operation.primaryNodeId
        } ?: return operation
        val txid = channel.fundingTxo?.txid ?: return operation
        if (txid == operation.previousFundingTxid) return operation
        if (operation.fundingTxid != null && txid != operation.fundingTxid) return operation
        val reconciled = updateOperation(operation) {
            it.copy(
                fundingTxid = txid,
                stage = if (channel.isChannelReady && !snapshot.hasPendingFunding) {
                    PrimaryWalletOperationStage.Ready
                } else {
                    PrimaryWalletOperationStage.Submitted
                },
            )
        }
        completePaymentFollowup(reconciled)
        return store.load(snapshot.walletIndex)
    }

    private suspend fun reconcileTerminalAcknowledgement(
        operation: PrimaryWalletOperation,
    ): PrimaryWalletOperation {
        val nativeState = nodeService.failureAcknowledgementState(operation.walletIndex)
        // A live native queue can advance before its acknowledgement is persisted. Only a reload proves durability.
        if (operation.failureNodeInstanceId == null ||
            operation.failureNodeInstanceId == nativeState.nodeInstanceId ||
            operation.failureEventFingerprint == nativeState.pendingEventFingerprint
        ) {
            return operation
        }
        return store.update(operation.walletIndex) {
            if (it?.id == operation.id && it.stage == PrimaryWalletOperationStage.FailureAwaitingAck &&
                it.failureEventFingerprint == operation.failureEventFingerprint &&
                it.failureNodeInstanceId == operation.failureNodeInstanceId
            ) {
                it.afterTerminalAcknowledgement()
            } else {
                requireNotNull(it)
            }
        }
    }

    private suspend fun bindOpeningChannel(
        operation: PrimaryWalletOperation,
        snapshot: PrimaryWalletSnapshot,
    ): PrimaryWalletOperation {
        if (operation.kind != PrimaryWalletOperationKind.Open || operation.userChannelId != null ||
            operation.stage == PrimaryWalletOperationStage.Prepared ||
            operation.primaryNodeId != snapshot.primary.nodeId
        ) {
            return operation
        }
        // Open is dispatched only with no existing primary channels. An inbound channel cannot match this intent.
        val channel = snapshot.channels.singleOrNull()?.takeIf {
            it.isOutbound && it.counterpartyNodeId == operation.primaryNodeId &&
                it.channelValueSats == operation.amountSats
        } ?: return operation
        return updateOperation(operation) { it.copy(userChannelId = channel.userChannelId) }
    }

    private suspend fun completePaymentFollowup(operation: PrimaryWalletOperation): Boolean {
        if (operation.kind != PrimaryWalletOperationKind.SpliceOut || operation.fundingTxid == null) return false
        if (operation.localFollowupComplete) return true
        if (!nodeService.matchesPayment(operation)) return false
        val fee = nodeService.paymentFee(operation) ?: return false
        val address = requireNotNull(operation.address)
        coreService.activity.createSentOnchainActivityFromSendResult(
            txid = operation.fundingTxid,
            address = address,
            amount = operation.amountSats,
            fee = fee,
            feeRate = operation.feeRate,
            isTransfer = false,
            channelId = null,
            walletId = operation.walletId,
        )
        val persisted = coreService.activity.getOnchainActivityByTxId(operation.fundingTxid, operation.walletId)
        if (persisted?.address != address || persisted.value != operation.amountSats || persisted.fee != fee) {
            return false
        }
        if (operation.tags.isNotEmpty()) {
            coreService.activity.appendTags(persisted.id, operation.tags, operation.walletId)
            val tags = coreService.activity.tags(persisted.id, operation.walletId)
            if (!tags.containsAll(operation.tags)) return false
        }
        updateOperation(operation) { it.copy(localFollowupComplete = true) }
        return true
    }

    private suspend fun updateOperation(
        expected: PrimaryWalletOperation,
        transform: (PrimaryWalletOperation) -> PrimaryWalletOperation,
    ): PrimaryWalletOperation = store.update(expected.walletIndex) {
        check(it?.id == expected.id) { "The primary wallet operation changed." }
        transform(requireNotNull(it))
    }

    private fun newOperation(
        snapshot: PrimaryWalletSnapshot,
        channel: ChannelDetails?,
        amountSats: ULong,
        kind: PrimaryWalletOperationKind,
        address: String? = null,
        feeRate: ULong = snapshot.fundingFeeRate,
    ) = PrimaryWalletOperation(
        id = UUID.randomUUID().toString(),
        walletIndex = snapshot.walletIndex,
        primaryNodeId = snapshot.primary.nodeId,
        kind = kind,
        amountSats = amountSats,
        walletId = WalletScope.default,
        feeRate = feeRate,
        userChannelId = channel?.userChannelId ?: newUserChannelId(),
        previousFundingTxid = channel?.fundingTxo?.txid,
        address = address,
    )

    private fun usableHome(snapshot: PrimaryWalletSnapshot): ChannelDetails? =
        snapshot.channels.singleOrNull()?.takeIf { it.isChannelReady && it.isUsable }

    private fun newUserChannelId(): String = UUID.randomUUID().toString().replace("-", "").toBigInteger(16).toString()

    private fun spliceOutFee(feeRate: ULong): ULong = feeRate.safe() * FUNDING_BASE_VBYTES.safe()

    internal fun fundingPlan(snapshot: PrimaryWalletSnapshot): PrimaryFundingPlan? {
        if (snapshot.confirmedSats < MINIMUM_FUNDING_SATS || snapshot.confirmedInputCount <= 0) return null
        val channel = if (snapshot.channels.isEmpty()) null else usableHome(snapshot) ?: return null
        val virtualBytes = FUNDING_BASE_VBYTES.safe() +
            (snapshot.confirmedInputCount.toULong().safe() * FUNDING_INPUT_VBYTES.safe()).safe()
        val fee = snapshot.fundingFeeRate.safe() * virtualBytes.safe()
        val maximumFee = (snapshot.confirmedSats / 100uL).safe() * MAXIMUM_FUNDING_FEE_PERCENT.safe()
        if (fee == 0uL || fee > maximumFee) return null
        return PrimaryFundingPlan(
            channel = channel,
            amountSats = snapshot.confirmedSats.safe() - fee.safe(),
            kind = if (channel == null) PrimaryWalletOperationKind.Open else PrimaryWalletOperationKind.SpliceIn,
        )
    }
}

internal data class PrimaryFundingPlan(
    val channel: ChannelDetails?,
    val amountSats: ULong,
    val kind: PrimaryWalletOperationKind,
)

internal fun operationAfterEvent(
    operation: PrimaryWalletOperation,
    event: Event,
): PrimaryWalletOperation? = when (event) {
    is Event.ChannelPending if operation.kind == PrimaryWalletOperationKind.Open &&
        operation.matches(event.userChannelId, event.counterpartyNodeId) -> operation.copy(
        fundingTxid = event.fundingTxo.txid,
        stage = PrimaryWalletOperationStage.Submitted,
    )
    is Event.SplicePending if operation.kind != PrimaryWalletOperationKind.Open &&
        operation.matches(event.userChannelId, event.counterpartyNodeId) &&
        event.newFundingTxo.txid != operation.previousFundingTxid -> operation.copy(
        fundingTxid = event.newFundingTxo.txid,
        stage = PrimaryWalletOperationStage.Submitted,
    )
    is Event.ChannelReady if operation.matches(event.userChannelId, event.counterpartyNodeId) &&
        event.fundingTxo != null && event.fundingTxo?.txid != operation.previousFundingTxid &&
        (operation.fundingTxid == null || operation.fundingTxid == event.fundingTxo?.txid) -> operation.copy(
        fundingTxid = event.fundingTxo?.txid,
        stage = PrimaryWalletOperationStage.Ready,
    )
    is Event.SpliceFailed if operation.matches(event.userChannelId, event.counterpartyNodeId) -> {
        if (operation.fundingTxid == null && event.abandonedFundingTxo == null) {
            operation.copy(
                stage = PrimaryWalletOperationStage.FailureAwaitingAck,
                failure = "The channel splice failed.",
                failureEventFingerprint = primaryFailureEventFingerprint(event),
            )
        } else {
            operation.copy(failure = "The channel splice did not finish. Its transaction still needs to be reconciled.")
        }
    }
    is Event.ChannelClosed if operation.matches(event.userChannelId, event.counterpartyNodeId) ->
        operation.copy(
            stage = PrimaryWalletOperationStage.FailureAwaitingAck,
            failure = "The primary channel closed.",
            failureEventFingerprint = primaryFailureEventFingerprint(event),
        )
    else -> null
}

private fun PrimaryWalletOperation.matches(userChannelId: String, nodeId: String?): Boolean =
    this.userChannelId == userChannelId && primaryNodeId == nodeId

private fun PrimaryWalletOperation.afterTerminalAcknowledgement(): PrimaryWalletOperation = copy(
    stage = if (failureEventFingerprint?.startsWith("channel-closed:") == true) {
        PrimaryWalletOperationStage.Closed
    } else {
        PrimaryWalletOperationStage.Failed
    },
)

internal fun primaryFailureEventFingerprint(event: Event): String? = when (event) {
    is Event.SpliceFailed -> listOf(
        "splice-failed",
        event.channelId,
        event.userChannelId,
        event.counterpartyNodeId,
        event.abandonedFundingTxo?.txid,
        event.abandonedFundingTxo?.vout,
    ).joinToString(":")
    is Event.ChannelClosed -> listOf(
        "channel-closed",
        event.channelId,
        event.userChannelId,
        event.counterpartyNodeId,
    ).joinToString(":")
    else -> null
}
