package to.bitkit.ui.screens.wallets.receive

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.LightningInvoice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.primaryReceiveMessage
import to.bitkit.utils.Logger
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
@HiltViewModel
class PrimaryReceiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val settingsStore: SettingsStore,
    private val walletRepo: WalletRepo,
    private val clock: Clock,
) : ViewModel() {
    companion object {
        private const val TAG = "PrimaryReceiveViewModel"
        private const val MSAT_PER_SAT = 1000uL
        private const val MAX_INVOICE_LIFETIME_SECONDS = 600uL
    }

    private val _uiState = MutableStateFlow(PrimaryReceiveUiState())
    val uiState = _uiState.asStateFlow()
    private var operation: Job? = null
    private var expiryJob: Job? = null
    private var paymentHash: String? = null
    val hasPrimaryNode = settingsStore.data.map { it.primaryNodeUri.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        observeInvoiceInvalidation()
    }

    private fun observeInvoiceInvalidation() {
        viewModelScope.launch {
            walletRepo.walletState.map { it.lastDirectFundingReceiveRequestId }.distinctUntilChanged().collect {
                if (it != null) invalidateInvoice()
            }
        }
        viewModelScope.launch {
            settingsStore.data.map { it.primaryNodeUri }.distinctUntilChanged().collect { primaryUri ->
                val offer = _uiState.value.offer
                if (offer != null && offer.primaryUri != primaryUri) {
                    invalidateInvoice(context.getString(R.string.wallet_primary__receive_primary_changed))
                }
            }
        }
        viewModelScope.launch {
            lightningRepo.lightningState.collect { state ->
                val offer = _uiState.value.offer
                if (_uiState.value.invoice != null && offer != null && !state.canServe(offer)) invalidateInvoice()
            }
        }
        viewModelScope.launch {
            lightningRepo.nodeEvents.collect { event ->
                val settled = when (event) {
                    is Event.PaymentReceived -> event.paymentHash == paymentHash
                    is Event.OnchainTransactionReceived -> _uiState.value.onchainAddress?.let { address ->
                        event.details.outputs.any { it.scriptpubkeyAddress == address }
                    } == true
                    else -> false
                }
                if (settled) invalidateInvoice()
            }
        }
        viewModelScope.launch {
            walletRepo.walletState.map { it.onchainAddress }.distinctUntilChanged().collect { address ->
                val activeAddress = _uiState.value.onchainAddress
                if (activeAddress != null && address != activeAddress) invalidateInvoice()
            }
        }
    }

    fun requestQuote(amountSats: ULong, description: String, onQuoted: () -> Unit) {
        operation?.cancel()
        expiryJob?.cancel()
        paymentHash = null
        if (amountSats == 0uL || amountSats > ULong.MAX_VALUE / MSAT_PER_SAT) {
            clear()
            return
        }
        _uiState.update {
            PrimaryReceiveUiState(
                offer = it.offer?.takeIf { offer ->
                    offer.amountSats == amountSats && offer.description == description
                },
                isLoading = true,
            )
        }
        operation = viewModelScope.launch {
            runSuspendCatching {
                val primaryUri = settingsStore.data.first().primaryNodeUri
                val quote = lightningRepo.quotePrimaryReceive(amountSats).getOrThrow()
                if (quote.amountMsat != amountSats * MSAT_PER_SAT || quote.feeMsat >= quote.amountMsat ||
                    settingsStore.data.first().primaryNodeUri != primaryUri
                ) {
                    return@runSuspendCatching null
                }
                PrimaryReceiveOffer(amountSats, quote.feeMsat, description, primaryUri)
            }.onSuccess { offer ->
                _uiState.update {
                    it.copy(
                        offer = offer,
                        isLoading = false,
                        error = if (offer == null) context.getString(R.string.wallet_primary__receive_error) else null,
                    )
                }
                if (offer != null) onQuoted()
            }.onFailure { showFailure(it) }
        }
    }

    fun confirm(onCreated: (amountSats: ULong) -> Unit) {
        val offer = _uiState.value.offer ?: return
        if (_uiState.value.isLoading || _uiState.value.error != null || _uiState.value.invoice != null) return
        val address = walletRepo.walletState.value.onchainAddress
        _uiState.update { it.copy(isLoading = true, error = null) }
        operation = viewModelScope.launch {
            runSuspendCatching {
                check(settingsStore.data.first().primaryNodeUri == offer.primaryUri)
                val invoice = lightningRepo.createPrimaryInvoice(
                    amountSats = offer.amountSats,
                    description = offer.description,
                    maxFeeMsat = offer.maxFeeMsat,
                ).getOrThrow()
                val decoded = lightningRepo.decodePrimaryReceiveInvoice(invoice).getOrThrow()
                check(decoded.amountSatoshis == offer.amountSats && !decoded.isExpired)
                check(settingsStore.data.first().primaryNodeUri == offer.primaryUri)
                check(walletRepo.walletState.value.onchainAddress == address)
                check(lightningRepo.lightningState.value.canServe(offer))
                val remainingLifetime = invoiceLifetime(decoded)
                check(remainingLifetime.isPositive())
                paymentHash = decoded.paymentHash.toHex()
                _uiState.update {
                    it.copy(invoice = invoice, onchainAddress = address, isLoading = false, error = null)
                }
                expiryJob = viewModelScope.launch {
                    delay(remainingLifetime)
                    invalidateInvoice()
                }
            }.onSuccess { onCreated(offer.amountSats) }
                .onFailure { showFailure(it) }
        }
    }

    private fun invoiceLifetime(invoice: LightningInvoice): Duration {
        val now = clock.now()
        if (invoice.timestampSeconds > now.epochSeconds.coerceAtLeast(0).toULong()) return Duration.ZERO
        val lifetime = minOf(invoice.expirySeconds, MAX_INVOICE_LIFETIME_SECONDS)
        val expiresAt = Instant.fromEpochSeconds(invoice.timestampSeconds.toLong()) + lifetime.toLong().seconds
        return expiresAt - now
    }

    private fun LightningState.canServe(offer: PrimaryReceiveOffer): Boolean =
        nodeLifecycleState.isRunning() && peers.any {
            it.nodeId == offer.primaryUri.substringBefore('@') && it.isConnected
        }

    fun refreshQuote() {
        val offer = _uiState.value.offer ?: return
        requestQuote(offer.amountSats, offer.description, onQuoted = {})
    }

    fun clear() {
        operation?.cancel()
        expiryJob?.cancel()
        paymentHash = null
        _uiState.update { PrimaryReceiveUiState() }
    }

    private fun invalidateInvoice(error: String? = null) {
        operation?.cancel()
        expiryJob?.cancel()
        paymentHash = null
        _uiState.update { PrimaryReceiveUiState(offer = it.offer.takeIf { error != null }, error = error) }
    }

    private fun showFailure(error: Throwable) {
        Logger.error("Failed to create primary receive request", error, context = TAG)
        _uiState.update {
            it.copy(
                isLoading = false,
                error = error.primaryReceiveMessage(context)
                    ?: context.getString(R.string.wallet_primary__receive_error),
            )
        }
    }
}

@Immutable
data class PrimaryReceiveOffer(
    val amountSats: ULong,
    val maxFeeMsat: ULong,
    val description: String,
    val primaryUri: String,
) {
    val minimumReceiveMsat: ULong get() = amountSats * 1000uL - maxFeeMsat
}

@Immutable
data class PrimaryReceiveUiState(
    val offer: PrimaryReceiveOffer? = null,
    val invoice: String? = null,
    val onchainAddress: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

internal fun formatPrimaryReceiveMsat(msat: ULong, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 }
        .format(BigDecimal(msat.toString()).movePointLeft(3))
