package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.FxRate
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.Toast
import to.bitkit.services.CurrencyService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrencyRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val currencyService: CurrencyService,
    private val settingsStore: SettingsStore,
) {
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())

    private val _currencyState = MutableStateFlow(CurrencyState())
    val currencyState: StateFlow<CurrencyState> = _currencyState.asStateFlow()

    private var lastSuccessfulRefresh: Date? = null
    private var isRefreshing = false

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

    private fun startPolling() {
        repoScope.launch {
            pollingFlow.collect {
                refresh()
            }
        }
    }

    private fun observeStaleData() {
        repoScope.launch {
            currencyState.map { it.hasStaleData }.distinctUntilChanged().collect { isStale ->
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

    private fun collectSettingsData() {
        repoScope.launch {
            settingsStore.data.collect { settings ->
                _currencyState.update { currentState ->
                    currentState.copy(
                        selectedCurrency = settings.selectedCurrency,
                        displayUnit = settings.displayUnit,
                        primaryDisplay = settings.primaryDisplay,
                        currencySymbol = currentState.rates.firstOrNull { rate ->
                            rate.quote == settings.selectedCurrency
                        }?.currencySymbol ?: "$"
                    )
                }
            }
        }
    }

    suspend fun triggerRefresh() = withContext(bgDispatcher) {
        refresh()
    }

    private suspend fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        try {
            val fetchedRates = currencyService.fetchLatestRates()
            _currencyState.update {
                it.copy(
                    rates = fetchedRates,
                    error = null,
                    hasStaleData = false
                )
            }
            lastSuccessfulRefresh = Date()
            Logger.debug("Currency rates refreshed successfully", context = TAG)
        } catch (e: Exception) {
            _currencyState.update { it.copy(error = e) }
            Logger.error("Currency rates refresh failed", e, context = TAG)

            lastSuccessfulRefresh?.let { last ->
                _currencyState.update {
                    it.copy(hasStaleData = Date().time - last.time > Env.fxRateStaleThreshold)
                }
            }
        } finally {
            isRefreshing = false
        }
    }

    suspend fun togglePrimaryDisplay() = withContext(bgDispatcher) {
        currencyState.value.primaryDisplay.let {
            val newDisplay = if (it == PrimaryDisplay.BITCOIN) PrimaryDisplay.FIAT else PrimaryDisplay.BITCOIN
            settingsStore.update { it.copy(primaryDisplay = newDisplay) }
        }
    }

    suspend fun setPrimaryDisplayUnit(unit: PrimaryDisplay) = withContext(bgDispatcher) {
        settingsStore.update { it.copy(primaryDisplay = unit) }
    }

    suspend fun setBtcDisplayUnit(unit: BitcoinDisplayUnit) = withContext(bgDispatcher) {
        settingsStore.update { it.copy(displayUnit = unit) }
    }

    suspend fun setSelectedCurrency(currency: String) = withContext(bgDispatcher) {
        settingsStore.update { it.copy(selectedCurrency = currency) }
        refresh()
    }

    fun getCurrencySymbol(): String {
        val currentState = currencyState.value
        return currentState.rates.firstOrNull { it.quote == currentState.selectedCurrency }?.currencySymbol ?: ""
    }

    // Conversion helpers
    fun convertSatsToFiat(sats: Long, currency: String? = null): ConvertedAmount? {
        val targetCurrency = currency ?: currencyState.value.selectedCurrency
        val rate = currencyService.getCurrentRate(targetCurrency, currencyState.value.rates)
        return rate?.let { currencyService.convert(sats = sats, rate = it) }
    }

    fun convertFiatToSats(fiatAmount: Double, currency: String? = null): Long {
        val sourceCurrency = currency ?: currencyState.value.selectedCurrency
        return currencyService.convertFiatToSats(fiatAmount, sourceCurrency, currencyState.value.rates)
    }

    companion object {
        private const val TAG = "CurrencyRepo"
    }
}

data class CurrencyState(
    val rates: List<FxRate> = emptyList(),
    val error: Throwable? = null,
    val hasStaleData: Boolean = false,
    val selectedCurrency: String = "USD",
    val currencySymbol: String = "$",
    val displayUnit: BitcoinDisplayUnit = BitcoinDisplayUnit.MODERN,
    val primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
)
