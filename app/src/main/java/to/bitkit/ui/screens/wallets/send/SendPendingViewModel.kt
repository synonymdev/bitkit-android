package to.bitkit.ui.screens.wallets.send

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.bitkit.ext.rawId
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.WalletScope
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.OnchainRecoveryFeeRate
import to.bitkit.repositories.OnchainPreparedReceipt
import to.bitkit.repositories.OnchainSendAttempt
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.SanityWarning
import javax.inject.Inject

@HiltViewModel
class SendPendingViewModel @Inject constructor(
    private val pendingPaymentRepo: PendingPaymentRepo,
    private val activityRepo: ActivityRepo,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "SendPendingViewModel"
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
                    attempt?.candidateTxids?.any { it.equals(txid, true) } == true
                if (matchesOriginalWallet && hasOriginalReceipt && attempt != null) {
                    if (attempt.isUnresolved) _uiState.update { it.copy(recoveryAttempt = attempt) }
                    observeOriginalCompletion(attempt)
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
        if (_uiState.value.isRecovering) return
        if (!OnchainRecoveryFeeRate.isValid(feeRateSatsPerVByte)) {
            _uiState.update { it.copy(invalidFeeRate = true, recoveryError = "invalid fee rate") }
            return
        }
        _uiState.update { it.copy(isRecovering = true, recoveryError = null, invalidFeeRate = false) }
        viewModelScope.launch {
            try {
                val result = runSuspendCatching { retry(original, feeRateSatsPerVByte).getOrThrow() }
                result.onSuccess { outcome ->
                    val completed = runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.getOrNull()
                        ?.takeIf { it.hasPositiveEvidence && it.localFollowupComplete && it.matchesOriginalRoute(original) }
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
                            it.copy(recoveryError = error.message)
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

    suspend fun approveRecoveryFee(
        receipt: OnchainPreparedReceipt,
        warnings: List<SanityWarning>,
        requiresPin: Boolean,
        useBiometrics: Boolean,
    ) = withContext(Dispatchers.Main.immediate) {
        requireNotNull(receipt.miningFeeSats)
        requestRecoveryApproval(RecoveryApproval.Fee(receipt))
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
)

sealed interface RecoveryApproval {
    data class Fee(val receipt: OnchainPreparedReceipt) : RecoveryApproval
    data class Warning(val warning: SanityWarning) : RecoveryApproval
    data object Biometrics : RecoveryApproval
    data object Pin : RecoveryApproval
}
