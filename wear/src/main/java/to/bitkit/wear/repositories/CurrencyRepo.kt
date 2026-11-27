package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import to.bitkit.wear.data.RatesHttpClient
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.models.FxRate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrencyRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val ratesHttpClient: RatesHttpClient,
) {
    private val _rates = MutableStateFlow<List<FxRate>>(emptyList())
    val rates: StateFlow<List<FxRate>> = _rates.asStateFlow()

    suspend fun refresh(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val tickers = ratesHttpClient.fetchLatestRates().tickers
            _rates.update { tickers }
        }
    }
}

/** The rate for [currency] among these, or null when there is none. */
fun List<FxRate>.rateFor(currency: String): FxRate? = firstOrNull { it.quote == currency }
