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
import to.bitkit.models.BTC_SCALE
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.FxRate
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.SATS_IN_BTC
import to.bitkit.models.STUB_RATE
import to.bitkit.models.Toast
import to.bitkit.models.asBtc
import to.bitkit.models.formatCurrency
import to.bitkit.services.CurrencyService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Suppress("TooManyFunctions")
@OptIn(ExperimentalTime::class)
@Singleton
class CurrencyRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val currencyService: CurrencyService,
    private val settingsStore: SettingsStore,
    private val cacheStore: CacheStore,
    private val clock: Clock,
    @Named("enablePolling") private val enablePolling: Boolean,
) : AmountInputHandler {
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())
    private val _currencyState = MutableStateFlow(CurrencyState())
    val currencyState: StateFlow<CurrencyState> = _currencyState.asStateFlow()

    @Volatile
    private var isRefreshing = false

    private val fxRatePollingFlow: Flow<Unit>
        get() = flow {
            while (currentCoroutineContext().isActive) {
                emit(Unit)
                delay(Env.fxRateRefreshInterval)
            }
        }.flowOn(bgDispatcher)

    init {
        if (enablePolling) {
            startPolling()
        }
        observeStaleData()
        collectCachedData()
    }

    private fun startPolling() {
        repoScope.launch {
            fxRatePollingFlow.collect {
                refresh()
            }
        }
    }

    private fun observeStaleData() {
        repoScope.launch {
            currencyState
                .map { it.hasStaleData }
                .distinctUntilChanged()
                .collect { isStale ->
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
            combine(
                settingsStore.data.distinctUntilChanged(),
                cacheStore.data.distinctUntilChanged()
            ) { settings, cachedData ->
                val selectedRate = cachedData.cachedRates.firstOrNull { rate ->
                    rate.quote == settings.selectedCurrency
                }
                _currencyState.value.copy(
                    rates = cachedData.cachedRates,
                    selectedCurrency = settings.selectedCurrency,
                    displayUnit = settings.displayUnit,
                    primaryDisplay = settings.primaryDisplay,
                    currencySymbol = selectedRate?.currencySymbol ?: "$",
                    error = null,
                    hasStaleData = false
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
                    hasStaleData = false,
                    lastSuccessfulRefresh = clock.now().toEpochMilliseconds(),
                )
            }
            Logger.debug("Currency rates refreshed successfully", context = TAG)
        } catch (e: Exception) {
            Logger.error("Currency rates refresh failed", e, context = TAG)
            _currencyState.update { it.copy(error = e) }

            _currencyState.value.lastSuccessfulRefresh?.let { lastUpdatedAt ->
                val isStale = clock.now().toEpochMilliseconds() - lastUpdatedAt > Env.fxRateStaleThreshold
                _currencyState.update { it.copy(hasStaleData = isStale) }
            }
        } finally {
            isRefreshing = false
        }
    }

    suspend fun switchUnit() = withContext(bgDispatcher) {
        settingsStore.update { it.copy(primaryDisplay = it.primaryDisplay.not()) }
    }

    override suspend fun switchUnit(unit: PrimaryDisplay): PrimaryDisplay = withContext(bgDispatcher) {
        unit.not().also { nextValue ->
            setPrimaryDisplayUnit(nextValue)
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

    fun getCurrentRate(currency: String): FxRate {
        val rates = _currencyState.value.rates
        val rate = rates.firstOrNull { it.quote == currency }

        return checkNotNull(rate) {
            "Rate not found for currency: $currency in: ${rates.joinToString { it.quote }}"
        }
    }

    fun convertSatsToFiat(
        sats: Long,
        currency: String? = null,
    ): Result<ConvertedAmount> = runCatching {
        val targetCurrency = currency ?: _currencyState.value.selectedCurrency
        val rate = getCurrentRate(targetCurrency)

        val (fiatValue, formatted) = convertSatsToFiatPair(sats, targetCurrency).getOrThrow()

        ConvertedAmount(
            value = fiatValue,
            formatted = formatted,
            symbol = rate.currencySymbol,
            currency = rate.quote,
            flag = rate.currencyFlag,
            sats = sats,
        )
    }

    fun convertSatsToFiatPair(
        sats: Long,
        currency: String? = null,
    ): Result<Pair<BigDecimal, String>> = runCatching {
        val targetCurrency = currency ?: _currencyState.value.selectedCurrency
        val rate = getCurrentRate(targetCurrency)

        val btcAmount = sats.asBtc()
        val fiatValue = btcAmount.multiply(BigDecimal.valueOf(rate.rate))
        val formatted = checkNotNull(fiatValue.formatCurrency()) {
            "Failed to format value: $fiatValue for currency: $targetCurrency"
        }

        return@runCatching fiatValue to formatted
    }

    fun convertFiatToSats(
        fiatValue: BigDecimal,
        currency: String? = null,
    ): Result<ULong> = runCatching {
        val targetCurrency = currency ?: _currencyState.value.selectedCurrency
        val rate = getCurrentRate(targetCurrency)

        val btcAmount = fiatValue.divide(BigDecimal.valueOf(rate.rate), BTC_SCALE, RoundingMode.HALF_UP)
        val satsDecimal = btcAmount.multiply(BigDecimal(SATS_IN_BTC))
        val roundedSats = satsDecimal.setScale(0, RoundingMode.HALF_UP)
        roundedSats.toLong().toULong()
    }

    fun convertFiatToSats(fiat: Double, currency: String?) = convertFiatToSats(BigDecimal.valueOf(fiat), currency)

    companion object {
        private const val TAG = "CurrencyRepo"
    }

    // MARK: - AmountHandler

    override fun convertFiatToSats(fiat: Double) = convertFiatToSats(BigDecimal.valueOf(fiat)).getOrDefault(0u).toLong()
    override fun convertSatsToFiatString(sats: Long): String = convertSatsToFiatPair(sats).getOrNull()?.second ?: ""
}

data class CurrencyState(
    val rates: List<FxRate> = emptyList(),
    val error: Throwable? = null,
    val hasStaleData: Boolean = false,
    val selectedCurrency: String = "USD",
    val currencySymbol: String = "$",
    val displayUnit: BitcoinDisplayUnit = BitcoinDisplayUnit.MODERN,
    val primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
    val lastSuccessfulRefresh: Long? = null,
)

interface AmountInputHandler {
    fun convertSatsToFiatString(sats: Long): String
    fun convertFiatToSats(fiat: Double): Long
    suspend fun switchUnit(unit: PrimaryDisplay): PrimaryDisplay

    companion object {
        fun stub(state: CurrencyState = CurrencyState()) = object : AmountInputHandler {
            override fun convertSatsToFiatString(sats: Long): String {
                return sats.asBtc().multiply(BigDecimal.valueOf(STUB_RATE)).formatCurrency() ?: ""
            }

            override fun convertFiatToSats(fiat: Double) = (fiat / STUB_RATE * SATS_IN_BTC).toLong()
            override suspend fun switchUnit(unit: PrimaryDisplay) = unit.not()
        }
    }
}
