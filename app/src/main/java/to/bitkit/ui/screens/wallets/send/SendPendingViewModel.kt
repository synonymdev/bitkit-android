package to.bitkit.ui.screens.wallets.send

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.SyncType
import to.bitkit.ext.rawId
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.WalletScope
import to.bitkit.models.safe
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.OnchainOriginalPaidError
import to.bitkit.repositories.OnchainOriginalStatusUnavailableError
import to.bitkit.repositories.OnchainPreparedReceipt
import to.bitkit.repositories.OnchainRecoveryFeeRate
import to.bitkit.repositories.OnchainSendAbandonPendingError
import to.bitkit.repositories.OnchainSendAbandonSyncError
import to.bitkit.repositories.OnchainSendAttempt
import to.bitkit.repositories.OnchainSendCancelUnderbidsError
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.PaykitPaymentProofRepo
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.SanityWarning
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class SendPendingViewModel @Inject constructor(
    private val pendingPaymentRepo: PendingPaymentRepo,
    private val activityRepo: ActivityRepo,
    private val lightningRepo: LightningRepo,
    private val paykitPaymentProofRepo: PaykitPaymentProofRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "SendPendingViewModel"
        private val ABANDON_CHECK_INTERVAL = 30.seconds
    }

    private val _uiState = MutableStateFlow(SendPendingUiState())
    val uiState = _uiState.asStateFlow()

    private var recoveryApproval: CompletableDeferred<Boolean>? = null
    private var recoveryPinOpened = false
    private var isInitialized = false

    fun init(paymentHash: String, amount: Long) {
        if (isInitialized) return
        isInitialized = true
        pendingPaymentRepo.setActiveHash(paymentHash)
        _uiState.update { it.copy(amount = amount) }
        pendingPaymentRepo.consumeResolution(paymentHash)?.let { resolution ->
            _uiState.update { it.copy(resolution = resolution) }
        }
        findActivity(paymentHash)
        observeResolution(paymentHash)
    }

    // Local activity is only a Details target; it cannot resolve or acknowledge an on-chain send.
    fun initOnchain(txid: String?, amount: Long, walletId: String = WalletScope.default) {
        if (isInitialized) return
        isInitialized = true
        _uiState.update { it.copy(amount = amount) }
        viewModelScope.launch {
            runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.onSuccess { attempt ->
                if (attempt?.walletId == walletId &&
                    (attempt.txid.equals(txid, true) || attempt.candidateTxids.any { it.equals(txid, true) })
                ) {
                    _uiState.update { it.copy(amount = attempt.amountSats.toLong()) }
                }
                val matchesOriginalWallet = walletId == WalletScope.default && attempt?.walletId == walletId
                val hasOriginalReceipt = !attempt?.originalInputs.isNullOrEmpty() &&
                    attempt.candidateTxids.any { it.equals(txid, true) }
                if (matchesOriginalWallet && hasOriginalReceipt) {
                    if (attempt.isUnresolved) {
                        // A recorded release or Cancel payment may have reached the network, so it holds the original
                        // like a sent cancellation, even before the wallet knows it, and a replacement must outbid it.
                        _uiState.update {
                            it.copy(
                                recoveryAttempt = attempt,
                                cancellationTxid = attempt.cancellationTxid ?: attempt.releaseTxid,
                                hasUnknownCancellation = attempt.hasSentCancellation,
                                cancellationFeeRate = listOfNotNull(
                                    attempt.releaseFeeRateSatsPerVByte,
                                    attempt.cancellationFeeRateSatsPerVByte,
                                ).maxOrNull(),
                            )
                        }
                        observeCancellation(attempt)
                        loadNormalFeeRate()
                    }
                    observeOriginalCompletion(attempt)
                    offerAbandonIfRequestEnded(attempt)
                }
            }
        }
        if (txid == null || !txid.matches(Regex("[0-9a-fA-F]{64}"))) return
        viewModelScope.launch {
            activityRepo.findActivityByPaymentId(txid, ActivityFilter.ONCHAIN, PaymentType.SENT, true, walletId)
                .onSuccess { activity -> _uiState.update { it.copy(activityId = activity.rawId()) } }
        }
    }

    private fun observeOriginalCompletion(original: OnchainSendAttempt) {
        // Shop completion has its own proof resolution route.
        if (original.requestId != null) return
        if (original.isTransfer && (original.orderId == null || original.transferContext == null)) return
        viewModelScope.launch {
            lightningRepo.onchainSendAttemptUpdates.collect {
                runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.onSuccess { current ->
                    if (current == null || !current.hasPositiveEvidence || !current.localFollowupComplete) {
                        return@onSuccess
                    }
                    if (current.matchesOriginalRoute(original)) {
                        _uiState.update {
                            it.copy(
                                recoveredTxid = current.txid.takeUnless { current.isTransfer },
                                recoveredTransfer = current.takeIf { current.isTransfer },
                                currentTxid = current.txid,
                                recoveryAttempt = null,
                                recoveryError = null,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeCancellation(original: OnchainSendAttempt) {
        viewModelScope.launch { showKnownCancellation(original, afterSync = true) }
        viewModelScope.launch {
            // Each wallet sync shows whether the wallet holds the cancellation, or never saw one that was sent
            lightningRepo.nodeEvents
                .filter { it is Event.SyncCompleted && it.syncType == SyncType.ONCHAIN_WALLET }
                .collect { showKnownCancellation(original, afterSync = true) }
        }
        viewModelScope.launch {
            lightningRepo.onchainSendAttemptUpdates.collect {
                runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.onSuccess { current ->
                    // Only a final cancellation, including an expired payment's release, or a wipe removes a
                    // dispatched unresolved guard.
                    if (current?.attemptId != original.attemptId) {
                        _uiState.update { it.copy(recoveryAttempt = null, isGuardReleased = true) }
                    }
                }
            }
        }
    }

    private suspend fun showKnownCancellation(original: OnchainSendAttempt, afterSync: Boolean = false) {
        lightningRepo.findOnchainSendCancellation(original).onSuccess { spend ->
            _uiState.update {
                if (spend != null) {
                    it.copy(
                        cancellationTxid = spend.txid,
                        isCancellationFinal = spend.isFinal,
                        isCancellationHeld = !spend.isFinal,
                        isCancellationUnheld = false,
                        cancellationConfirmations = spend.confirmations,
                    )
                } else {
                    // A server refusal other than non-final reads as unknown, so a sent cancellation the synced
                    // wallet still lacks may never have reached the network
                    it.copy(
                        isCancellationHeld = false,
                        isCancellationUnheld = it.isCancellationUnheld || (afterSync && it.cancellationTxid != null),
                    )
                }
            }
        }
    }

    private fun loadNormalFeeRate() {
        viewModelScope.launch {
            lightningRepo.getFeeRateForSpeed(TransactionSpeed.Medium)
                .onSuccess { rate -> _uiState.update { it.copy(normalFeeRate = rate) } }
        }
    }

    private fun OnchainSendAttempt.matchesOriginalRoute(original: OnchainSendAttempt): Boolean {
        val sameOperation = attemptId == original.attemptId && amountSats == original.amountSats &&
            address == original.address && originalInputs == original.originalInputs
        val sameWallet = walletId == original.walletId && walletIndex == original.walletIndex
        val sameCandidateFamily = candidateTxids.any { it.equals(original.txid, true) } &&
            candidateTxids.any { it.equals(txid, true) }
        val samePurpose = requestId == null && isTransfer == original.isTransfer &&
            orderId == original.orderId && transferContext == original.transferContext
        return sameOperation && sameWallet && sameCandidateFamily && samePurpose
    }

    fun retryOriginal(
        feeRateSatsPerVByte: ULong,
        retry: suspend (OnchainSendAttempt, ULong) -> Result<OnchainSendOutcome>,
    ) {
        val original = _uiState.value.recoveryAttempt ?: return
        if (_uiState.value.isRecovering || !_uiState.value.canRetry) return
        if (!OnchainRecoveryFeeRate.isValid(feeRateSatsPerVByte)) {
            _uiState.update { it.copy(invalidFeeRate = true, recoveryError = "invalid fee rate") }
            return
        }
        _uiState.update {
            it.copy(
                isRecovering = true,
                recoveryError = null,
                invalidFeeRate = false,
                cancellationFailed = false,
                speedUpFailed = false,
                cancelFeeTooLowSats = null,
                isOriginalPaid = false,
                isOriginalStatusUnknown = false,
                abandonError = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = runSuspendCatching { retry(original, feeRateSatsPerVByte).getOrThrow() }
                result.onSuccess { outcome ->
                    val completed = runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.getOrNull()
                        ?.takeIf {
                            it.hasPositiveEvidence && it.localFollowupComplete && it.matchesOriginalRoute(original)
                        }
                    _uiState.update {
                        if (it.recoveredTxid != null || it.recoveredTransfer != null) return@update it
                        it.copy(
                            recoveredTxid = completed?.txid.takeUnless { original.isTransfer },
                            recoveredTransfer = completed?.takeIf { original.isTransfer },
                            recoveryAttempt = if (completed != null) null else it.recoveryAttempt,
                            recoveryError = (outcome as? OnchainSendOutcome.Rejected)?.reason,
                            currentTxid = completed?.txid ?: outcome.txid,
                        )
                    }
                }.onFailure { error ->
                    _uiState.update {
                        if (it.recoveredTxid == null && it.recoveredTransfer == null) {
                            it.copy(
                                recoveryError = error.message,
                                isOriginalPaid = error is OnchainOriginalPaidError,
                                isOriginalStatusUnknown = error is OnchainOriginalStatusUnavailableError,
                            )
                        } else {
                            it
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(isRecovering = false) }
            }
        }
    }

    fun cancelOriginal(
        feeRateSatsPerVByte: ULong,
        cancel: suspend (OnchainSendAttempt, ULong) -> Result<OnchainSendOutcome>,
    ) {
        val original = _uiState.value.recoveryAttempt ?: return
        if (_uiState.value.isRecovering) return
        if (!OnchainRecoveryFeeRate.isValid(feeRateSatsPerVByte)) {
            _uiState.update { it.copy(invalidFeeRate = true, recoveryError = "invalid fee rate") }
            return
        }
        _uiState.update {
            it.copy(
                isRecovering = true,
                recoveryError = null,
                invalidFeeRate = false,
                cancellationFailed = false,
                speedUpFailed = false,
                cancelFeeTooLowSats = null,
                isOriginalPaid = false,
                isOriginalStatusUnknown = false,
                abandonError = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = runSuspendCatching { cancel(original, feeRateSatsPerVByte).getOrThrow() }
                val outcome = result.getOrNull()
                // Any dispatched cancellation may have been relayed, so a replacement must pay more than it.
                val dispatchedFeeRate = outcome?.let { feeRateSatsPerVByte }
                // Only a higher rate outbids what it replaces, such as a release that added other coins.
                val underbidFeeRate = (result.exceptionOrNull() as? OnchainSendCancelUnderbidsError)?.feeRate
                val underbid = result.exceptionOrNull() as? OnchainSendCancelUnderbidsError
                // An accepted or unknown cancellation may be in a mempool, so it shows as sent and holds the retries
                // back; a refused one leaves the guard unchanged, and another fee rate may still work.
                _uiState.update {
                    val feeRate = listOfNotNull(it.cancellationFeeRate, dispatchedFeeRate, underbidFeeRate).maxOrNull()
                    when (outcome) {
                        is OnchainSendOutcome.Accepted, is OnchainSendOutcome.Unknown -> it.copy(
                            cancellationTxid = outcome.txid,
                            cancellationFeeRate = feeRate,
                            isCancellationUnheld = false,
                            hasUnknownCancellation = it.hasUnknownCancellation ||
                                outcome is OnchainSendOutcome.Unknown,
                        )
                        else -> it.copy(
                            cancellationFailed = underbid == null,
                            cancelFeeTooLowSats = underbid?.feeSats,
                            cancellationFeeRate = feeRate,
                        )
                    }
                }
                // A cancellation the wallet now holds, such as the release, keeps the original's coins, so it is
                // sped up rather than built over again.
                showKnownCancellation(original)
            } finally {
                _uiState.update { it.copy(isRecovering = false) }
            }
        }
    }

    fun speedUpCancellation(
        feeRateSatsPerVByte: ULong,
        speedUp: suspend (OnchainSendAttempt, ULong) -> Result<String>,
    ) {
        val original = _uiState.value.recoveryAttempt ?: return
        if (_uiState.value.isRecovering || !_uiState.value.canSpeedUpCancellation) return
        if (!OnchainRecoveryFeeRate.isValid(feeRateSatsPerVByte)) {
            _uiState.update { it.copy(invalidFeeRate = true, recoveryError = "invalid fee rate") }
            return
        }
        _uiState.update {
            it.copy(
                isRecovering = true,
                recoveryError = null,
                invalidFeeRate = false,
                cancellationFailed = false,
                speedUpFailed = false,
                cancelFeeTooLowSats = null,
                isOriginalPaid = false,
                isOriginalStatusUnknown = false,
                abandonError = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = runSuspendCatching { speedUp(original, feeRateSatsPerVByte).getOrThrow() }
                val underbid = result.exceptionOrNull() as? OnchainSendCancelUnderbidsError
                _uiState.update {
                    val txid = result.getOrNull()
                    val sentFeeRate = feeRateSatsPerVByte.takeIf { txid != null }
                    val feeRate = listOfNotNull(it.cancellationFeeRate, sentFeeRate, underbid?.feeRate).maxOrNull()
                    if (txid != null) {
                        it.copy(cancellationTxid = txid, cancellationFeeRate = feeRate, cancellationConfirmations = 0u)
                    } else {
                        it.copy(
                            speedUpFailed = underbid == null,
                            cancelFeeTooLowSats = underbid?.feeSats,
                            cancellationFeeRate = feeRate,
                        )
                    }
                }
                if (result.isFailure) showKnownCancellation(original)
            } finally {
                _uiState.update { it.copy(isRecovering = false) }
            }
        }
    }

    /** A speed-up shows its fee rate in the dialog the user confirmed, so only the payment's own check remains. */
    suspend fun approveSpeedUp(requiresPin: Boolean, useBiometrics: Boolean) = withContext(Dispatchers.Main.immediate) {
        if (requiresPin) {
            requestRecoveryApproval(if (useBiometrics) RecoveryApproval.Biometrics else RecoveryApproval.Pin)
        }
    }

    // The request can end, or a failed check recover, while Payment Pending stays open.
    private fun offerAbandonIfRequestEnded(attempt: OnchainSendAttempt) {
        if (!attempt.canBeAbandoned) return
        viewModelScope.launch {
            // Transfers do not come back once unavailable, so one check decides a Blocktank funding.
            if (attempt.isBlocktankFunding) {
                val hasEnded = runSuspendCatching { paykitPaymentProofRepo.onchainRequestHasEnded(attempt) }
                    .getOrNull() == true
                if (hasEnded) _uiState.update { it.copy(abandonableAttempt = attempt) }
                return@launch
            }
            var current = attempt
            while (_uiState.value.recoveryAttempt?.attemptId == attempt.attemptId &&
                _uiState.value.abandonableAttempt == null
            ) {
                // A retry from this screen can add a candidate; offer the attempt as it is stored now.
                current = runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.getOrNull()
                    ?.takeIf { it.attemptId == attempt.attemptId } ?: current
                val hasEnded = current.canBeAbandoned &&
                    runSuspendCatching { paykitPaymentProofRepo.onchainRequestHasEnded(current) }.getOrDefault(false)
                if (hasEnded) {
                    _uiState.update { it.copy(abandonableAttempt = current) }
                    return@launch
                }
                delay(ABANDON_CHECK_INTERVAL)
            }
        }
    }

    fun abandonExpired(requiresPin: Boolean, useBiometrics: Boolean) {
        val attempt = _uiState.value.abandonableAttempt?.takeIf { _uiState.value.canAbandon } ?: return
        if (_uiState.value.isAbandoning) return
        _uiState.update { it.copy(isAbandoning = true, abandonError = null) }
        viewModelScope.launch {
            try {
                releaseExpired(attempt, requiresPin, useBiometrics)
            } finally {
                _uiState.update { it.copy(isAbandoning = false) }
            }
        }
    }

    private suspend fun releaseExpired(shown: OnchainSendAttempt, requiresPin: Boolean, useBiometrics: Boolean) {
        // An earlier try may have saved its approval and release since the screen loaded the attempt.
        val attempt = runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.getOrNull()
            ?.takeIf { it.attemptId == shown.attemptId } ?: shown
        var approvedRelease: OnchainPreparedReceipt? = null
        paykitPaymentProofRepo.abandonUnbroadcastOnchainPayment(attempt, lightningRepo) { receipt ->
            withContext(Dispatchers.Main.immediate) {
                requestRecoveryApproval(RecoveryApproval.Release(receipt))
                if (requiresPin) {
                    requestRecoveryApproval(if (useBiometrics) RecoveryApproval.Biometrics else RecoveryApproval.Pin)
                }
            }
            approvedRelease = receipt
        }.onFailure { error ->
            Logger.warn("Could not release on-chain payment '${attempt.txid}'", error, context = TAG)
            val stored = runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }
            // Another path, such as a final cancellation, may have released the guard while this cancel ran.
            if (stored.map { it?.attemptId != shown.attemptId }.getOrDefault(false)) {
                _uiState.update { it.copy(isAbandoning = false, recoveryAttempt = null, isGuardReleased = true) }
                return
            }
            if (error is OnchainSendAbandonPendingError) showKnownCancellation(attempt)
            _uiState.update {
                it.copy(
                    isAbandoning = false,
                    abandonError = when (error) {
                        is CancellationException -> null
                        is OnchainSendAbandonSyncError -> AbandonError.SyncUnavailable
                        is OnchainSendAbandonPendingError -> AbandonError.ReleasePending
                        else -> AbandonError.MayStillConfirm
                    },
                    // A release that was recorded or may have reached the network can still confirm, so the original
                    // is not retried, no other release is sent beside it, and a Cancel payment must outbid it.
                    hasUnknownCancellation = it.hasUnknownCancellation || error is OnchainSendAbandonPendingError ||
                        stored.getOrNull()?.releaseTxid != null,
                    cancellationFeeRate = listOfNotNull(it.cancellationFeeRate, approvedRelease?.feeRateSatsPerVByte)
                        .maxOrNull(),
                )
            }
        }
    }

    suspend fun approveRecoveryFee(
        receipt: OnchainPreparedReceipt,
        warnings: List<SanityWarning>,
        requiresPin: Boolean,
        useBiometrics: Boolean,
        isCancellation: Boolean = false,
    ) = withContext(Dispatchers.Main.immediate) {
        requireNotNull(receipt.miningFeeSats)
        requestRecoveryApproval(RecoveryApproval.Fee(receipt, isCancellation))
        warnings.forEach { requestRecoveryApproval(RecoveryApproval.Warning(it)) }
        if (requiresPin) {
            requestRecoveryApproval(if (useBiometrics) RecoveryApproval.Biometrics else RecoveryApproval.Pin)
        }
    }

    private suspend fun requestRecoveryApproval(step: RecoveryApproval) {
        val answer = CompletableDeferred<Boolean>()
        recoveryApproval = answer
        recoveryPinOpened = false
        _uiState.update { it.copy(recoveryApproval = step) }
        try {
            if (!answer.await()) throw CancellationException("Original payment retry cancelled")
        } finally {
            if (recoveryApproval === answer) {
                recoveryApproval = null
                _uiState.update { it.copy(recoveryApproval = null) }
            }
        }
    }

    fun answerRecoveryApproval(approved: Boolean) {
        recoveryApproval?.complete(approved)
    }

    fun answerRecoveryPin(approved: Boolean) {
        if (_uiState.value.recoveryApproval == RecoveryApproval.Pin) answerRecoveryApproval(approved)
    }

    fun useRecoveryPin() {
        recoveryPinOpened = false
        _uiState.update { it.copy(recoveryApproval = RecoveryApproval.Pin) }
    }

    fun openRecoveryPin(): Boolean {
        if (_uiState.value.recoveryApproval != RecoveryApproval.Pin || recoveryPinOpened) return false
        recoveryPinOpened = true
        return true
    }

    override fun onCleared() {
        recoveryApproval?.cancel()
        pendingPaymentRepo.setActiveHash(null)
    }

    fun onResolutionHandled() = _uiState.update { it.copy(resolution = null) }

    private fun findActivity(paymentHash: String) {
        viewModelScope.launch {
            activityRepo.findActivityByPaymentId(
                paymentHashOrTxId = paymentHash,
                type = ActivityFilter.LIGHTNING,
                txType = PaymentType.SENT,
                retry = true,
            ).onSuccess {
                _uiState.update { state -> state.copy(activityId = it.rawId()) }
            }
        }
    }

    private fun observeResolution(paymentHash: String) {
        viewModelScope.launch {
            pendingPaymentRepo.resolution
                .filter { it.paymentHash == paymentHash }
                .collect { resolution ->
                    pendingPaymentRepo.consumeResolution(paymentHash)
                    Logger.info(
                        "Received payment resolution '${resolution::class.simpleName}' for '$paymentHash'",
                        context = TAG,
                    )
                    _uiState.update { it.copy(resolution = resolution) }
                }
        }
    }
}

@Stable
data class SendPendingUiState(
    val amount: Long = 0L,
    val activityId: String? = null,
    val resolution: PendingPaymentResolution? = null,
    val recoveryAttempt: OnchainSendAttempt? = null,
    val isRecovering: Boolean = false,
    val recoveryApproval: RecoveryApproval? = null,
    val invalidFeeRate: Boolean = false,
    val recoveryError: String? = null,
    val currentTxid: String? = null,
    val recoveredTxid: String? = null,
    val recoveredTransfer: OnchainSendAttempt? = null,
    val cancellationTxid: String? = null,
    val cancellationFeeRate: ULong? = null,
    val isCancellationFinal: Boolean = false,
    val isCancellationHeld: Boolean = false,
    val isCancellationUnheld: Boolean = false,
    val cancelFeeTooLowSats: ULong? = null,
    val cancellationConfirmations: UInt = 0u,
    val normalFeeRate: ULong? = null,
    val speedUpFailed: Boolean = false,
    val hasUnknownCancellation: Boolean = false,
    val cancellationFailed: Boolean = false,
    val isOriginalPaid: Boolean = false,
    val isOriginalStatusUnknown: Boolean = false,
    val isGuardReleased: Boolean = false,
    val abandonableAttempt: OnchainSendAttempt? = null,
    val isAbandoning: Boolean = false,
    val abandonError: AbandonError? = null,
) {
    /** A sent or possibly relayed cancellation may still confirm, so the original must not be retried beside it. */
    val canRetry: Boolean
        get() = recoveryAttempt != null && cancellationTxid == null && !hasUnknownCancellation

    /** Only a request can be paid late; Blocktank no longer sells channels, so an order is never paid again. */
    val canRetryWithoutChecking: Boolean
        get() = canRetry && recoveryAttempt?.requestId != null

    /** An expired payment's release spends the original inputs too, so it is not sent beside another cancellation. */
    val canAbandon: Boolean
        get() = abandonableAttempt != null && cancellationTxid == null && !hasUnknownCancellation

    /**
     * A cancellation the wallet holds keeps the original inputs, so no new one builds over them until it drops out
     * of the network; meanwhile it can be sped up instead. One the wallet never saw can still be replaced.
     */
    val canCancel: Boolean
        get() = recoveryAttempt != null && !isCancellationHeld && !isCancellationFinal

    /** Before anything is sent, Pending says the payment can be cancelled. */
    val showsCancelHint: Boolean
        get() = canCancel && cancellationTxid == null && !hasUnknownCancellation

    /** Speeding up replaces the pending cancellation, and keeps every output in this wallet. */
    val canSpeedUpCancellation: Boolean
        get() = recoveryAttempt != null && isCancellationHeld && !isCancellationFinal

    /** A final cancellation should have released the guard, so one still held needs another sync or attention. */
    val isReleaseStalled: Boolean
        get() = recoveryAttempt != null && isCancellationFinal

    /** A known cancellation holds the original inputs until it confirms or drops out, so a new one fails meanwhile. */
    val isEarlierCancellationPending: Boolean
        get() = cancellationFailed && isCancellationHeld && !isCancellationFinal

    fun suggestedFeeRate(action: RecoveryAction): ULong? {
        val original = recoveryAttempt ?: return null
        return when (action) {
            // A replacement must pay a higher rate than everything it conflicts with, and a cancellation that
            // pays below the current estimate can wait weeks to confirm while it keeps every send blocked.
            RecoveryAction.Cancel, RecoveryAction.SpeedUpCancellation -> minOf(
                maxOf(
                    maxOf(original.highestSpendFeeRate, cancellationFeeRate ?: 0uL).safe() + 1uL.safe(),
                    normalFeeRate ?: 0uL,
                ),
                OnchainRecoveryFeeRate.maximum,
            )
            RecoveryAction.Retry, RecoveryAction.RetryWithoutChecking -> original.feeRateSatsPerVByte
        }
    }
}

enum class RecoveryAction { Retry, RetryWithoutChecking, Cancel, SpeedUpCancellation }

enum class AbandonError { MayStillConfirm, SyncUnavailable, ReleasePending }

sealed interface RecoveryApproval {
    data class Fee(val receipt: OnchainPreparedReceipt, val isCancellation: Boolean = false) : RecoveryApproval
    data class Warning(val warning: SanityWarning) : RecoveryApproval
    data object Biometrics : RecoveryApproval
    data object Pin : RecoveryApproval
    data class Release(val receipt: OnchainPreparedReceipt) : RecoveryApproval
}
