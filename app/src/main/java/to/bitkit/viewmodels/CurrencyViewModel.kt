package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.FxRate
import to.bitkit.models.Toast
import to.bitkit.services.CurrencyService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import java.util.Date
import javax.inject.Inject

enum class PrimaryDisplay {
    BITCOIN,
    FIAT
}

@HiltViewModel
class CurrencyViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val currencyService: CurrencyService,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CurrencyUiState())
    val uiState = _uiState.asStateFlow()

    private var lastSuccessfulRefresh: Date? = null

    private val pollingFlow: Flow<Unit>
        get() = flow {
            while (currentCoroutineContext().isActive) {
                emit(Unit)
                delay(Env.fxRateRefreshInterval)
            }
        }.flowOn(bgDispatcher)

    init {
        startPolling()
        observeStaleData()
        collectSettingsData()
    }

    private fun observeStaleData() {
        viewModelScope.launch {
            uiState.map { it.hasStaleData }.distinctUntilChanged().collect { isStale ->
                if (isStale) {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = "Rates currently unavailable",
                        description = "An error has occurred. Please try again later."
                    )
                }
            }
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            pollingFlow.collect {
                refresh()
            }
        }
    }

    fun triggerRefresh() {
        viewModelScope.launch {
            refresh()
        }
    }

    private suspend fun refresh() {
        try {
            val fetchedRates = currencyService.fetchLatestRates()
            _uiState.update {
                it.copy(
                    rates = fetchedRates,
                    error = null,
                    hasStaleData = false
                )
            }
            lastSuccessfulRefresh = Date()
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e) }
            Logger.error("Currency rates refresh failed", e)

            lastSuccessfulRefresh?.let { last ->
                _uiState.update { it.copy(hasStaleData = Date().time - last.time > Env.fxRateStaleThreshold) }
            }
        }
    }

    private fun collectSettingsData() {
        viewModelScope.launch {
            settingsStore.selectedCurrency.collect { currency ->
                _uiState.update { it.copy(selectedCurrency = currency) }
            }
        }

        viewModelScope.launch {
            settingsStore.displayUnit.collect { unit ->
                _uiState.update { it.copy(displayUnit = unit) }
            }
        }

        viewModelScope.launch {
            settingsStore.primaryDisplay.collect { display ->
                _uiState.update { it.copy(primaryDisplay = display) }
            }
        }
    }

    fun togglePrimaryDisplay() {
        viewModelScope.launch {
            uiState.value.primaryDisplay.let {
                val newDisplay = if (it == PrimaryDisplay.BITCOIN) PrimaryDisplay.FIAT else PrimaryDisplay.BITCOIN
                settingsStore.setPrimaryDisplayUnit(newDisplay)
            }
        }
    }

    fun setPrimaryDisplayUnit(unit: PrimaryDisplay) {
        viewModelScope.launch {
            settingsStore.setPrimaryDisplayUnit(unit)
        }
    }

    fun setBtcDisplayUnit(unit: BitcoinDisplayUnit) {
        viewModelScope.launch {
            settingsStore.setBtcDisplayUnit(unit)
        }
    }

    fun setSelectedCurrency(currency: String) {
        viewModelScope.launch {
            settingsStore.setSelectedCurrency(currency)
            refresh()
        }
    }

    // UI Helpers
    fun convert(sats: Long, currency: String? = null): ConvertedAmount? {
        val targetCurrency = currency ?: uiState.value.selectedCurrency
        val rate = currencyService.getCurrentRate(targetCurrency, uiState.value.rates)
        return rate?.let { currencyService.convert(sats = sats, rate = it) }
    }
}

data class CurrencyUiState(
    val rates: List<FxRate> = emptyList(),
    val error: Throwable? = null,
    val hasStaleData: Boolean = false,
    val selectedCurrency: String = "USD",
    val displayUnit: BitcoinDisplayUnit = BitcoinDisplayUnit.MODERN,
    val primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
)
