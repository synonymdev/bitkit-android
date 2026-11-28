package to.bitkit.wear.ui.screens.receive

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.wear.env.Env
import to.bitkit.wear.models.ReceiveInvoice
import to.bitkit.wear.models.WatchPayment
import to.bitkit.wear.repositories.PaymentsRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.ui.screens.home.DisplayAmount
import to.bitkit.wear.ui.screens.home.bitcoinAmount
import to.bitkit.wear.utils.Logger
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The receive QR: an amountless invoice made once the wallet is ready, shown until it is paid or nearly expired.
 * A nearly expired invoice is replaced by a new one and still watched until it expires; a paid one, shown or
 * replaced, turns into a check until the wearer asks for a new invoice.
 */
@HiltViewModel
class ReceiveViewModel internal constructor(
    private val paymentsRepo: PaymentsRepo,
    private val walletRepo: WalletRepo,
    private val nowSecs: () -> Long,
) : ViewModel() {
    companion object {
        private const val TAG = "ReceiveViewModel"

        /** How long before its expiry an invoice is replaced, so a payer never scans one that is about to expire. */
        val EXPIRY_MARGIN = 60.seconds

        /**
         * The shortest time an invoice shows before it is replaced, so a watch whose clock runs ahead of Spark's
         * does not make invoices one after another.
         */
        val MIN_LIFETIME = 30.seconds

        /** How long after a failure the replacement of an invoice that is still valid is tried again. */
        val RETRY_DELAY = 5.seconds
    }

    @Inject
    constructor(
        paymentsRepo: PaymentsRepo,
        walletRepo: WalletRepo,
    ) : this(paymentsRepo, walletRepo, { Instant.now().epochSecond })

    private val _uiState = MutableStateFlow(ReceiveUiState())
    val uiState: StateFlow<ReceiveUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    /** Makes a new invoice: after an error, and after the last one was paid. */
    fun onRefreshClick() = refresh()

    private fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _uiState.update { ReceiveUiState(isLoading = true) }
            val paid = CompletableDeferred<WatchPayment>()
            val watcherScope = this
            val rotation = launch { rotate(watcherScope, paid) }
            val payment = paid.await()
            rotation.cancelAndJoin()
            coroutineContext.cancelChildren()
            Logger.info("Received payment '${payment.id}'", context = TAG)
            _uiState.update { ReceiveUiState(paidAmount = bitcoinAmount(payment.amountSats.toLong())) }
        }
    }

    /**
     * Shows one invoice after another, each replaced shortly before it expires. Every invoice is watched in
     * [watcherScope] until it expires, also after its replacement shows, so a payer who scanned the earlier QR
     * still completes [paid].
     */
    private suspend fun rotate(watcherScope: CoroutineScope, paid: CompletableDeferred<WatchPayment>) {
        while (true) {
            // The invoice on screen stays until its replacement exists, or until it is about to expire.
            val isShowing = _uiState.value.qrContent != null
            val created = (if (isShowing) replace() else null) ?: run {
                _uiState.update { ReceiveUiState(isLoading = true) }
                create()
            }
            val invoice = created.getOrElse {
                _uiState.update { ReceiveUiState(isError = true) }
                return
            }
            if (Env.isDebug) Logger.debug("Showing invoice '${invoice.bolt11}'", context = TAG)
            _uiState.update { ReceiveUiState(qrContent = invoice.bolt11.uppercase()) }
            val expiresIn = (invoice.expiresAtSecs.toLong() - nowSecs()).seconds
            val lifetime = (expiresIn - EXPIRY_MARGIN).coerceAtLeast(MIN_LIFETIME)
            watcherScope.launch {
                withTimeoutOrNull(expiresIn.coerceAtLeast(lifetime + EXPIRY_MARGIN)) { paymentsRepo.awaitPaid(invoice) }
                    ?.let { paid.complete(it) }
            }
            delay(lifetime)
            Logger.debug("Replacing invoice '${invoice.paymentHash}' before it expires", context = TAG)
            _uiState.update { it.copy(isLoading = true) }
        }
    }

    /**
     * Makes the invoice that replaces the one on screen, trying again after a failure for as long as that one is
     * valid. Null when none could be made within [EXPIRY_MARGIN].
     */
    private suspend fun replace(): Result<ReceiveInvoice>? = withTimeoutOrNull(EXPIRY_MARGIN) {
        var created = create()
        while (created.isFailure) {
            delay(RETRY_DELAY)
            created = create()
        }
        created
    }

    /** Makes an invoice once the wallet is ready. */
    private suspend fun create(): Result<ReceiveInvoice> {
        walletRepo.walletState.first { it.status == WalletStatus.Ready }
        return paymentsRepo.createInvoice()
    }
}

@Immutable
data class ReceiveUiState(
    /** The uppercased invoice the QR shows, or null while there is none to show. */
    val qrContent: String? = null,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    /** What the paid invoice brought in, shown with the check. */
    val paidAmount: DisplayAmount? = null,
)
