package to.bitkit.ui.screens.wallets.send

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.R
import to.bitkit.ext.isBroadcastConnectivityFailure
import to.bitkit.ext.isHardwareBroadcastNotSubmitted
import to.bitkit.ext.isHardwareBroadcastRefusalForNavigation
import to.bitkit.ext.isHwDeviceBusy
import to.bitkit.ext.isHwFirmwareError
import to.bitkit.ext.isHwSessionFailure
import to.bitkit.ext.isHwUserCancellation
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.HwWallet
import to.bitkit.models.Toast
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.HwPassphraseMismatchError
import to.bitkit.repositories.HwPassphraseRequiredError
import to.bitkit.repositories.HwRetainedSendRepo
import to.bitkit.repositories.HwWalletMismatchError
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.PaykitPaymentProofRepo
import to.bitkit.repositories.PaykitPaymentRequestError
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PreActivityMetadataRepo
import to.bitkit.services.CoreService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.HwErrorPresenter
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import to.bitkit.utils.SignedTransactionId
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@HiltViewModel
class HwSendViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hwWalletRepo: HwWalletRepo,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    private val coreService: CoreService,
    private val activityRepo: ActivityRepo,
    private val paykitPaymentProofRepo: PaykitPaymentProofRepo,
    private val hwRetainedSendRepo: HwRetainedSendRepo,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private companion object {
        const val TAG = "HwSendViewModel"
        val COMPOSE_TIMEOUT = 45.seconds
        val SIGN_TIMEOUT = 120.seconds
        val BROADCAST_TIMEOUT = 120.seconds
        val OBSERVE_TIMEOUT = 30.seconds
    }

    val wallets: StateFlow<ImmutableList<HwWallet>>
        get() = hwWalletRepo.wallets

    private val _uiState = MutableStateFlow(HwSendUiState())
    val uiState = _uiState.asStateFlow()

    private val pendingResult = MutableStateFlow<HwSendResult?>(null)
    val results = pendingResult.filterNotNull()

    private var pendingBroadcast: PendingHwSendBroadcast? = null
    private var signingWalletId: String? = null
    private var signingJob: Job? = null
    private var signingAttempt = 0
    private var passphraseJob: Job? = null

    private val isOperationInFlight: Boolean
        get() = pendingResult.value != null || _uiState.value.isSigning || signingJob?.isActive == true

    fun warmUp(walletId: String) {
        hwWalletRepo.warmUpKnownDevice(walletId)
    }

    fun signAndBroadcast(
        request: HwSendRequest,
        prepareContactPayment: suspend (HwFundingSignedTx) -> Boolean = { true },
        authorizeContactPayment: suspend (hasAttemptedBroadcast: Boolean) -> Boolean = { true },
        onPaymentDeadlineExpired: suspend (hasAttemptedBroadcast: Boolean) -> Unit = {},
        beginPaymentSubmission: () -> (() -> Unit) = { {} },
    ) {
        if (isOperationInFlight) return
        signingWalletId = request.walletId
        _uiState.update { it.copy(isSigning = true) }
        val attempt = ++signingAttempt
        signingJob = viewModelScope.launch {
            val finishSubmission = beginPaymentSubmission()
            try {
                runCatching {
                    if (pendingBroadcast?.blocks(request) == true) {
                        showOperationInProgress()
                        return@runCatching
                    }
                    if (request.paymentRequestId == null && !restoreRetainedSend(request)) return@runCatching
                    var pending = pendingBroadcast?.takeIf { it.matches(request) }
                    if (pending == null && request.paymentRequestId != null) {
                        val restored = paykitPaymentProofRepo.retainedHardwareOnchainPayment(
                            request.paymentRequestId,
                            request.walletId,
                            request.paymentIdentity,
                            request.address,
                            request.amountSats
                        )
                        if (restored != null) {
                            pending =
                                PendingHwSendBroadcast(
                                    request,
                                    restored.signedTx,
                                    isPreparedForBroadcast = true,
                                    hasAttemptedBroadcast = restored.hasAttemptedBroadcast,
                                    isRefusedForNavigation = restored.isRefusedForNavigation,
                                )
                            pendingBroadcast = pending
                            _uiState.update {
                                it.copy(
                                    hasPendingBroadcast = true,
                                    isBroadcastUnresolved =
                                    restored.hasAttemptedBroadcast && !restored.isRefusedForNavigation,
                                )
                            }
                        }
                    }
                    if (pending == null && paykitPaymentProofRepo.hasRetainedHardwareOnchainPayment(request.walletId)) {
                        throw PaykitPaymentRequestError.OperationInProgress
                    }
                    if (pending == null && hwWalletRepo.needsPassphrase(request.walletId)) {
                        _uiState.update { it.copy(isPassphraseRequired = true) }
                        return@runCatching
                    }
                    if (pending == null) {
                        val signedTx = prepareSignedTransaction(
                            walletId = request.walletId,
                            address = request.address,
                            amountSats = request.amountSats,
                            satsPerVByte = request.satsPerVByte,
                        )
                        pending = PendingHwSendBroadcast(request, signedTx)
                        pendingBroadcast = pending
                        _uiState.update { it.copy(hasPendingBroadcast = true) }
                    }
                    var payment = checkNotNull(pending) { "Hardware payment was not prepared" }
                    if (payment.isPreparedForBroadcast.not()) {
                        if (!prepareContactPayment(payment.signedTx)) return@runCatching
                        payment = payment.copy(isPreparedForBroadcast = true)
                        pendingBroadcast = payment
                    }
                    if (!authorizeBroadcast(payment, authorizeContactPayment, onPaymentDeadlineExpired)) {
                        return@runCatching
                    }
                    val result = broadcast(payment, onPaymentDeadlineExpired) ?: return@runCatching
                    completeSend(request, result, attempt)
                }.onFailure {
                    if (it is CancellationException && it !is TimeoutCancellationException) throw it
                    settleFailure(it, request, attempt)
                }
            } finally {
                finishSubmission()
                // A cancelled job can outlive cancel() while a device call returns, and must not
                // reset the state of a signing attempt started after it.
                if (signingAttempt == attempt) {
                    _uiState.update { it.copy(isSigning = false, isConnectingDevice = false) }
                    signingJob = null
                }
            }
        }
    }

    private suspend fun showOperationInProgress() = ToastEventBus.send(
        type = Toast.ToastType.ERROR,
        title = context.getString(R.string.common__error),
        description = HwErrorPresenter.userMessage(context, PaykitPaymentRequestError.OperationInProgress),
    )

    private fun clearPendingBroadcast() {
        pendingBroadcast = null
        _uiState.update { it.copy(hasPendingBroadcast = false) }
    }

    private suspend fun restoreRetainedSend(request: HwSendRequest): Boolean {
        val record = hwRetainedSendRepo.record(request.walletId).getOrThrow()
        if (record?.isSamePayment(request.address, request.amountSats) == true) {
            pendingBroadcast = PendingHwSendBroadcast(
                request,
                record.signedTx,
                isPreparedForBroadcast = true,
                hasAttemptedBroadcast = true,
            )
            _uiState.update { it.copy(hasPendingBroadcast = true) }
            return true
        }
        if (record?.observed == false) {
            showOperationInProgress()
            return false
        }
        // An observed record for another payment is resolved. A failed release stops here, before any device contact.
        if (record != null) hwRetainedSendRepo.release(request.walletId).getOrThrow()
        _uiState.update { it.copy(hasPendingBroadcast = pendingBroadcast != null) }
        return true
    }

    private suspend fun releaseRetainedSend(walletId: String) {
        hwRetainedSendRepo.release(walletId)
            .onFailure { Logger.warn("Failed to release retained hardware send for '$walletId'", it, context = TAG) }
    }

    private suspend fun completeSend(request: HwSendRequest, result: HwFundingBroadcastResult, attempt: Int) {
        if (request.paymentRequestId == null) releaseRetainedSend(request.walletId)
        if (signingAttempt != attempt) return
        runSuspendCatching { persistResult(request, result) }
            .onFailure { Logger.error("Failed to persist hardware send result", it, context = TAG) }
        if (signingAttempt != attempt) return
        pendingResult.update {
            HwSendResult(
                request.walletId,
                result.txId,
                request.amountSats,
                request.paymentRequestId,
                request.paymentIdentity,
            )
        }
    }

    private suspend fun authorizeBroadcast(
        payment: PendingHwSendBroadcast,
        authorizeContactPayment: suspend (Boolean) -> Boolean,
        onPaymentDeadlineExpired: suspend (Boolean) -> Unit,
    ): Boolean {
        if (payment.request.paymentDeadlineAt?.let { clock.now() > it } == true) {
            _uiState.update { it.copy(isBroadcastUnresolved = payment.blocksNavigation) }
            onPaymentDeadlineExpired(payment.hasAttemptedBroadcast)
            return false
        }
        val authorized = authorizeContactPayment(payment.hasAttemptedBroadcast)
        if (!authorized && payment.blocksNavigation &&
            payment.request.paymentDeadlineAt?.let { clock.now() > it } == true
        ) {
            _uiState.update { it.copy(isBroadcastUnresolved = true) }
        }
        return authorized
    }

    private suspend fun broadcast(
        payment: PendingHwSendBroadcast,
        onPaymentDeadlineExpired: suspend (Boolean) -> Unit,
    ): HwFundingBroadcastResult? {
        val request = payment.request
        request.paymentRequestId?.let { requestId ->
            // Protect the only signed receipt while its durable lookup is being saved as well.
            _uiState.update { it.copy(isBroadcastUnresolved = true) }
            val retained = runSuspendCatching {
                paykitPaymentProofRepo.retainHardwareOnchainCandidate(
                    requestId,
                    request.walletId,
                    SignedTransactionId.fromHex(payment.signedTx.serializedTx),
                    request.paymentIdentity,
                    request.address,
                    request.amountSats,
                    payment.signedTx
                )
            }.onFailure { Logger.warn("Failed to retain signed hardware receipt", it, context = TAG) }
                .getOrDefault(false)
            if (!retained) {
                // No dispatch occurred. Keep the exact signed transaction available for a save retry;
                // Back/cancel must not discard it while the durable Shop preparation remains started.
                _uiState.update { it.copy(isBroadcastUnresolved = true) }
                return null
            }
        }
        _uiState.update { it.copy(isBroadcastUnresolved = true) }
        if (request.paymentRequestId == null) {
            hwRetainedSendRepo.retain(request.walletId, request.address, request.amountSats, payment.signedTx)
                .getOrThrow()
        }
        return withTimeout(BROADCAST_TIMEOUT) {
            val requestId = request.paymentRequestId
            if (requestId == null) {
                pendingBroadcast = payment.copy(
                    hasAttemptedBroadcast = true,
                    isRefusedForNavigation = false,
                    dispatch = if (payment.hasAttemptedBroadcast) HwSendDispatch.Retry else HwSendDispatch.FirstAttempt,
                )
                hwWalletRepo.broadcastFunding(payment.signedTx, request.paymentDeadlineAt)
            } else {
                hwWalletRepo.broadcastFundingAtBoundary(payment.signedTx, request.paymentDeadlineAt) {
                    check(
                        paykitPaymentProofRepo.markHardwareOnchainDispatch(
                            requestId,
                            request.walletId,
                            SignedTransactionId.fromHex(payment.signedTx.serializedTx),
                            request.paymentIdentity,
                            request.address,
                            request.amountSats,
                        )
                    ) { "Original hardware payment is no longer eligible for dispatch" }
                    pendingBroadcast = payment.copy(hasAttemptedBroadcast = true, isRefusedForNavigation = false)
                }
            }
        }.getOrElse { error ->
            if (generateSequence(error) { it.cause }.any { it is ServiceError.PaymentDeadlineExpired }) {
                val cleared = request.paymentRequestId?.let { requestId ->
                    !payment.hasAttemptedBroadcast &&
                        paykitPaymentProofRepo.clearHardwareOnchainCandidateBeforeDispatch(
                            requestId, request.walletId, SignedTransactionId.fromHex(payment.signedTx.serializedTx),
                            request.paymentIdentity, request.address, request.amountSats, payment.hasAttemptedBroadcast,
                        )
                } ?: !payment.hasAttemptedBroadcast
                val unresolved = payment.hasAttemptedBroadcast || !cleared
                if (request.paymentRequestId == null && !unresolved) releaseRetainedSend(request.walletId)
                pendingBroadcast = payment.copy(hasAttemptedBroadcast = unresolved)
                _uiState.update { it.copy(isBroadcastUnresolved = unresolved && !payment.isRefusedForNavigation) }
                onPaymentDeadlineExpired(unresolved)
                return null
            }
            throw error
        }
    }

    fun submitPassphrase(
        walletId: String,
        passphrase: String,
        onReady: () -> Unit,
    ) {
        if (passphrase.isEmpty()) return
        val state = _uiState.value
        if (!state.isPassphraseRequired) return
        if (state.isVerifyingPassphrase) return
        if (passphraseJob?.isActive == true) return

        _uiState.update { it.copy(isVerifyingPassphrase = true) }
        passphraseJob = viewModelScope.launch {
            try {
                hwWalletRepo.reconnectWithPassphrase(walletId, passphrase)
                    .onSuccess {
                        if (!_uiState.value.isPassphraseRequired) return@onSuccess
                        _uiState.update { it.copy(isPassphraseRequired = false) }
                        onReady()
                    }
                    .onFailure { error ->
                        if (error is HwPassphraseMismatchError) {
                            ToastEventBus.send(
                                type = Toast.ToastType.ERROR,
                                title = context.getString(R.string.common__error),
                                description = context.getString(R.string.hardware__passphrase_mismatch),
                            )
                        } else {
                            handleFailure(error, walletId)
                        }
                    }
            } finally {
                _uiState.update { it.copy(isVerifyingPassphrase = false) }
                passphraseJob = null
            }
        }
    }

    fun dismissPassphrase() {
        passphraseJob?.cancel()
        passphraseJob = null
        _uiState.update { it.copy(isPassphraseRequired = false, isVerifyingPassphrase = false) }
    }

    fun cancel() {
        passphraseJob?.cancel()
        passphraseJob = null
        _uiState.update { it.copy(isPassphraseRequired = false, isVerifyingPassphrase = false) }
        if (_uiState.value.isBroadcastUnresolved) return

        signingJob?.cancel()
        signingJob = null
        signingAttempt++
        // A refused Shop payment may leave the sheet, but its signed operation stays guarded.
        if (pendingBroadcast?.let { it.request.paymentRequestId != null && it.hasAttemptedBroadcast } != true) {
            pendingBroadcast = null
        }
        _uiState.update {
            it.copy(isSigning = false, isConnectingDevice = false, hasPendingBroadcast = pendingBroadcast != null)
        }
        val walletId = signingWalletId ?: return
        signingWalletId = null
        viewModelScope.launch { hwWalletRepo.disconnectStaleSession(walletId) }
    }

    fun completeReconciledBroadcast(
        walletId: String?,
        txid: String?,
        onCompleted: (HwSendResult) -> Unit = {},
    ): Boolean {
        val result = pendingResult.value?.takeIf {
            it.walletId == walletId && it.txId.equals(txid, ignoreCase = true)
        } ?: pendingBroadcast?.takeIf {
            pendingResult.value == null && it.hasAttemptedBroadcast && it.request.paymentRequestId != null &&
                it.request.walletId == walletId &&
                SignedTransactionId.fromHex(it.signedTx.serializedTx).equals(txid, ignoreCase = true)
        }?.let {
            HwSendResult(
                it.request.walletId, requireNotNull(txid).lowercase(), it.request.amountSats,
                it.request.paymentRequestId, it.request.paymentIdentity
            )
        } ?: return false
        // Exact observation owns completion; a late native response belongs to the old attempt.
        signingAttempt++
        signingJob?.cancel()
        signingJob = null
        _uiState.update { it.copy(isSigning = false, isConnectingDevice = false) }
        completeBroadcast()
        onCompleted(result)
        return true
    }

    fun completeBroadcast() {
        pendingBroadcast = null
        signingWalletId = null
        pendingResult.update { null }
        _uiState.update {
            it.copy(
                hasPendingBroadcast = false,
                isBroadcastUnresolved = false,
            )
        }
    }

    /**
     * What the sign sheet should offer before any device contact. [hasIncomingRequest] is required so a Shop caller
     * cannot skip it: Shop payments keep their own durable proofs and never read this record.
     */
    suspend fun retainedSendStatus(
        address: String,
        amountSats: ULong,
        walletId: String,
        hasIncomingRequest: Boolean,
    ): HwRetainedSendStatus {
        if (hasIncomingRequest) return HwRetainedSendStatus.None
        val record = hwRetainedSendRepo.record(walletId).getOrElse {
            Logger.warn("Failed to read retained hardware send for '$walletId'", it, context = TAG)
            return HwRetainedSendStatus.Blocking
        } ?: return HwRetainedSendStatus.None
        val isSamePayment = record.isSamePayment(address, amountSats)
        return when {
            // The first payment is on the network: repeating it needs consent, and another payment needs none.
            record.observed -> if (isSamePayment) HwRetainedSendStatus.Blocking else HwRetainedSendStatus.None
            record.retryWasRefused || !isSamePayment -> HwRetainedSendStatus.Blocking
            else -> HwRetainedSendStatus.Retry(record.feeRate, record.miningFeeSats)
        }
    }

    /**
     * Whether the sign button offers a retry of [request]. Contacts no device, so a reopened sheet can ask.
     * An observed record still offers one: the review warns before a reopened send gets here, so it is this sheet's.
     */
    suspend fun offersRetry(request: HwSendRequest): Boolean {
        if (request.paymentRequestId != null) return _uiState.value.hasPendingBroadcast
        if (pendingBroadcast?.matches(request) == true) return true
        val record = hwRetainedSendRepo.record(request.walletId).getOrElse {
            Logger.warn("Failed to read retained hardware send for '${request.walletId}'", it, context = TAG)
            return false
        } ?: return false
        return record.isSamePayment(request.address, request.amountSats) && (record.observed || !record.retryWasRefused)
    }

    /** Only for a user who accepted a possible second payment. Does nothing while an operation is in flight. */
    suspend fun abandonRetainedPayment(walletId: String): Result<Unit> {
        if (isOperationInFlight) return Result.success(Unit)
        return hwRetainedSendRepo.abandon(walletId).onSuccess {
            if (pendingBroadcast?.request?.paymentRequestId == null) clearPendingBroadcast()
        }
    }

    private suspend fun prepareSignedTransaction(
        walletId: String,
        address: String,
        amountSats: ULong,
        satsPerVByte: ULong,
    ): HwFundingSignedTx {
        ensureConnected(walletId)
        val funding = withTimeout(COMPOSE_TIMEOUT) {
            hwWalletRepo.composeFundingTransaction(
                walletId = walletId,
                address = address,
                sats = amountSats,
                satsPerVByte = satsPerVByte,
            ).getOrThrow()
        }
        return sign(walletId, funding)
    }

    private suspend fun sign(walletId: String, funding: HwFundingTransaction): HwFundingSignedTx {
        val firstAttempt = runSuspendCatching { signWithTimeoutCleanup(walletId, funding) }
        val error = firstAttempt.exceptionOrNull() ?: return firstAttempt.getOrThrow()
        if (!error.isHwSessionFailure()) throw error

        ensureConnected(walletId)
        return signWithTimeoutCleanup(walletId, funding)
    }

    private suspend fun ensureConnected(walletId: String) {
        // Nothing has been sent to the device for signing yet, so the sheet may be left while this
        // waits; a Jade may sit here for minutes waiting for its PIN.
        val attempt = signingAttempt
        _uiState.update { it.copy(isConnectingDevice = true) }
        try {
            // A Jade reconnect may include entering the PIN on the device, so the budget is per vendor.
            withTimeout(hwWalletRepo.reconnectTimeout(walletId)) {
                hwWalletRepo.ensureConnected(walletId).getOrThrow()
            }
        } finally {
            if (signingAttempt == attempt) _uiState.update { it.copy(isConnectingDevice = false) }
        }
    }

    private suspend fun signWithTimeoutCleanup(
        walletId: String,
        funding: HwFundingTransaction,
    ): HwFundingSignedTx = try {
        signOnce(walletId, funding)
    } catch (error: TimeoutCancellationException) {
        hwWalletRepo.disconnectStaleSession(walletId)
        throw error
    }

    private suspend fun signOnce(walletId: String, funding: HwFundingTransaction): HwFundingSignedTx =
        withTimeout(SIGN_TIMEOUT) {
            hwWalletRepo.signFunding(walletId, funding).getOrThrow()
        }

    private suspend fun persistResult(request: HwSendRequest, result: HwFundingBroadcastResult) {
        if (request.tags.isNotEmpty()) {
            preActivityMetadataRepo.savePreActivityMetadata(
                id = result.txId,
                txId = result.txId,
                address = request.address,
                isReceive = false,
                tags = request.tags,
                feeRate = result.feeRate,
                walletId = request.walletId,
            )
        }
        // A Core txid alone is not positive evidence for a Shop payment. Its original proof
        // completes local activity after a fresh exact outgoing transaction observation.
        if (request.paymentRequestId != null) return
        coreService.activity.createSentOnchainActivityFromSendResult(
            txid = result.txId,
            address = request.address,
            amount = request.amountSats,
            fee = result.miningFeeSats,
            feeRate = result.feeRate,
            isTransfer = false,
            channelId = null,
            walletId = request.walletId,
        )
        if (request.tags.isNotEmpty()) {
            activityRepo.addTagsToActivity(result.txId, request.tags, request.walletId)
        }
        activityRepo.notifyPaymentActivityChanged()
    }

    private suspend fun settleFailure(error: Throwable, request: HwSendRequest, attempt: Int) {
        val walletId = request.walletId
        val payment = pendingBroadcast
        val dispatch = payment?.dispatch ?: return handleFailure(error, walletId)
        val settled = payment.copy(dispatch = null)
        pendingBroadcast = settled
        val txId = runCatching { SignedTransactionId.fromHex(settled.signedTx.serializedTx) }.getOrNull()
        // A transaction already seen on the network is out, whatever the server replied, so this is a success.
        if (txId != null && hwRetainedSendRepo.record(walletId).getOrNull()?.observed == true) {
            return completeRetainedSend(settled, txId, attempt)
        }
        val isFirstAttempt = dispatch == HwSendDispatch.FirstAttempt
        when {
            error.isHardwareBroadcastRefusalForNavigation() && isFirstAttempt -> {
                releaseRetainedSend(walletId)
                clearPendingBroadcast()
                handleFailure(error, walletId)
            }
            error.isHardwareBroadcastRefusalForNavigation() -> settleRetryRefusal(error, settled, txId, attempt)
            error.isHardwareBroadcastNotSubmitted() -> {
                if (isFirstAttempt) {
                    releaseRetainedSend(walletId)
                    pendingBroadcast = settled.copy(hasAttemptedBroadcast = false)
                }
                handleFailure(error, walletId)
                if (!isFirstAttempt) pendingBroadcast = null
            }
            else -> {
                pendingBroadcast = null
                handleFailure(error, walletId, mayHaveBeenSent = true)
            }
        }
    }

    private suspend fun settleRetryRefusal(
        error: Throwable,
        payment: PendingHwSendBroadcast,
        txId: String?,
        attempt: Int,
    ) {
        val walletId = payment.request.walletId
        hwRetainedSendRepo.markRetryRefused(walletId)
            .onFailure { Logger.warn("Failed to mark retained hardware send for '$walletId'", it, context = TAG) }
        if (txId != null && isTransactionObserved(walletId, txId)) {
            completeRetainedSend(payment, txId, attempt)
        } else {
            handleFailure(error, walletId, mayHaveBeenSent = true)
        }
    }

    private suspend fun completeRetainedSend(payment: PendingHwSendBroadcast, txId: String, attempt: Int) {
        val signedTx = payment.signedTx
        val result = HwFundingBroadcastResult(txId, signedTx.miningFeeSats, signedTx.feeRate, signedTx.totalSpent)
        completeSend(payment.request, result, attempt)
    }

    private suspend fun isTransactionObserved(walletId: String, txId: String): Boolean =
        withTimeoutOrNull(OBSERVE_TIMEOUT) {
            hwWalletRepo.observeExactTransaction(walletId, txId).getOrDefault(false)
        } == true

    private suspend fun handleFailure(error: Throwable, walletId: String, mayHaveBeenSent: Boolean = false) {
        val retainedShopPayment = pendingBroadcast?.let {
            it.request.paymentRequestId != null && it.hasAttemptedBroadcast
        } == true
        // This changes navigation only: a protocol refusal never releases the durable wallet guard.
        val refused = retainedShopPayment && error.isHardwareBroadcastRefusalForNavigation()
        if (refused) pendingBroadcast = pendingBroadcast?.copy(isRefusedForNavigation = true)
        if (refused) {
            val payment = requireNotNull(pendingBroadcast)
            val request = payment.request
            runSuspendCatching {
                paykitPaymentProofRepo.markHardwareRefusedForNavigation(
                    requireNotNull(request.paymentRequestId),
                    request.walletId,
                    SignedTransactionId.fromHex(payment.signedTx.serializedTx),
                    request.paymentIdentity,
                    request.address,
                    request.amountSats,
                )
            }.onFailure { Logger.warn("Failed to save hardware refusal navigation state", it, context = TAG) }
        }
        _uiState.update {
            it.copy(isBroadcastUnresolved = retainedShopPayment && pendingBroadcast?.blocksNavigation == true)
        }
        when {
            mayHaveBeenSent -> ToastEventBus.send(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.hardware__send_broadcast_uncertain_title),
                description = context.getString(R.string.hardware__send_broadcast_uncertain_text),
            )
            error.isHwUserCancellation() -> {
                Logger.info("Hardware send cancelled on device for '$walletId'", context = TAG)
            }
            generateSequence(error) { it.cause }.any { it is HwPassphraseRequiredError } -> {
                _uiState.update { it.copy(isPassphraseRequired = true) }
            }
            generateSequence(error) { it.cause }.any { it is HwWalletMismatchError } -> ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.common__error),
                description = context.getString(R.string.hardware__wallet_mismatch),
            )
            error.isHwDeviceBusy() -> ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = HwErrorPresenter.userMessage(context, error),
            )
            error.isHwFirmwareError() -> ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.lightning__transfer_hw__reconnect_error_title),
                description = context.getString(R.string.lightning__transfer_hw__reconnect_error_description),
            )
            pendingBroadcast != null &&
                (error.isBroadcastConnectivityFailure() || error is TimeoutCancellationException) -> ToastEventBus.send(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.hardware__send_broadcast_failed_title),
                description = context.getString(R.string.hardware__send_broadcast_failed_text),
            )
            error is TimeoutCancellationException -> ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.common__error),
                description = context.getString(R.string.wallet__payment_timeout),
            )
            else -> {
                if (pendingBroadcast != null && !retainedShopPayment) clearPendingBroadcast()
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.common__error),
                    description = HwErrorPresenter.userMessage(
                        context = context,
                        error = error,
                        fallback = context.getString(R.string.hardware__connect_error),
                    ),
                )
            }
        }
    }
}

@Immutable
data class HwSendUiState(
    val isSigning: Boolean = false,
    val isConnectingDevice: Boolean = false,
    val hasPendingBroadcast: Boolean = false,
    val isBroadcastUnresolved: Boolean = false,
    val isPassphraseRequired: Boolean = false,
    val isVerifyingPassphrase: Boolean = false,
) {
    /**
     * Whether the sign sheet may be dismissed. Connecting or unlocking can be abandoned, and leaving
     * cancels it. A definite refusal may dismiss the sheet while the signed payment remains guarded.
     * Active signing and uncertain broadcasts still prevent dismissal.
     */
    val canLeave: Boolean
        get() = (!isSigning || isConnectingDevice) && !isBroadcastUnresolved
}

sealed interface HwRetainedSendStatus {
    data object None : HwRetainedSendStatus
    data class Retry(val feeRate: ULong, val miningFeeSats: ULong) : HwRetainedSendStatus
    data object Blocking : HwRetainedSendStatus
}

data class HwSendResult(
    val walletId: String,
    val txId: String,
    val amountSats: ULong,
    val paymentRequestId: PaykitPaymentRequestId? = null,
    val paymentIdentity: String? = null,
)

data class HwSendRequest(
    val walletId: String,
    val address: String,
    val amountSats: ULong,
    val satsPerVByte: ULong,
    val tags: List<String>,
    val paymentRequestId: PaykitPaymentRequestId? = null,
    val paymentIdentity: String? = null,
    val paymentDeadlineAt: Instant? = null,
)

private enum class HwSendDispatch { FirstAttempt, Retry }

private data class PendingHwSendBroadcast(
    val request: HwSendRequest,
    val signedTx: HwFundingSignedTx,
    val isPreparedForBroadcast: Boolean = false,
    val hasAttemptedBroadcast: Boolean = false,
    val isRefusedForNavigation: Boolean = false,
    val dispatch: HwSendDispatch? = null,
) {
    val blocksNavigation: Boolean get() = hasAttemptedBroadcast && !isRefusedForNavigation

    fun blocks(request: HwSendRequest): Boolean = !matches(request) &&
        (this.request.paymentRequestId != null || this.request.walletId == request.walletId)

    fun matches(request: HwSendRequest): Boolean = this.request == request ||
        hasAttemptedBroadcast && this.request.paymentRequestId != null &&
        this.request.copy(satsPerVByte = request.satsPerVByte, tags = request.tags) == request
}
