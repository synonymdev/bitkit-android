package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import to.bitkit.wear.data.RatesHttpClient
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.models.FxRate
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Singleton
class CurrencyRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val ratesHttpClient: RatesHttpClient,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "CurrencyRepo"
    }

    private val _rates = MutableStateFlow<List<FxRate>>(emptyList())
    val rates: StateFlow<List<FxRate>> = _rates.asStateFlow()

    @Volatile
    private var ratesFetched: TimeMark? = null

    /** How long ago [rates] were fetched, or null when they never were. */
    fun ratesAge(): Duration? = ratesFetched?.elapsedNow()

    suspend fun refresh(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val tickers = ratesHttpClient.fetchLatestRates().tickers
            _rates.update { tickers }
            ratesFetched = TimeSource.Monotonic.markNow()
        }
    }

    /** Swaps which unit the balance shows first, and keeps the choice across restarts. */
    suspend fun togglePrimaryDisplay(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching { settingsStore.setPrimaryDisplay(!settingsStore.data.first().primaryDisplay) }
            .onFailure { Logger.error("Failed to switch the primary display", it, context = TAG) }
    }
}

/** The rate for [currency] among these, or null when there is none. */
fun List<FxRate>.rateFor(currency: String): FxRate? = firstOrNull { it.quote == currency }
