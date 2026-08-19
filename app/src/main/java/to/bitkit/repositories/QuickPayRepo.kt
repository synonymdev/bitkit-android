package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.QuickPayDaySpend
import to.bitkit.data.QuickPaySpendReservation
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.quickPaySpendDayKey
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.USD
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Singleton
class QuickPayRepo @Inject constructor(
    private val cacheStore: CacheStore,
    private val settingsStore: SettingsStore,
    private val currencyRepo: CurrencyRepo,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "QuickPayRepo"
    }

    suspend fun spentCentsToday(): Result<Long> = withContext(ioDispatcher) {
        runSuspendCatching {
            cacheStore.data.first().spendFor(currentDayKey()).spentCents
        }
    }

    suspend fun canApply(amountSats: ULong): Result<Boolean> = withContext(ioDispatcher) {
        runSuspendCatching {
            val settings = settingsStore.data.first()
            if (!settings.isQuickPayEnabled || amountSats == 0uL) return@runSuspendCatching false

            val thresholdSats = currencyRepo.convertFiatToSats(
                settings.quickPayAmount.toDouble(),
                USD,
            ).getOrNull() ?: return@runSuspendCatching false
            if (amountSats > thresholdSats) return@runSuspendCatching false

            val converted = currencyRepo.convertSatsToFiat(amountSats.toLong(), USD).getOrNull()
                ?: return@runSuspendCatching false
            val reserveCents = quickPayReserveCents(converted.toUsdCents(), settings.quickPayAmount)
            val capCents = quickPayCapCents(settings.quickPayAmount, settings.quickPayDailyLimitMultiplier)
            val spentCentsToday = cacheStore.data.first().spendFor(currentDayKey()).spentCents
            if (spentCentsToday + reserveCents <= capCents) return@runSuspendCatching true

            Logger.info(
                "Skipping QuickPay: daily spend '$spentCentsToday' + '$reserveCents' exceeds cap '$capCents'",
                context = TAG,
            )
            false
        }
    }

    suspend fun tryReserve(amountSats: ULong): Result<QuickPaySpendReservation?> = withContext(ioDispatcher) {
        runSuspendCatching {
            val settings = settingsStore.data.first()
            val converted = currencyRepo.convertSatsToFiat(amountSats.toLong(), USD).getOrElse {
                throw QuickPayConversionError()
            }
            val amountCents = quickPayReserveCents(converted.toUsdCents(), settings.quickPayAmount)
            val capCents = quickPayCapCents(settings.quickPayAmount, settings.quickPayDailyLimitMultiplier)
            val dayKey = currentDayKey()
            var reserved: QuickPaySpendReservation? = null
            cacheStore.update {
                val spend = it.spendFor(dayKey)
                if (spend.spentCents + amountCents > capCents) return@update it
                reserved = QuickPaySpendReservation(amountCents = amountCents, dayKey = spend.dayKey)
                it.copy(
                    quickPaySpendDayKey = spend.dayKey,
                    quickPaySpentCentsToday = spend.spentCents + amountCents,
                )
            }
            reserved
        }
    }

    suspend fun remember(
        paymentHash: String,
        reservation: QuickPaySpendReservation,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (paymentHash.isBlank()) return@runSuspendCatching
            cacheStore.update {
                it.copy(
                    quickPayReservations = it.quickPayReservations + (paymentHash to reservation),
                )
            }
        }
    }

    suspend fun reservation(paymentHash: String): Result<QuickPaySpendReservation?> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (paymentHash.isBlank()) return@runSuspendCatching null
            cacheStore.data.first().quickPayReservations[paymentHash]
        }
    }

    suspend fun release(paymentHash: String): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (paymentHash.isBlank()) return@runSuspendCatching
            cacheStore.update { data ->
                val reservation = data.quickPayReservations[paymentHash] ?: return@update data
                val remaining = data.quickPayReservations - paymentHash
                val spend = data.spendFor(reservation.dayKey)
                if (reservation.dayKey != spend.dayKey) {
                    return@update data.copy(quickPayReservations = remaining)
                }
                data.copy(
                    quickPaySpentCentsToday = (spend.spentCents - reservation.amountCents).coerceAtLeast(0L),
                    quickPayReservations = remaining,
                )
            }
        }
    }

    suspend fun releaseUnbound(reservation: QuickPaySpendReservation): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            cacheStore.update {
                if (reservation.dayKey != it.quickPaySpendDayKey) return@update it
                it.copy(
                    quickPaySpentCentsToday = (it.quickPaySpentCentsToday - reservation.amountCents).coerceAtLeast(0L),
                )
            }
        }
    }

    suspend fun clear(paymentHash: String): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (paymentHash.isBlank()) return@runSuspendCatching
            cacheStore.update {
                if (paymentHash !in it.quickPayReservations) return@update it
                it.copy(quickPayReservations = it.quickPayReservations - paymentHash)
            }
        }
    }

    private fun currentDayKey(): String = quickPaySpendDayKey(clock)
}

private fun quickPayCapCents(thresholdUsd: Int, multiplier: Int): Long =
    thresholdUsd.toLong() * 100L * multiplier.toLong()

private fun quickPayReserveCents(convertedCents: Long, thresholdUsd: Int): Long =
    minOf(convertedCents, thresholdUsd.toLong() * 100L)

class QuickPayConversionError : AppError("Currency conversion failed")

private fun AppCacheData.spendFor(dayKey: String): QuickPayDaySpend = when {
    quickPaySpendDayKey.isEmpty() || dayKey > quickPaySpendDayKey -> QuickPayDaySpend(dayKey, 0L)
    dayKey == quickPaySpendDayKey -> QuickPayDaySpend(dayKey, quickPaySpentCentsToday)
    else -> QuickPayDaySpend(quickPaySpendDayKey, quickPaySpentCentsToday)
}
