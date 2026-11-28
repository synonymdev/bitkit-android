package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    }

    @Inject
    constructor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        settingsStore: SettingsStore,
        currencyRepo: CurrencyRepo,
        spendStore: QuickPaySpendStore,
    ) : this(ioDispatcher, settingsStore, currencyRepo, spendStore, System::currentTimeMillis, ZoneId::systemDefault)

    private val mutex = Mutex()

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
        val settingsAge = now - (settings.phoneSyncedAtMs ?: return false)
        if (settingsAge < 0 || settingsAge > MAX_SETTINGS_AGE.inWholeMilliseconds) return false
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
