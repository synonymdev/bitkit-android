package to.bitkit.ui.screens.wallets.send

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.R
import to.bitkit.data.HeldHwSend
import to.bitkit.ext.isBroadcastConnectivityFailure
import to.bitkit.ext.isHardwareBroadcastInputsSpent
import to.bitkit.ext.isHardwareBroadcastNeverConfirmable
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
import to.bitkit.repositories.HwHeldDispatch
import to.bitkit.repositories.HwHeldDispatchTracker
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
import to.bitkit.utils.AppError
import to.bitkit.utils.HwErrorPresenter
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import to.bitkit.utils.SignedTransactionId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.ContinuationInterceptor
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
    private val heldDispatches: HwHeldDispatchTracker,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private companion object {
        const val TAG = "HwSendViewModel"
        val COMPOSE_TIMEOUT = 45.seconds
        val SIGN_TIMEOUT = 120.seconds

        /** How long the sheet waits for a broadcast before showing it as pending; the broadcast itself runs on. */
        val BROADCAST_WAIT = 60.seconds
    }

    val wallets: StateFlow<ImmutableList<HwWallet>>
        get() = hwWalletRepo.wallets

    private val _uiState = MutableStateFlow(HwSendUiState())
    val uiState = _uiState.asStateFlow()

    private val pendingResult = MutableStateFlow<HwSendResult?>(null)
    val results = pendingResult.filterNotNull()

    private val _heldSendEvents = MutableSharedFlow<HeldHwSendEvent>(extraBufferCapacity = 8)

    /** Outcomes of held sends, which can arrive long after their sheet closed, so they are never replayed. */
    val heldSendEvents: SharedFlow<HeldHwSendEvent> = _heldSendEvents.asSharedFlow()

    private var pendingBroadcast: PendingHwSendBroadcast? = null
    private val unresolvedSends = mutableMapOf<String, UnresolvedHwSend>()
    private var signingWalletId: String? = null
    private var signingJob: Job? = null
    private var signingAttempt = 0
    private var passphraseJob: Job? = null

    init {
        dropUnresolvedSendsOfRemovedWallets()
        viewModelScope.launch {
            heldDispatches.inFlight.collect { txIds -> _uiState.update { it.copy(retryingSendTxIds = txIds) } }
        }
    }

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
                    // A payment whose broadcast may have gone out is only ever broadcast again as the same bytes
                    unresolvedSendFor(request)?.let { unresolved ->
                        Logger.warn("Broadcasting held hardware send of '${request.walletId}' again", context = TAG)
                        _uiState.update { it.copy(unresolvedSend = unresolved.send) }
                        retryUnresolvedSend(unresolved.send.txId)
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
                    if (held != null && isSeenByWallet(request.walletId, held.txId)) {
                        // It went out, so it is recorded and shown as sent; only a new send signs it again
                        Logger.info("Recorded held hardware send '${held.txId}' the wallet has seen", context = TAG)
                        val heldPayment = held.toPendingBroadcast(request)
                        val signedTx = heldPayment.signedTx
                        val result = HwFundingBroadcastResult(
                            txId = held.txId,
                            miningFeeSats = signedTx.miningFeeSats,
                            feeRate = signedTx.feeRate,
                            totalSpent = signedTx.totalSpent,
                        )
                        runSuspendCatching { persistResult(heldPayment.request, result) }
                            .onFailure { Logger.error("Failed to persist hardware send result", it, context = TAG) }
                        releaseHeldSend(held.txId)
                        pendingResult.update {
                            HwSendResult(
                                walletId = request.walletId,
                                txId = held.txId,
                                amountSats = request.amountSats,
                                paymentIdentity = request.paymentIdentity,
                            )
                        }
                        return@runCatching
                    }
                    if (held != null) {
                        Logger.warn("Broadcasting held hardware send of '${request.walletId}' again", context = TAG)
                        retryUnresolvedSend(watchUnresolvedSend(held.toPendingBroadcast(request)).send.txId)
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
                    if (signingAttempt == attempt) {
                        runSuspendCatching { persistResult(request, result) }
                            .onFailure { Logger.error("Failed to persist hardware send result", it, context = TAG) }
                    }
                    // Released once the send is recorded, so an app killed before that still holds it
                    if (request.paymentRequestId == null) {
                        releaseHeldSend(SignedTransactionId.fromHex(payment.signedTx.serializedTx))
                    }
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
        _uiState.update { it.copy(isBroadcastUnresolved = true) }
        // Written before the broadcast, so a send that may have gone out stays held even if the app dies during it
        if (requestId == null) hwWalletRepo.holdSend(payment.toHeldSend(txId)).getOrThrow()
        // The broadcast runs to its end on the core queue, so it is never cancelled: the sheet stops waiting for it
        // after a while, and its answer still settles the send
        val tracked = if (requestId == null) {
            checkNotNull(heldDispatches.begin(txId)) { "A broadcast of hardware send '$txId' is unanswered" }
        } else {
            null
        }
        val isDispatched = AtomicBoolean(false)
        val isAbandoned = AtomicBoolean(false)
        val sheetDispatcher = requireNotNull(currentCoroutineContext()[ContinuationInterceptor])
        val dispatch = viewModelScope.async {
            hwWalletRepo.broadcastFundingAtBoundary(payment.signedTx, request.paymentDeadlineAt) {
                markDispatched(payment, isDispatched)
                withContext(sheetDispatcher) {
                    if (!isAbandoned.get()) {
                        pendingBroadcast = payment.copy(hasAttemptedBroadcast = true, isRefusedForNavigation = false)
                    }
                }
            }
        }
        // A dispatch the sheet stops waiting for is settled by its answer, or counts as possibly sent if it has none
        tracked?.let { dispatch.invokeOnCompletion { cause -> if (cause != null) heldDispatches.finish(it, false) } }
        val outcome = withTimeoutOrNull(BROADCAST_WAIT) { dispatch.await() }
            ?: return abandonBroadcastWait(payment, dispatch, isAbandoned, isDispatched, tracked)
        val error = outcome.exceptionOrNull()
        val isDeadlineExpired = error != null &&
            generateSequence(error) { it.cause }.any { it is ServiceError.PaymentDeadlineExpired }
        // A failure before the dispatch sent nothing, while one after it may follow the write
        val isNotSent = error != null && (!isDispatched.get() || error.isFirstHardwareBroadcastNotSent())
        tracked?.let { heldDispatches.finish(it, isNotSent) }
        val isOnlyPossibleSend = tracked?.let { heldDispatches.isOnlyPossibleSend(it) } != false
        val isOutcomeUnknown = requestId == null && error != null && !isDeadlineExpired &&
            !(isNotSent && isOnlyPossibleSend)
        if (isOutcomeUnknown && isProvenNeverConfirming(payment, requireNotNull(error))) {
            Logger.warn("Released hardware send '$txId' the network rejected for good", context = TAG)
            releaseHeldSend(txId)
            pendingBroadcast = null
            _uiState.update { it.copy(hasPendingBroadcast = false, isBroadcastUnresolved = false) }
            toastNotSent(R.string.hardware__send_broadcast_rejected_text)
            return null
        }
        if (isOutcomeUnknown) {
            Logger.warn("Held hardware send '$txId' after an unknown broadcast outcome", context = TAG)
            watchUnresolvedSend(payment)
            _uiState.update { it.copy(isBroadcastUnresolved = false) }
        } else if (requestId == null && error != null && !isDeadlineExpired) {
            releaseHeldSend(txId)
        }
        return outcome.getOrElse { error ->
            if (isDeadlineExpired) {
                val cleared = requestId?.let {
                    !payment.hasAttemptedBroadcast &&
                        paykitPaymentProofRepo.clearHardwareOnchainCandidateBeforeDispatch(
                            it, request.walletId, txId,
                            request.paymentIdentity, request.address, request.amountSats, payment.hasAttemptedBroadcast,
                        )
                } ?: !payment.hasAttemptedBroadcast
                val unresolved = payment.hasAttemptedBroadcast || !cleared
                // An expiry never releases a send whose earlier broadcast may have gone out
                if (requestId == null && !unresolved) releaseHeldSend(txId)
                pendingBroadcast = payment.copy(hasAttemptedBroadcast = unresolved)
                _uiState.update { it.copy(isBroadcastUnresolved = unresolved && !payment.isRefusedForNavigation) }
                onPaymentDeadlineExpired(unresolved)
                return null
            }
            if (isOutcomeUnknown) return null
            throw error
        }
    }

    private suspend fun markDispatched(payment: PendingHwSendBroadcast, isDispatched: AtomicBoolean) {
        val request = payment.request
        request.paymentRequestId?.let {
            check(
                paykitPaymentProofRepo.markHardwareOnchainDispatch(
                    it,
                    request.walletId,
                    SignedTransactionId.fromHex(payment.signedTx.serializedTx),
                    request.paymentIdentity,
                    request.address,
                    request.amountSats,
                )
            ) { "Original hardware payment is no longer eligible for dispatch" }
        }
        isDispatched.set(true)
    }

    private fun abandonBroadcastWait(
        payment: PendingHwSendBroadcast,
        dispatch: Deferred<Result<HwFundingBroadcastResult>>,
        isAbandoned: AtomicBoolean,
        isDispatched: AtomicBoolean,
        tracked: HwHeldDispatch?,
    ): HwFundingBroadcastResult? {
        isAbandoned.set(true)
        if (payment.request.paymentRequestId != null) {
            // A Shop payment keeps its retained receipt and its guarded sheet, and its late answer still lands
            val attempted = payment.copy(hasAttemptedBroadcast = true, isRefusedForNavigation = false)
            pendingBroadcast = attempted
            viewModelScope.launch { settleLateShopBroadcast(attempted, dispatch.await()) }
            throw HwBroadcastWaitExpiredError()
        }
        val unresolved = watchUnresolvedSend(payment)
        Logger.warn("Stopped waiting for broadcast of held hardware send '${unresolved.send.txId}'", context = TAG)
        _uiState.update { it.copy(isBroadcastUnresolved = false) }
        viewModelScope.launch {
            settleLateBroadcast(unresolved, dispatch.await(), isDispatched.get(), requireNotNull(tracked))
        }
        return null
    }

    private suspend fun settleLateBroadcast(
        unresolved: UnresolvedHwSend,
        outcome: Result<HwFundingBroadcastResult>,
        isDispatched: Boolean,
        tracked: HwHeldDispatch,
    ) {
        val txId = unresolved.send.txId
        val error = outcome.exceptionOrNull()
        val isNotSent = error != null && (!isDispatched || error.isFirstHardwareBroadcastNotSent())
        heldDispatches.finish(tracked, isNotSent)
        // An accepted broadcast stays pending until the wallet sees its transaction
        if (error == null) {
            Logger.info("Accepted late broadcast of held hardware send '$txId'", context = TAG)
            return
        }
        if (releaseIfNeverConfirming(unresolved, error)) return
        // Proof that this broadcast sent nothing says nothing about any other dispatch of the same bytes
        if (isNotSent && heldDispatches.isOnlyPossibleSend(tracked)) {
            releaseUnresolvedSend(unresolved, R.string.hardware__send_not_sent_text)
            return
        }
        Logger.warn("Kept held hardware send '$txId' after a late failure", error, context = TAG)
    }

    private suspend fun settleLateShopBroadcast(
        payment: PendingHwSendBroadcast,
        outcome: Result<HwFundingBroadcastResult>,
    ) {
        // A later attempt or an exact observation may have settled this payment meanwhile
        if (pendingBroadcast?.signedTx != payment.signedTx || pendingResult.value != null) return
        val request = payment.request
        outcome
            .onSuccess { result ->
                runSuspendCatching { persistResult(request, result) }
                    .onFailure { Logger.error("Failed to persist hardware send result", it, context = TAG) }
                pendingResult.update {
                    HwSendResult(
                        walletId = request.walletId,
                        txId = result.txId,
                        amountSats = request.amountSats,
                        paymentRequestId = request.paymentRequestId,
                        paymentIdentity = request.paymentIdentity,
                    )
                }
            }
            .onFailure { handleFailure(it, request.walletId) }
    }

    private fun Throwable.isFirstHardwareBroadcastNotSent(): Boolean =
        isHardwareBroadcastNotSent() || isHardwareBroadcastRefusalForNavigation()

    private suspend fun isProvenNeverConfirming(payment: PendingHwSendBroadcast, error: Throwable): Boolean {
        if (payment.request.paymentRequestId != null) return false
        if (error.isHardwareBroadcastNeverConfirmable()) return true
        if (!error.isHardwareBroadcastInputsSpent()) return false
        // A coin missing to this node proves nothing until a final spend of it by another transaction shows up
        return hwWalletRepo.isSpentByAnotherTransaction(payment.request.walletId, payment.signedTx.serializedTx)
            .onFailure { Logger.warn("Failed to read spends of hardware send coins", it, context = TAG) }
            .getOrDefault(false)
    }

    private suspend fun releaseIfNeverConfirming(unresolved: UnresolvedHwSend, error: Throwable): Boolean {
        if (!isProvenNeverConfirming(unresolved.payment, error)) return false
        if (unresolvedSends[unresolved.send.txId] !== unresolved) return true
        // A transaction the wallet has seen went out, whatever a node says about it now
        if (isSeenByWallet(unresolved.send.walletId, unresolved.send.txId)) {
            completeUnresolvedSend(unresolved)
            return true
        }
        releaseUnresolvedSend(unresolved, R.string.hardware__send_broadcast_rejected_text)
        return true
    }

    private suspend fun releaseUnresolvedSend(unresolved: UnresolvedHwSend, @StringRes description: Int) {
        if (unresolvedSends[unresolved.send.txId] !== unresolved) return
        Logger.warn("Released held hardware send '${unresolved.send.txId}' that was never sent", context = TAG)
        forgetUnresolvedSend(unresolved)
        releaseHeldSend(unresolved.send.txId)
        toastNotSent(description)
        _heldSendEvents.emit(HeldHwSendEvent.Released(unresolved.send))
    }

    private fun isSeenByWallet(walletId: String, txId: String): Boolean =
        hwWalletRepo.activities.value.filterIsInstance<Activity.Onchain>().any {
            it.v1.walletId == walletId && it.v1.txId.equals(txId, ignoreCase = true)
        }

    private suspend fun toastNotSent(@StringRes description: Int) = ToastEventBus.send(
        type = Toast.ToastType.ERROR,
        title = context.getString(R.string.hardware__send_not_sent_title),
        description = context.getString(description),
    )

    private suspend fun releaseHeldSend(txId: String) {
        hwWalletRepo.releaseHeldSend(txId)
            .onFailure { Logger.warn("Failed to release held hardware send '$txId'", it, context = TAG) }
    }

    private fun watchUnresolvedSend(payment: PendingHwSendBroadcast): UnresolvedHwSend {
        val request = payment.request
        val txId = SignedTransactionId.fromHex(payment.signedTx.serializedTx).lowercase()
        val send = HwSendResult(request.walletId, txId, request.amountSats, paymentIdentity = request.paymentIdentity)
        // The held send leaves the sheet's attempt, so another payment can be signed beside it
        if (pendingBroadcast?.signedTx == payment.signedTx) pendingBroadcast = null
        val unresolved = UnresolvedHwSend(payment, send)
        unresolvedSends.put(txId, unresolved)?.watch?.cancel()
        unresolved.watch = viewModelScope.launch {
            hwWalletRepo.activities.first { activities ->
                activities.filterIsInstance<Activity.Onchain>().any {
                    it.v1.walletId == send.walletId && it.v1.txId.equals(send.txId, ignoreCase = true)
                }
            }
            completeUnresolvedSend(unresolved)
        }
        _uiState.update {
            it.copy(
                hasPendingBroadcast = pendingBroadcast != null,
                unresolvedSend = send,
                unresolvedSends = unresolvedSends.values.map { held -> held.send }.toImmutableList(),
            )
        }
        return unresolved
    }

    private fun unresolvedSendFor(request: HwSendRequest): UnresolvedHwSend? =
        unresolvedSends.values.firstOrNull { it.payment.request.isPaymentOf(request) }

    private fun dropUnresolvedSendsOfRemovedWallets() {
        viewModelScope.launch {
            var knownWalletIds = emptySet<String>()
            hwWalletRepo.wallets.collect { wallets ->
                val walletIds = wallets.mapTo(mutableSetOf()) { it.id }
                val removed = knownWalletIds - walletIds
                knownWalletIds = walletIds
                // A removed wallet's send stays held in the store, which guards it again once the wallet is back
                unresolvedSends.values.filter { it.send.walletId in removed }.forEach { forgetUnresolvedSend(it) }
            }
        }
    }

    private fun forgetUnresolvedSend(unresolved: UnresolvedHwSend) {
        if (unresolvedSends[unresolved.send.txId] !== unresolved) return
        unresolvedSends.remove(unresolved.send.txId)
        unresolved.watch?.cancel()
        _uiState.update { state ->
            state.copy(
                unresolvedSend = state.unresolvedSend?.takeUnless { it.txId == unresolved.send.txId },
                unresolvedSends = unresolvedSends.values.map { it.send }.toImmutableList(),
            )
        }
    }

    /**
     * Broadcasts the identical signed bytes of the held send [txId] again, never signing it again. An accepted
     * broadcast stays pending until the wallet sees the transaction, and only a refusal proving it can never confirm
     * releases it, since an earlier broadcast may still confirm.
     */
    fun retryUnresolvedSend(txId: String) {
        val unresolved = unresolvedSends[txId.lowercase()] ?: return
        val send = unresolved.send
        // One dispatch of the same bytes at a time, from any sheet
        val tracked = heldDispatches.begin(send.txId) ?: return
        viewModelScope.launch {
            var isNotSent = false
            try {
                // Written again first, so the send stays held whatever happened to the record since
                hwWalletRepo.holdSend(unresolved.payment.toHeldSend(send.txId)).onFailure {
                    isNotSent = true
                    Logger.warn("Skipped broadcast again of '${send.txId}' without its hold", it, context = TAG)
                    toastBroadcastFailed()
                    return@launch
                }
                val outcome = hwWalletRepo.broadcastFunding(unresolved.payment.signedTx)
                isNotSent = outcome.exceptionOrNull()?.isHardwareBroadcastNotSent() == true
                heldDispatches.finish(tracked, isNotSent)
                outcome
                    .onSuccess {
                        ToastEventBus.send(
                            type = Toast.ToastType.INFO,
                            title = context.getString(R.string.hardware__send_broadcast_accepted),
                        )
                    }
                    .onFailure {
                        if (unresolvedSends[send.txId] !== unresolved) return@onFailure
                        if (releaseIfNeverConfirming(unresolved, it)) return@onFailure
                        ToastEventBus.send(
                            type = Toast.ToastType.WARNING,
                            title = context.getString(R.string.hardware__send_broadcast_failed_title),
                            description = context.getString(R.string.hardware__send_broadcast_refused_text),
                        )
                    }
            } finally {
                heldDispatches.finish(tracked, isNotSent)
            }
        }
    }

    private suspend fun toastBroadcastFailed() = ToastEventBus.send(
        type = Toast.ToastType.WARNING,
        title = context.getString(R.string.hardware__send_broadcast_failed_title),
        description = context.getString(R.string.hardware__send_broadcast_failed_text),
    )

    /**
     * Broadcasts again the held send of a payment whose earlier broadcast may have gone out, instead of signing it
     * anew, and returns it to show as pending; null when there is none.
     */
    fun resumeHeldSend(walletId: String, address: String, amountSats: ULong): HwSendResult? {
        val send = unresolvedSends.values.firstOrNull { it.payment.request.isPaymentOf(walletId, address, amountSats) }
            ?.send ?: return null
        retryUnresolvedSend(send.txId)
        return send
    }

    private suspend fun completeUnresolvedSend(unresolved: UnresolvedHwSend) {
        // Known to be sent now, so neither the wallet's observation nor a retry completes it a second time
        if (unresolvedSends[unresolved.send.txId] !== unresolved) return
        forgetUnresolvedSend(unresolved)
        val send = unresolved.send
        val signedTx = unresolved.payment.signedTx
        val result = HwFundingBroadcastResult(send.txId, signedTx.miningFeeSats, signedTx.feeRate, signedTx.totalSpent)
        runSuspendCatching { persistResult(unresolved.payment.request, result) }
            .onFailure { Logger.error("Failed to persist hardware send result", it, context = TAG) }
        releaseHeldSend(send.txId)
        _heldSendEvents.emit(HeldHwSendEvent.Completed(send))
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
        _uiState.update { it.copy(isPassphraseRequired = false, isVerifyingPassphrase = false, unresolvedSend = null) }
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
            pendingBroadcast != null && (
                error.isBroadcastConnectivityFailure() ||
                    error is TimeoutCancellationException ||
                    error is HwBroadcastWaitExpiredError
                ) -> ToastEventBus.send(
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
    /** The held send this sheet's attempt ran into, which the sheet shows as pending. */
    val unresolvedSend: HwSendResult? = null,
    /** Sends whose broadcast may have gone out: each is pending until it is sent, and is never signed again. */
    val unresolvedSends: ImmutableList<HwSendResult> = persistentListOf(),
    val retryingSendTxIds: ImmutableSet<String> = persistentSetOf(),
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

sealed interface HeldHwSendEvent {
    val send: HwSendResult

    data class Completed(override val send: HwSendResult) : HeldHwSendEvent

    /** The network rejected the send for good, so it was never sent and is no longer held. */
    data class Released(override val send: HwSendResult) : HeldHwSendEvent
}

data class HwSendRequest(
    val walletId: String,
    val address: String,
    val amountSats: ULong,
    val satsPerVByte: ULong,
    val tags: List<String>,
    val paymentRequestId: PaykitPaymentRequestId? = null,
    val paymentIdentity: String? = null,
    val paymentDeadlineAt: Instant? = null,
) {
    fun isPaymentOf(walletId: String, address: String, amountSats: ULong): Boolean =
        this.walletId == walletId && this.address.equals(address, ignoreCase = true) && this.amountSats == amountSats

    fun isPaymentOf(other: HwSendRequest): Boolean = isPaymentOf(other.walletId, other.address, other.amountSats)
}

private class HwBroadcastWaitExpiredError : AppError("Hardware broadcast did not answer in time")

private class UnresolvedHwSend(val payment: PendingHwSendBroadcast, val send: HwSendResult) {
    var watch: Job? = null
}

private data class PendingHwSendBroadcast(
    val request: HwSendRequest,
    val signedTx: HwFundingSignedTx,
    val isPreparedForBroadcast: Boolean = false,
    val hasAttemptedBroadcast: Boolean = false,
    val isRefusedForNavigation: Boolean = false,
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
