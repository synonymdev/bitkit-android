package to.bitkit.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.env.Tag.APP
import to.bitkit.env.Tag.DEV
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.FxRate
import to.bitkit.models.Toast
import to.bitkit.services.CurrencyService
import to.bitkit.ui.shared.toast.ToastEventBus
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
    private val _rates = MutableStateFlow<List<FxRate>>(emptyList())
    val rates = _rates.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error = _error.asStateFlow()

    private val _hasStaleData = MutableStateFlow(false)
    private val hasStaleData = _hasStaleData.asStateFlow()

    var selectedCurrency = "USD"

    val displayUnit = settingsStore.displayUnit
    val primaryDisplay = settingsStore.primaryDisplay

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
    }

    private fun observeStaleData() {
        viewModelScope.launch {
            hasStaleData.collect { isStale ->
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
            Log.d(DEV, rates.value.toString())
        }
    }

    private suspend fun refresh() {
        try {
            val fetchedRates = currencyService.fetchLatestRates()
            _rates.update { fetchedRates }
            lastSuccessfulRefresh = Date()
            _error.update { null }
            _hasStaleData.update { false }
        } catch (e: Exception) {
            _error.update { e }
            Log.e(APP, "Currency rates refresh failed", e)

            lastSuccessfulRefresh?.let { last ->
                _hasStaleData.update { Date().time - last.time > Env.fxRateStaleThreshold }
            }
        }
    }

    fun togglePrimaryDisplay() {
        viewModelScope.launch {
            primaryDisplay.firstOrNull()?.let {
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

    // UI Helpers
    fun convert(sats: Long, currency: String? = null): ConvertedAmount? {
        val targetCurrency = currency ?: selectedCurrency
        val rate = currencyService.getCurrentRate(targetCurrency, _rates.value)
        return rate?.let { currencyService.convert(sats = sats, rate = it) }
    }
}
