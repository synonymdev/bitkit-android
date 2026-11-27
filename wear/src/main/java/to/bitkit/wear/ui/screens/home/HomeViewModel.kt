package to.bitkit.wear.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
    private val currencyRepo: CurrencyRepo,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private val STOP_TIMEOUT = 5.seconds
    }

    val uiState: StateFlow<HomeUiState> = combine(
        walletRepo.walletState,
        currencyRepo.rates,
        settingsStore.data,
    ) { wallet, rates, settings ->
        homeUiState(wallet, rates, settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), HomeUiState())

    init {
        start()
        refreshRates()
    }

    fun onRetryClick() {
        start()
        refreshRates()
    }

    /** Swaps which unit the balance shows first. */
    fun onUnitToggle() {
        viewModelScope.launch {
            val current = settingsStore.data.first().primaryDisplay
            settingsStore.setPrimaryDisplay(!current)
        }
    }

    private fun start() {
        viewModelScope.launch { walletRepo.start() }
    }

    private fun refreshRates() {
        viewModelScope.launch {
            currencyRepo.refresh().onFailure { Logger.warn("Failed to refresh rates", it, context = TAG) }
        }
    }
}

/** The home screen's state for [wallet], showing amounts in [settings]' order with the matching rate in [rates]. */
internal fun homeUiState(
    wallet: WalletState,
    rates: List<FxRate>,
    settings: WatchSettings,
    locale: Locale = Locale.getDefault(),
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
        isLoading = wallet.status == WalletStatus.Idle || wallet.status == WalletStatus.Connecting,
        isError = wallet.status == WalletStatus.Error,
        isOffline = wallet.status == WalletStatus.Offline,
        isRemoved = wallet.status == WalletStatus.Removed,
        isMissing = wallet.status == WalletStatus.Missing,
        isBitcoinPrimary = !showsFiatFirst,
        primaryAmount = if (showsFiatFirst) fiat else bitcoin,
        secondaryAmount = if (showsFiatFirst) bitcoin else fiat,
    )
}

@Immutable
data class HomeUiState(
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val isOffline: Boolean = false,
    val isRemoved: Boolean = false,
    val isMissing: Boolean = false,
    val isBitcoinPrimary: Boolean = true,
    val primaryAmount: DisplayAmount? = null,
    val secondaryAmount: DisplayAmount? = null,
) {
    /** Whether the balance shown is synced and the wallet can pay: not loading, failed or offline. */
    val isWalletReady: Boolean get() = !isLoading && !isError && !isOffline && primaryAmount != null
}

/** An amount as the balance shows it: the number and its currency symbol, drawn apart as on the phone. */
@Immutable
data class DisplayAmount(
    val symbol: String,
    val text: String,
    val isSymbolSuffix: Boolean = false,
)
