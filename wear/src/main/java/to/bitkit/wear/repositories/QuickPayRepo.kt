package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.wear.data.QuickPaySpendStore
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.models.BTC_SCALE
import to.bitkit.wear.models.FxRate
import to.bitkit.wear.models.QuickPayLimits
import to.bitkit.wear.models.SATS_IN_BTC
import to.bitkit.wear.models.USD
import to.bitkit.wear.models.fiatValue
import to.bitkit.wear.utils.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Decides whether a payment goes out under the phone's QuickPay settings, which the watch imports and never sets, by
 * the rules of the phone's own `QuickPayRepo`: the same amount, rate, rounding and daily count. It reads the settings
 * as last imported, however long ago. Everything that is missing or unreadable answers no.
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

        /** How long [awaitRate] holds a payment back for the dollar rate before it goes on without. */
        val RATE_TIMEOUT = 5.seconds
    }

    @Inject
    constructor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        settingsStore: SettingsStore,
        currencyRepo: CurrencyRepo,
        spendStore: QuickPaySpendStore,
    ) : this(
        ioDispatcher,
        settingsStore,
        currencyRepo,
        spendStore,
        System::currentTimeMillis,
        ZoneId::systemDefault,
    )

    private val mutex = Mutex()

    /**
     * Fetches the rates when QuickPay is on and no dollar rate is in yet, as for a payment whose link started the
     * app: the watch keeps no rates across restarts, where the phone has its cached ones. It waits at most
     * [RATE_TIMEOUT] and decides nothing: without a rate [reserve] answers no.
     */
    suspend fun awaitRate(): Unit = withContext(ioDispatcher) {
        runSuspendCatching {
            if (settingsStore.data.first().quickPay?.isEnabled != true) return@runSuspendCatching
            if (currencyRepo.rates.value.rateFor(USD) != null) return@runSuspendCatching
            withTimeoutOrNull(RATE_TIMEOUT) { currencyRepo.refresh() }
        }.onFailure { Logger.warn("Failed to fetch the rate QuickPay reads", it, context = TAG) }
    }

    /**
     * Whether a payment of [amountSats], its fee not counted, may skip the hold to pay; if so it is counted against
     * today's limit and returned as what [release] takes back. It may when QuickPay is on in the last import, the
     * amount is no more than the phone's QuickPay amount, in sats and in unrounded dollars at the dollar rate (the
     * phone checks the one when it offers QuickPay and the other when it pays), and today's QuickPay payments
     * from this watch stay within the phone's daily limit with it. Any failure to read or count answers null.
     */
    suspend fun reserve(amountSats: ULong): QuickPayReservation? = withContext(ioDispatcher) {
        runSuspendCatching { mutex.withLock { reserveLocked(amountSats) } }
            .onFailure { Logger.warn("Failed to check QuickPay", it, context = TAG) }
            .getOrNull()
    }

    /**
     * Takes [reservation] out of its day's count again, for a payment that did not go out or that failed, as the
     * phone does. A count that cannot be written stays, which only makes the day's limit tighter.
     */
    suspend fun release(reservation: QuickPayReservation): Unit = withContext(ioDispatcher) {
        runSuspendCatching { mutex.withLock { spendStore.release(reservation.dayKey, reservation.cents) } }
            .onFailure { Logger.warn("Failed to release a QuickPay payment", it, context = TAG) }
    }

    private suspend fun reserveLocked(amountSats: ULong): QuickPayReservation? {
        val quickPay = settingsStore.data.first().quickPay?.takeIf { it.isEnabled } ?: return null
        if (amountSats == 0uL || amountSats > Long.MAX_VALUE.toULong()) return null
        val rate = currencyRepo.rates.value.rateFor(USD) ?: return null
        val thresholdSats = thresholdSats(quickPay, rate) ?: return null
        if (thresholdSats == 0uL || amountSats > thresholdSats) return null
        val fiatCents = fiatValue(amountSats.toLong(), rate)?.movePointRight(CENTS_SCALE) ?: return null
        // The threshold in sats may have rounded up, so its last sat can be worth a fraction of a cent too much.
        if (fiatCents > BigDecimal.valueOf(quickPay.thresholdCents)) return null
        val cents = fiatCents.setScale(0, RoundingMode.HALF_UP).toLong().coerceAtLeast(1L)
        val today = Instant.ofEpochMilli(nowMs()).atZone(zone()).toLocalDate().toString()
        val dayKey = spendStore.reserve(today, cents, quickPay.dailyCapCents) ?: return null
        return QuickPayReservation(dayKey, cents)
    }

    private fun thresholdSats(quickPay: QuickPayLimits, rate: FxRate): ULong? {
        if (rate.rate <= 0.0) return null
        val sats = BigDecimal.valueOf(quickPay.amountUsd.toDouble())
            .divide(BigDecimal.valueOf(rate.rate), BTC_SCALE, RoundingMode.HALF_UP)
            .multiply(BigDecimal(SATS_IN_BTC))
            .setScale(0, RoundingMode.HALF_UP)
        return runCatching { sats.longValueExact().toULong() }.getOrNull()
    }
}

/** A payment counted against the QuickPay limit of [dayKey], an ISO date, as [cents] US cents. */
data class QuickPayReservation(val dayKey: String, val cents: Long)
