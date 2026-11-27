package to.bitkit.wear.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.BITCOIN_SYMBOL
import to.bitkit.wear.models.FxRate
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.models.fiatValue
import to.bitkit.wear.models.formatCurrencyWithSymbol
import to.bitkit.wear.models.formatToModernDisplay
import to.bitkit.wear.repositories.CurrencyRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.repositories.rateFor
import to.bitkit.wear.utils.Logger
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
    private val currencyRepo: CurrencyRepo,
    settingsStore: SettingsStore,
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L
    }

    val uiState: StateFlow<HomeUiState> = combine(
        walletRepo.walletState,
        currencyRepo.rates,
        settingsStore.data,
    ) { wallet, rates, settings ->
        homeUiState(wallet, rates, settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    init {
        start()
        refreshRates()
    }

    fun onRetryClick() {
        start()
        refreshRates()
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
    val sats = wallet.balanceSats?.toLong()
    val bitcoin = sats?.let { "$BITCOIN_SYMBOL ${it.formatToModernDisplay(locale)}" }
    val fiat = sats?.let { amount ->
        rates.rateFor(settings.selectedCurrency)?.let { rate ->
            fiatValue(amount, rate)?.formatCurrencyWithSymbol(rate.currencySymbol, locale)
        }
    }
    val showsFiatFirst = settings.primaryDisplay == PrimaryDisplay.FIAT && fiat != null
    return HomeUiState(
        isError = wallet.status == WalletStatus.Error && sats == null,
        isRemoved = wallet.status == WalletStatus.Removed,
        primaryAmount = if (showsFiatFirst) fiat else bitcoin,
        secondaryAmount = if (showsFiatFirst) bitcoin else fiat,
    )
}

@Immutable
data class HomeUiState(
    val isError: Boolean = false,
    val isRemoved: Boolean = false,
    val primaryAmount: String? = null,
    val secondaryAmount: String? = null,
)
