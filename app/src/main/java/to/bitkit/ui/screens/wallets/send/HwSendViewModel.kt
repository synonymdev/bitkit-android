package to.bitkit.ui.screens.wallets.send

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import to.bitkit.R
import to.bitkit.data.HeldHwSend
import to.bitkit.ext.isBroadcastConnectivityFailure
import to.bitkit.ext.isHardwareBroadcastNotSent
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
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private companion object {
        const val TAG = "HwSendViewModel"
        val COMPOSE_TIMEOUT = 45.seconds
        val SIGN_TIMEOUT = 120.seconds
    }

    val wallets: StateFlow<ImmutableList<HwWallet>>
        get() = hwWalletRepo.wallets

    private val _uiState = MutableStateFlow(HwSendUiState())
    val uiState = _uiState.asStateFlow()

    private val pendingResult = MutableStateFlow<HwSendResult?>(null)
    val results = pendingResult.filterNotNull()

    private var pendingBroadcast: PendingHwSendBroadcast? = null
    private var unresolvedSendWatch: Job? = null
    private var signingWalletId: String? = null
    private var signingJob: Job? = null
    private var signingAttempt = 0
    private var passphraseJob: Job? = null

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
        if (pendingResult.value != null || _uiState.value.isSigning || signingJob?.isActive == true) return
        signingWalletId = request.walletId
        _uiState.update { it.copy(isSigning = true) }
        val attempt = ++signingAttempt
        signingJob = viewModelScope.launch {
            val finishSubmission = beginPaymentSubmission()
            try {
                runCatching {
                    // A send whose broadcast may have gone out is only ever broadcast again as the same bytes, and
                    // nothing is signed beside it
                    if (pendingBroadcast?.isOutcomeUnknown == true) {
                        ToastEventBus.send(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.common__error),
                            description = context.getString(R.string.wallet__send_pending__blocked_new_send),
                        )
                        return@runCatching
                    }
                    if (pendingBroadcast?.matches(request) == false) {
                        ToastEventBus.send(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.common__error),
                            description = HwErrorPresenter.userMessage(
                                context,
                                PaykitPaymentRequestError.OperationInProgress,
                            ),
                        )
                        return@runCatching
                    }
                    // A send of this payment held after the sheet closed or the app restarted is finished first
                    val held = if (pendingBroadcast == null && request.paymentRequestId == null) {
                        hwWalletRepo.heldSendFor(request.walletId, request.address, request.amountSats).getOrThrow()
                    } else {
                        null
                    }
                    if (held != null) {
                        Logger.warn("Refused held hardware send of '${request.walletId}'", context = TAG)
                        watchUnresolvedSend(held.toPendingBroadcast(request))
                        ToastEventBus.send(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.common__error),
                            description = context.getString(R.string.wallet__send_pending__blocked_new_send),
                        )
                        return@runCatching
                    }
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
                    if (signingAttempt != attempt) return@runCatching
                    runSuspendCatching { persistResult(request, result) }
                        .onFailure { Logger.error("Failed to persist hardware send result", it, context = TAG) }
                    if (signingAttempt != attempt) return@runCatching
                    pendingResult.update {
                        HwSendResult(
                            request.walletId, result.txId, request.amountSats,
                            request.paymentRequestId, request.paymentIdentity
                        )
                    }
                }.onFailure {
                    if (it is CancellationException && it !is TimeoutCancellationException) throw it
                    handleFailure(it, request.walletId)
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
        payment.request.paymentRequestId?.let { requestId ->
            val request = payment.request
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
        val request = payment.request
        val requestId = request.paymentRequestId
        val txId = SignedTransactionId.fromHex(payment.signedTx.serializedTx)
        // Written before the broadcast, so a send that may have gone out stays held even if the app dies during it
        if (requestId == null) hwWalletRepo.holdSend(payment.toHeldSend(txId)).getOrThrow()
        _uiState.update { it.copy(isBroadcastUnresolved = true) }
        // No deadline: the broadcast runs to its end on the core queue, so one could only discard the outcome of a
        // payment that went out. The sheet stays unresolved until it answers.
        var isDispatched = false
        val outcome = hwWalletRepo.broadcastFundingAtBoundary(payment.signedTx, request.paymentDeadlineAt) {
            requestId?.let {
                check(
                    paykitPaymentProofRepo.markHardwareOnchainDispatch(
                        it,
                        request.walletId,
                        txId,
                        request.paymentIdentity,
                        request.address,
                        request.amountSats,
                    )
                ) { "Original hardware payment is no longer eligible for dispatch" }
            }
            isDispatched = true
            pendingBroadcast = payment.copy(hasAttemptedBroadcast = true, isRefusedForNavigation = false)
        }
        val error = outcome.exceptionOrNull()
        if (requestId == null) {
            // A failure before the dispatch sent nothing, while one after it may follow the write
            if (error != null && isDispatched && !error.isHardwareBroadcastNotSent()) {
                watchUnresolvedSend(payment)
            } else {
                releaseHeldSend(txId)
            }
        }
        return outcome.getOrElse { error ->
            if (generateSequence(error) { it.cause }.any { it is ServiceError.PaymentDeadlineExpired }) {
                val cleared = requestId?.let {
                    !payment.hasAttemptedBroadcast &&
                        paykitPaymentProofRepo.clearHardwareOnchainCandidateBeforeDispatch(
                            it, request.walletId, txId,
                            request.paymentIdentity, request.address, request.amountSats, payment.hasAttemptedBroadcast,
                        )
                } ?: !payment.hasAttemptedBroadcast
                val unresolved = payment.hasAttemptedBroadcast || !cleared
                pendingBroadcast = payment.copy(hasAttemptedBroadcast = unresolved)
                _uiState.update { it.copy(isBroadcastUnresolved = unresolved && !payment.isRefusedForNavigation) }
                onPaymentDeadlineExpired(unresolved)
                return null
            }
            throw error
        }
    }

    private suspend fun releaseHeldSend(txId: String) {
        hwWalletRepo.releaseHeldSend(txId)
            .onFailure { Logger.warn("Failed to release held hardware send '$txId'", it, context = TAG) }
    }

    private fun watchUnresolvedSend(payment: PendingHwSendBroadcast) {
        val held = payment.copy(hasAttemptedBroadcast = true, isRefusedForNavigation = false, isOutcomeUnknown = true)
        pendingBroadcast = held
        val request = held.request
        val txId = SignedTransactionId.fromHex(held.signedTx.serializedTx)
        val send = HwSendResult(request.walletId, txId, request.amountSats, paymentIdentity = request.paymentIdentity)
        _uiState.update { it.copy(hasPendingBroadcast = true, unresolvedSend = send) }
        unresolvedSendWatch?.cancel()
        unresolvedSendWatch = viewModelScope.launch {
            hwWalletRepo.activities.first { activities ->
                activities.filterIsInstance<Activity.Onchain>().any {
                    it.v1.walletId == send.walletId && it.v1.txId.equals(send.txId, ignoreCase = true)
                }
            }
            completeUnresolvedSend(held, send)
        }
    }

    /**
     * Broadcasts the signed bytes of a send whose broadcast may have gone out once more, for when that broadcast never
     * reached the server. Any failure keeps the send held, since the first broadcast may still confirm.
     */
    fun retryUnresolvedSend() {
        val payment = pendingBroadcast?.takeIf { it.isOutcomeUnknown } ?: return
        val send = _uiState.value.unresolvedSend ?: return
        if (_uiState.value.isRetryingUnresolvedSend) return
        _uiState.update { it.copy(isRetryingUnresolvedSend = true) }
        viewModelScope.launch {
            try {
                hwWalletRepo.broadcastFunding(payment.signedTx)
                    .onSuccess { completeUnresolvedSend(payment, send) }
                    .onFailure {
                        if (pendingBroadcast !== payment) return@onFailure
                        // A refusal now does not prove the first broadcast was not delivered, so the send stays held
                        val description = if (it.isHardwareBroadcastRefusalForNavigation()) {
                            R.string.hardware__send_broadcast_refused_text
                        } else {
                            R.string.hardware__send_broadcast_failed_text
                        }
                        ToastEventBus.send(
                            type = Toast.ToastType.WARNING,
                            title = context.getString(R.string.hardware__send_broadcast_failed_title),
                            description = context.getString(description),
                        )
                    }
            } finally {
                _uiState.update { it.copy(isRetryingUnresolvedSend = false) }
            }
        }
    }

    /**
     * Refuses a new hardware send while an earlier one may have gone out, saying so, and returns that earlier send to
     * finish first; null when there is none.
     */
    fun refuseNewSend(): HwSendResult? {
        val send = _uiState.value.unresolvedSend ?: return null
        viewModelScope.launch {
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.common__error),
                description = context.getString(R.string.wallet__send_pending__blocked_new_send),
            )
        }
        return send
    }

    private suspend fun completeUnresolvedSend(payment: PendingHwSendBroadcast, send: HwSendResult) {
        if (pendingBroadcast !== payment) return
        // Known to be sent now, so neither the wallet's observation nor a retry completes it a second time
        pendingBroadcast = payment.copy(isOutcomeUnknown = false)
        val signedTx = payment.signedTx
        val result = HwFundingBroadcastResult(send.txId, signedTx.miningFeeSats, signedTx.feeRate, signedTx.totalSpent)
        runSuspendCatching { persistResult(payment.request, result) }
            .onFailure { Logger.error("Failed to persist hardware send result", it, context = TAG) }
        releaseHeldSend(send.txId)
        _uiState.update { it.copy(unresolvedSend = null) }
        pendingResult.update { send }
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
        // A refused Shop payment may leave the sheet, but its signed operation stays guarded, as does a send whose
        // broadcast may have gone out.
        if (pendingBroadcast?.let {
                it.isOutcomeUnknown || it.request.paymentRequestId != null && it.hasAttemptedBroadcast
            } != true
        ) {
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
        unresolvedSendWatch?.cancel()
        unresolvedSendWatch = null
        pendingBroadcast = null
        signingWalletId = null
        pendingResult.update { null }
        _uiState.update {
            it.copy(
                hasPendingBroadcast = false,
                isBroadcastUnresolved = false,
                unresolvedSend = null,
            )
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

    private suspend fun handleFailure(error: Throwable, walletId: String) {
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
        // The payment shows as pending until the wallet sees it, so it is not offered to be tried again
        if (pendingBroadcast?.isOutcomeUnknown == true) {
            Logger.warn("Held hardware send of '$walletId' after an unknown broadcast outcome", context = TAG)
            return
        }
        when {
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
                if (pendingBroadcast != null && !retainedShopPayment) {
                    pendingBroadcast = null
                    _uiState.update { it.copy(hasPendingBroadcast = false) }
                }
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
    /** A send whose broadcast may have gone out: it is pending until it is sent, and nothing is signed beside it. */
    val unresolvedSend: HwSendResult? = null,
    val isRetryingUnresolvedSend: Boolean = false,
) {
    /**
     * Whether the sign sheet may be dismissed. Connecting or unlocking can be abandoned, and leaving
     * cancels it. A definite refusal may dismiss the sheet while the signed payment remains guarded.
     * Active signing and uncertain broadcasts still prevent dismissal.
     */
    val canLeave: Boolean
        get() = (!isSigning || isConnectingDevice) && !isBroadcastUnresolved
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

private data class PendingHwSendBroadcast(
    val request: HwSendRequest,
    val signedTx: HwFundingSignedTx,
    val isPreparedForBroadcast: Boolean = false,
    val hasAttemptedBroadcast: Boolean = false,
    val isRefusedForNavigation: Boolean = false,
    val isOutcomeUnknown: Boolean = false,
) {
    val blocksNavigation: Boolean get() = hasAttemptedBroadcast && !isRefusedForNavigation

    fun matches(request: HwSendRequest): Boolean = this.request == request ||
        hasAttemptedBroadcast && this.request.paymentRequestId != null &&
        this.request.copy(satsPerVByte = request.satsPerVByte, tags = request.tags) == request

    fun toHeldSend(txId: String) = HeldHwSend(
        walletId = request.walletId,
        txId = txId,
        address = request.address,
        amountSats = request.amountSats,
        satsPerVByte = request.satsPerVByte,
        tags = request.tags,
        serializedTx = signedTx.serializedTx,
        miningFeeSats = signedTx.miningFeeSats,
        feeRate = signedTx.feeRate,
        totalSpent = signedTx.totalSpent,
    )
}

private fun HeldHwSend.toPendingBroadcast(request: HwSendRequest) = PendingHwSendBroadcast(
    request = request.copy(satsPerVByte = satsPerVByte, tags = tags),
    signedTx = HwFundingSignedTx(serializedTx, miningFeeSats, feeRate, totalSpent),
    isPreparedForBroadcast = true,
)
