package to.bitkit.ui.screens.wallets.receive

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.models.DIRECT_FUNDING_MIN_SATS
import to.bitkit.models.hasCommittedFunding
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@HiltViewModel
class DirectFundingReceiveViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    settingsStore: SettingsStore,
    private val clock: Clock,
) : ViewModel() {
    companion object {
        private const val TAG = "DirectFundingReceiveViewModel"
    }

    private var lastRequestId: String? = null
    private var expiryJob: Job? = null
    private val input = MutableStateFlow<DirectFundingReceiveInput?>(null)
    private val _request = MutableStateFlow<DirectFundingReceiveAdvertisement?>(null)
    val request = _request.asStateFlow()

    init {
        viewModelScope.launch {
            combine(input, settingsStore.data, lightningRepo.lightningState) { current, settings, lightning ->
                val primary = settings.primaryNodeUri
                current?.takeIf {
                    it.address.isNotBlank() && (it.amount == null || it.amount >= DIRECT_FUNDING_MIN_SATS) &&
                        primary.isNotBlank() && lightning.nodeLifecycleState.isRunning() &&
                        lightning.peers.any { peer ->
                            peer.nodeId == primary.substringBefore('@') && peer.isConnected
                        }
                }?.copy(primary = primary)
            }.distinctUntilChanged().collectLatest { current ->
                expiryJob?.cancel()
                _request.update { null }
                if (current == null) return@collectLatest
                val details = lightningRepo.createDirectFundingRequest(current.amount).getOrElse {
                    Logger.warn("Could not advertise direct funding request", it, context = TAG)
                    return@collectLatest
                }
                lastRequestId = details.requestId
                walletRepo.retainDirectFundingReceive(
                    details,
                    current.invoice,
                    current.address,
                    current.primary,
                ).getOrElse {
                    Logger.warn("Could not retain direct funding receive association", it, context = TAG)
                    lightningRepo.invalidateDirectFundingRequest(details.requestId)
                    return@collectLatest
                }
                expiryJob = viewModelScope.launch {
                    val remaining = details.expiresAt.toLong() - clock.now().toEpochMilliseconds()
                    if (remaining > 0) delay(remaining.milliseconds)
                    _request.update { it?.takeUnless { advertisement -> advertisement.request == details.request } }
                }
                var keepAdvertising = true
                while (keepAdvertising) {
                    val payments = lightningRepo.listDirectFundingPayments().getOrNull()
                    val committed = payments?.any {
                        it.requestId == details.requestId && it.status.hasCommittedFunding(isOutbound = false)
                    } == true
                    val remaining = details.expiresAt.toLong() - clock.now().toEpochMilliseconds()
                    keepAdvertising = remaining > 0 && payments != null && !committed
                    if (committed) walletRepo.consumeDirectFundingReceive(details.requestId)
                    if (keepAdvertising) {
                        _request.update {
                            DirectFundingReceiveAdvertisement(
                                details.request,
                                current.amount,
                                current.address,
                                current.invoice,
                            )
                        }
                        delay(minOf(5.seconds, remaining.milliseconds))
                    }
                }
                _request.update { null }
            }
        }
    }

    fun update(amount: ULong?, address: String, invoice: String) {
        val next = DirectFundingReceiveInput(amount?.takeUnless { it == 0uL }, address, invoice)
        if (input.value != null && input.value != next) {
            val previousId = lastRequestId
            _request.update { null }
            if (previousId != null) {
                viewModelScope.launch {
                    walletRepo.invalidateDirectFundingReceive(previousId).onFailure {
                        Logger.warn("Could not invalidate replaced direct funding request", it, context = TAG)
                    }
                }
            }
        }
        input.update { next }
    }

    fun clear() {
        expiryJob?.cancel()
        lastRequestId = null
        input.update { null }
        _request.update { null }
    }
}

private data class DirectFundingReceiveInput(
    val amount: ULong?,
    val address: String,
    val invoice: String,
    val primary: String = "",
)

@Immutable
data class DirectFundingReceiveAdvertisement(
    val request: String,
    val amount: ULong?,
    val address: String,
    val invoice: String,
) {
    fun requestFor(amount: ULong?, address: String, invoice: String): String? = request.takeIf {
        this.amount == amount?.takeUnless { it == 0uL } && this.address == address && this.invoice == invoice
    }
}
