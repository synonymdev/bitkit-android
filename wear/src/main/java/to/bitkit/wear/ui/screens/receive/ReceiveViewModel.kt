package to.bitkit.wear.ui.screens.receive

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.wear.env.Env
import to.bitkit.wear.models.ReceiveInvoice
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
 * A nearly expired invoice is replaced by a new one; a paid one turns into a check until the wearer asks for a new
 * invoice.
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
            while (true) {
                // The invoice on screen stays until its replacement exists, or until it is about to expire.
                val isShowing = _uiState.value.qrContent != null
                val replaced = if (isShowing) withTimeoutOrNull(EXPIRY_MARGIN) { create() } else null
                val created = replaced ?: run {
                    _uiState.update { ReceiveUiState(isLoading = true) }
                    create()
                }
                val invoice = created.getOrElse {
                    _uiState.update { ReceiveUiState(isError = true) }
                    return@launch
                }
                if (Env.isDebug) Logger.debug("Showing invoice '${invoice.bolt11}'", context = TAG)
                _uiState.update { ReceiveUiState(qrContent = invoice.bolt11.uppercase()) }
                val lifetime = (invoice.expiresAtSecs.toLong() - nowSecs()).seconds - EXPIRY_MARGIN
                val paid = withTimeoutOrNull(lifetime.coerceAtLeast(MIN_LIFETIME)) { paymentsRepo.awaitPaid(invoice) }
                if (paid != null) {
                    Logger.info("Received payment '${paid.id}'", context = TAG)
                    _uiState.update { ReceiveUiState(paidAmount = bitcoinAmount(paid.amountSats.toLong())) }
                    return@launch
                }
                Logger.debug("Replacing invoice '${invoice.paymentHash}' before it expires", context = TAG)
                _uiState.update { it.copy(isLoading = true) }
            }
        }
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
