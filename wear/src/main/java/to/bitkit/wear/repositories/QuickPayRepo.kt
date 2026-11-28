package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.wear.data.QuickPaySpendStore
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.models.BTC_SCALE
import to.bitkit.wear.models.USD
import to.bitkit.wear.utils.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Decides whether a payment goes out under the phone's QuickPay settings, which the watch imports and never sets.
 * Everything that is missing, stale or unreadable answers no.
 */
@Singleton
class QuickPayRepo internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val settingsStore: SettingsStore,
    private val currencyRepo: CurrencyRepo,
    private val spendStore: QuickPaySpendStore,
    private val settingsSyncRepo: SettingsSyncRepo,
    private val nowMs: () -> Long,
    private val zone: () -> ZoneId,
) {
    companion object {
        private const val TAG = "QuickPayRepo"
        private const val CENTS_SCALE = 2

        /**
         * How old the last import of the phone's settings may be. A watch that could not read them for longer asks
         * for the hold again, so QuickPay turned off on the phone stops on the watch within this time.
         */
        val MAX_SETTINGS_AGE = 15.minutes

        /** How old the exchange rates may be for the limit, which is in dollars, to be checked against them. */
        val MAX_RATES_AGE = 1.hours

        /** How long [awaitInputs] holds a payment back for a new import and new rates before it goes on without. */
        val INPUTS_TIMEOUT = 5.seconds
    }

    @Inject
    constructor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        settingsStore: SettingsStore,
        currencyRepo: CurrencyRepo,
        spendStore: QuickPaySpendStore,
        settingsSyncRepo: SettingsSyncRepo,
    ) : this(
        ioDispatcher,
        settingsStore,
        currencyRepo,
        spendStore,
        settingsSyncRepo,
        System::currentTimeMillis,
        ZoneId::systemDefault,
    )

    private val mutex = Mutex()

    /**
     * Brings what [reserve] reads up to date before it is asked, for a payment that arrives as the app starts or
     * returns: the import and the rates are then on their way but not in yet. When the last import has QuickPay on
     * and is older than [MAX_SETTINGS_AGE], it waits for an import, joining one that runs; when the rates are missing
     * or older than [MAX_RATES_AGE], it fetches them. Both together take at most [INPUTS_TIMEOUT]: only the wait
     * ends then, and an import still under way goes on and lands for the next payment. It decides nothing:
     * whatever is still missing or old afterwards makes [reserve] answer no. A watch whose last import has QuickPay
     * off, or that has none, never waits here.
     */
    suspend fun awaitInputs(): Unit = withContext(ioDispatcher) {
        runSuspendCatching {
            val settings = settingsStore.data.first()
            if (settings.quickPay?.isEnabled != true) return@runSuspendCatching
            val isImportFresh = settings.phoneSyncedAtMs?.let { isFresh(nowMs() - it) } == true
            val areRatesFresh = currencyRepo.ratesAge()?.let { it <= MAX_RATES_AGE } == true
            withTimeoutOrNull(INPUTS_TIMEOUT) {
                coroutineScope {
                    if (!isImportFresh) launch { settingsSyncRepo.sync() }
                    if (!areRatesFresh) launch { currencyRepo.refresh() }
                }
            }
        }.onFailure { Logger.warn("Failed to refresh what QuickPay reads", it, context = TAG) }
    }

    private fun isFresh(settingsAgeMs: Long): Boolean = settingsAgeMs in 0..MAX_SETTINGS_AGE.inWholeMilliseconds

    /**
     * Whether a payment that costs [totalSats], its fee included, may skip the hold to pay, and if so counts it
     * against today's limit. It may when QuickPay was imported from the phone within [MAX_SETTINGS_AGE] and is on,
     * a dollar rate no older than [MAX_RATES_AGE] values the payment at no more than the phone's QuickPay amount, and
     * today's QuickPay payments from this watch stay within the phone's daily limit with it. A yes is counted
     * whether or not the payment then succeeds. Any failure to read or count answers no.
     */
    suspend fun reserve(totalSats: ULong): Boolean = withContext(ioDispatcher) {
        runSuspendCatching { mutex.withLock { reserveLocked(totalSats) } }
            .onFailure { Logger.warn("Failed to check QuickPay", it, context = TAG) }
            .getOrDefault(false)
    }

    private suspend fun reserveLocked(totalSats: ULong): Boolean {
        val settings = settingsStore.data.first()
        val quickPay = settings.quickPay?.takeIf { it.isEnabled } ?: return false
        val now = nowMs()
        if (!isFresh(now - (settings.phoneSyncedAtMs ?: return false))) return false
        val ratesAge = currencyRepo.ratesAge() ?: return false
        if (ratesAge > MAX_RATES_AGE) return false
        val rate = currencyRepo.rates.value.rateFor(USD)?.lastPrice?.toBigDecimalOrNull() ?: return false
        val cents = usdCents(totalSats, rate) ?: return false
        if (cents > quickPay.thresholdCents) return false
        val dayKey = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toString()
        return spendStore.reserve(dayKey, cents, quickPay.dailyCapCents)
    }

    /** [sats] at [usdPerBtc] in US cents, rounded up so a payment never counts for less than it is worth. */
    private fun usdCents(sats: ULong, usdPerBtc: BigDecimal): Long? {
        if (sats == 0uL || sats > Long.MAX_VALUE.toULong() || usdPerBtc.signum() <= 0) return null
        val cents = BigDecimal.valueOf(sats.toLong())
            .multiply(usdPerBtc)
            .movePointLeft(BTC_SCALE - CENTS_SCALE)
            .setScale(0, RoundingMode.CEILING)
        return runCatching { cents.longValueExact() }.getOrNull()
    }
}
