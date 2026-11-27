package to.bitkit.wear.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.wear.data.ForegroundMonitor
import to.bitkit.wear.data.NetworkMonitor
import to.bitkit.wear.data.NfcMonitor
import to.bitkit.wear.data.NfcState
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.BITCOIN_SYMBOL
import to.bitkit.wear.models.FxRate
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.models.fiatValue
import to.bitkit.wear.models.formatCurrency
import to.bitkit.wear.models.formatToModernDisplay
import to.bitkit.wear.models.isSymbolSuffix
import to.bitkit.wear.repositories.CurrencyRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.repositories.rateFor
import to.bitkit.wear.utils.Logger
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The home screen's state, and what keeps it fresh while Home is open: it starts the wallet and fetches the exchange
 * rates when the app comes on screen and every [RATES_REFRESH_INTERVAL] from then, when the wallet becomes ready,
 * and when the network returns while there are no rates yet.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
    private val currencyRepo: CurrencyRepo,
    settingsStore: SettingsStore,
    nfcMonitor: NfcMonitor,
    networkMonitor: NetworkMonitor,
    foregroundMonitor: ForegroundMonitor,
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private val STOP_TIMEOUT = 5.seconds

        /** How often the exchange rates are fetched again while the app is on screen. */
        val RATES_REFRESH_INTERVAL = 5.minutes
    }

    val uiState: StateFlow<HomeUiState> = combine(
        walletRepo.walletState,
        currencyRepo.rates,
        settingsStore.data,
        nfcMonitor.state,
    ) { wallet, rates, settings, nfc ->
        homeUiState(wallet, rates, settings, nfc = nfc)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), HomeUiState())

    init {
        start()
        viewModelScope.launch {
            foregroundMonitor.isForeground.collectLatest { isForeground ->
                while (isForeground) {
                    fetchRates()
                    delay(RATES_REFRESH_INTERVAL)
                }
            }
        }
        viewModelScope.launch {
            walletRepo.walletState
                .map { it.status == WalletStatus.Ready }
                .distinctUntilChanged()
                .collect { isReady -> if (isReady) fetchRates() }
        }
        viewModelScope.launch {
            // Only a change counts as the network returning, so the value at the start is skipped.
            networkMonitor.isOnline.drop(1).collect { isOnline ->
                if (isOnline && currencyRepo.rates.value.isEmpty()) fetchRates()
            }
        }
    }

    /** Retries after the error, and from the offline icon. */
    fun onRetryClick() {
        start()
        viewModelScope.launch { fetchRates() }
    }

    /** Swaps which unit the balance shows first. */
    fun onUnitToggle() {
        viewModelScope.launch { currencyRepo.togglePrimaryDisplay() }
    }

    private fun start() {
        viewModelScope.launch { walletRepo.start() }
    }

    private suspend fun fetchRates() {
        currencyRepo.refresh().onFailure { Logger.warn("Failed to refresh rates", it, context = TAG) }
    }
}

private val loadingStatuses = setOf(WalletStatus.Idle, WalletStatus.Connecting, WalletStatus.Paused)

/**
 * The home screen's state for [wallet], showing amounts in [settings]' order with the matching rate in [rates], and
 * carrying the [nfc] state for the NFC hint.
 */
internal fun homeUiState(
    wallet: WalletState,
    rates: List<FxRate>,
    settings: WatchSettings,
    locale: Locale = Locale.getDefault(),
    nfc: NfcState = NfcState.Unknown,
): HomeUiState {
    // A balance no sync confirmed this session, such as 0 for a wallet restored offline, is never shown.
    val sats = wallet.balanceSats?.takeIf { wallet.hasSynced }?.toLong()
    val bitcoin = sats?.let { DisplayAmount(BITCOIN_SYMBOL, it.formatToModernDisplay(locale)) }
    val fiat = sats?.let { amount ->
        rates.rateFor(settings.selectedCurrency)?.let { rate ->
            fiatValue(amount, rate)?.let {
                DisplayAmount(rate.currencySymbol, it.formatCurrency(locale), isSymbolSuffix(rate.quote))
            }
        }
    }
    val showsFiatFirst = settings.primaryDisplay == PrimaryDisplay.FIAT && fiat != null
    return HomeUiState(
        isReady = wallet.status == WalletStatus.Ready,
        isLoading = wallet.status in loadingStatuses,
        isError = wallet.status == WalletStatus.Error,
        isOffline = wallet.status == WalletStatus.Offline,
        isRemoved = wallet.status == WalletStatus.Removed,
        isMissing = wallet.status == WalletStatus.Missing,
        isBitcoinPrimary = !showsFiatFirst,
        primaryAmount = if (showsFiatFirst) fiat else bitcoin,
        secondaryAmount = if (showsFiatFirst) bitcoin else fiat,
        nfc = nfc,
    )
}

@Immutable
data class HomeUiState(
    val isReady: Boolean = false,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val isOffline: Boolean = false,
    val isRemoved: Boolean = false,
    val isMissing: Boolean = false,
    val isBitcoinPrimary: Boolean = true,
    val primaryAmount: DisplayAmount? = null,
    val secondaryAmount: DisplayAmount? = null,
    val nfc: NfcState = NfcState.Unknown,
) {
    /**
     * Whether the wallet is connected and synced and its balance shows, so it can pay: never while it is loading,
     * failed, offline or locked.
     */
    val isWalletReady: Boolean get() = isReady && primaryAmount != null
}

/** An amount as the balance shows it: the number and its currency symbol, drawn apart as on the phone. */
@Immutable
data class DisplayAmount(
    val symbol: String,
    val text: String,
    val isSymbolSuffix: Boolean = false,
)
