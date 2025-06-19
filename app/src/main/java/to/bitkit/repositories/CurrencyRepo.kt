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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.FxRate
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.SATS_IN_BTC
import to.bitkit.models.Toast
import to.bitkit.services.CurrencyService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.utils.formatCurrency
import to.bitkit.utils.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

@Singleton
class CurrencyRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val currencyService: CurrencyService,
    private val settingsStore: SettingsStore,
    private val cacheStore: CacheStore
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
        collectCachedData()
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

    private fun collectCachedData() {
        repoScope.launch {
            combine(settingsStore.data, cacheStore.data) { settings, cachedData ->
                _currencyState.value.copy(
                    rates = cachedData.cachedRates,
                    selectedCurrency = settings.selectedCurrency,
                    displayUnit = settings.displayUnit,
                    primaryDisplay = settings.primaryDisplay,
                    currencySymbol = cachedData.cachedRates.firstOrNull { rate ->
                        rate.quote == settings.selectedCurrency
                    }?.currencySymbol ?: "$"
                )
            }.collect { newState ->
                _currencyState.update { newState }
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
            cacheStore.update { it.copy(cachedRates = fetchedRates) }
            _currencyState.update {
                it.copy(
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
            val newDisplay =
                if (it == PrimaryDisplay.BITCOIN) PrimaryDisplay.FIAT else PrimaryDisplay.BITCOIN
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
        return currentState.rates.firstOrNull {
            it.quote == currentState.selectedCurrency
        }?.currencySymbol ?: ""
    }

    // Conversion helpers
    fun getCurrentRate(currency: String): FxRate? {
        return _currencyState.value.rates.firstOrNull { it.quote == currency }
    }

    fun convertSatsToFiat(
        sats: Long,
        currency: String? = null,
    ): Result<ConvertedAmount> = runCatching {
        val targetCurrency = currency ?: currencyState.value.selectedCurrency
        val rate = getCurrentRate(targetCurrency)

        if (rate == null) {
            val exception = Exception("Rate not found for targetCurrency: $targetCurrency")
            Logger.error("Rate not found", exception, context = TAG)
            return Result.failure(exception)
        }

        val btcAmount = BigDecimal(sats).divide(BigDecimal(SATS_IN_BTC))
        val value: BigDecimal = btcAmount.multiply(BigDecimal.valueOf(rate.rate))
        val formatted = value.formatCurrency()

        if (formatted == null) {
            val exception = Exception("Error formatting currency: $value")
            Logger.error("Error formatting currency", exception, context = TAG)
            return Result.failure(exception)
        }

        return Result.success(
            ConvertedAmount(
                value = value,
                formatted = formatted,
                symbol = rate.currencySymbol,
                currency = rate.quote,
                flag = rate.currencyFlag,
                sats = sats,
            )
        )
    }

    fun convertFiatToSats(
        fiatValue: BigDecimal,
        currency: String? = null,
    ): Result<ULong> = runCatching {
        val targetCurrency = currency ?: currencyState.value.selectedCurrency
        val rate = getCurrentRate(targetCurrency)

        if (rate == null) {
            val exception = Exception("Rate not found for targetCurrency: $targetCurrency")
            Logger.error("Rate not found", exception, context = TAG)
            return Result.failure(exception)
        }

        val btcAmount = fiatValue.divide(BigDecimal.valueOf(rate.rate), 8, RoundingMode.HALF_UP)
        val satsDecimal = btcAmount.multiply(BigDecimal(SATS_IN_BTC))
        val roundedNumber = satsDecimal.setScale(0, RoundingMode.HALF_UP)
        return Result.success(roundedNumber.toLong().toULong())
    }

    fun convertFiatToSats(
        fiatAmount: Double,
        currency: String?
    ): Result<Long> {
        val targetCurrency = currency ?: currencyState.value.selectedCurrency
        val rate = getCurrentRate(targetCurrency)

        if (rate == null) {
            val exception = Exception("Rate not found for targetCurrency: $targetCurrency")
            Logger.error("Rate not found", exception, context = TAG)
            return Result.failure(exception)
        }

        // Convert the fiat amount to BTC, then to sats
        val btc = fiatAmount / rate.rate
        val sats = (btc * SATS_IN_BTC).roundToLong()

        return Result.success(sats)
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
