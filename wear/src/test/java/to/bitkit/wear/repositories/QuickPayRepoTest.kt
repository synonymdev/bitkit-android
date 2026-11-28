package to.bitkit.wear.repositories

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.FakeQuickPaySpendStore
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.QuickPayLimits
import to.bitkit.wear.models.rate
import to.bitkit.wear.test.BaseUnitTest
import java.io.IOException
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** At 100 000 dollars a bitcoin one sat is a tenth of a cent, so 5 dollars are exactly 5 000 sats. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickPayRepoTest : BaseUnitTest() {
    private companion object {
        /** 2026-11-28T12:00:00Z */
        const val NOW = 1_795_867_200_000L
        const val FIVE_DOLLARS = 5_000uL
        val LIMITS = QuickPayLimits(isEnabled = true, amountUsd = 5, dailyMultiplier = 3)
    }

    private val settingsStore = mock<SettingsStore>()
    private val currencyRepo = mock<CurrencyRepo>()
    private val spend = FakeQuickPaySpendStore()
    private var zone = ZoneId.of("UTC")
    private val settings = MutableStateFlow(WatchSettings(quickPay = LIMITS, phoneSyncedAtMs = NOW))
    private val rates = MutableStateFlow(listOf(rate("100000"), rate("90000", quote = "EUR")))
    private var now = NOW

    private lateinit var sut: QuickPayRepo

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(settings)
        whenever(currencyRepo.rates).thenReturn(rates)
        whenever { currencyRepo.refresh() }.thenReturn(Result.success(Unit))
        sut = QuickPayRepo(testDispatcher, settingsStore, currencyRepo, spend, { now }, { zone })
    }

    @Test
    fun `a missing dollar rate is fetched before the payment is decided, while quickpay is on`() = test {
        rates.update { listOf(rate("90000", quote = "EUR")) }
        whenever(currencyRepo.refresh()).doSuspendableAnswer {
            rates.update { listOf(rate("100000")) }
            Result.success(Unit)
        }

        sut.awaitRate()

        assertNotNull(sut.reserve(1uL))
        verify(currencyRepo, times(1)).refresh()
    }

    @Test
    fun `the wait for the rate ends after five seconds and the payment then holds`() = test {
        rates.update { emptyList() }
        whenever(currencyRepo.refresh()).doSuspendableAnswer { awaitCancellation() }

        val waiting = async { sut.awaitRate() }
        testScheduler.advanceTimeBy(QuickPayRepo.RATE_TIMEOUT.inWholeMilliseconds - 1)
        assertFalse(waiting.isCompleted)
        testScheduler.advanceTimeBy(2)

        assertTrue(waiting.isCompleted)
        assertNull(sut.reserve(1uL))
        assertEquals(0L, spend.spentCents)
    }

    @Test
    fun `no rate is fetched with one at hand, with quickpay off or before any import`() = test {
        sut.awaitRate()

        rates.update { emptyList() }
        settings.update { it.copy(quickPay = LIMITS.copy(isEnabled = false)) }
        sut.awaitRate()
        settings.update { WatchSettings() }
        sut.awaitRate()
        whenever(settingsStore.data).thenReturn(flow { throw IOException("disk") })
        sut.awaitRate()

        verify(currencyRepo, never()).refresh()
    }

    @Test
    fun `the last import decides however old it is, as the phone's own settings do`() = test {
        now = NOW + 30.days.inWholeMilliseconds
        assertNotNull(sut.reserve(1uL))

        settings.update { it.copy(phoneSyncedAtMs = null) }
        assertNotNull(sut.reserve(1uL))
    }

    @Test
    fun `a payment of exactly the phone's amount skips the hold and one sat more does not`() = test {
        assertNull(sut.reserve(FIVE_DOLLARS + 1uL))
        assertEquals(0L, spend.spentCents)

        assertNotNull(sut.reserve(FIVE_DOLLARS))
        assertEquals(500L, spend.spentCents)
    }

    @Test
    fun `the smallest payment skips the hold and counts as a whole cent`() = test {
        assertNotNull(sut.reserve(1uL))

        assertEquals(1L, spend.spentCents)
    }

    @Test
    fun `nothing skips the hold before the phone's settings were ever imported`() = test {
        settings.update { WatchSettings() }

        assertNull(sut.reserve(1uL))
        assertNull(sut.reserve(FIVE_DOLLARS))
    }

    @Test
    fun `nothing skips the hold while quickpay is off on the phone`() = test {
        settings.update { it.copy(quickPay = LIMITS.copy(isEnabled = false)) }

        assertNull(sut.reserve(1uL))
    }

    @Test
    fun `without a dollar rate nothing skips the hold`() = test {
        rates.update { listOf(rate("90000", quote = "EUR")) }
        assertNull(sut.reserve(1uL))

        for (price in listOf("n/a", "0", "-100000", "")) {
            rates.update { listOf(rate(price)) }
            assertNull(sut.reserve(1uL), price)
        }
        assertEquals(0L, spend.spentCents)

        rates.update { listOf(rate("100000")) }
        assertNotNull(sut.reserve(1uL))
    }

    @Test
    fun `the amount is valued and rounded as on the phone`() = test {
        // 5 dollars at 64 321.98 are 7 773.39 sats, which the phone rounds to 7 773.
        rates.update { listOf(rate("64321.98")) }
        assertNull(sut.reserve(7_774uL))

        // 7 773 sats are 499.97 cents, counted as 500; 100 sats are 6.43 cents, counted as 6.
        assertEquals(500L, sut.reserve(7_773uL)?.cents)
        assertEquals(6L, sut.reserve(100uL)?.cents)
    }

    @Test
    fun `a payment counts for no more than the phone's amount`() = test {
        // At 3 000 000 dollars a bitcoin 5 dollars round up to 167 sats, which are worth 501 cents.
        rates.update { listOf(rate("3000000")) }

        assertEquals(500L, sut.reserve(167uL)?.cents)
        assertNull(sut.reserve(168uL))
    }

    @Test
    fun `a payment that failed is taken out of the day's count again`() = test {
        repeat(2) { assertNotNull(sut.reserve(FIVE_DOLLARS)) }
        val failed = assertNotNull(sut.reserve(FIVE_DOLLARS))
        assertNull(sut.reserve(1uL))

        sut.release(failed)

        assertEquals(1_000L, spend.spentCents)
        assertNotNull(sut.reserve(FIVE_DOLLARS))
    }

    @Test
    fun `a payment of an earlier day is not taken out of today's count`() = test {
        val yesterday = assertNotNull(sut.reserve(FIVE_DOLLARS))
        now = NOW + 12.hours.inWholeMilliseconds
        assertNotNull(sut.reserve(FIVE_DOLLARS))

        sut.release(yesterday)

        assertEquals(500L, spend.spentCents)
        spend.failure = IOException("disk")
        sut.release(yesterday)
    }

    @Test
    fun `the limit follows the rate, not the sats`() = test {
        rates.update { listOf(rate("200000")) }

        assertNull(sut.reserve(2_501uL))
        assertNotNull(sut.reserve(2_500uL))
    }

    @Test
    fun `no amount and an absurd amount skip nothing`() = test {
        assertNull(sut.reserve(0uL))
        assertNull(sut.reserve(ULong.MAX_VALUE))
        assertNull(sut.reserve(Long.MAX_VALUE.toULong()))
    }

    @Test
    fun `the day's payments stop at the phone's daily limit`() = test {
        repeat(3) { assertNotNull(sut.reserve(FIVE_DOLLARS), "payment $it") }

        assertNull(sut.reserve(1uL))
        assertEquals(1_500L, spend.spentCents)
    }

    @Test
    fun `a payment that would pass the daily limit is refused whole`() = test {
        assertNotNull(sut.reserve(FIVE_DOLLARS))
        assertNotNull(sut.reserve(FIVE_DOLLARS))
        assertNotNull(sut.reserve(4_000uL))

        // 1 005 sats are 100.5 cents, counted as 101.
        assertNull(sut.reserve(1_005uL))
        assertNotNull(sut.reserve(1_000uL))
    }

    @Test
    fun `the next day starts a new count`() = test {
        repeat(3) { sut.reserve(FIVE_DOLLARS) }
        assertNull(sut.reserve(1uL))

        now = NOW + 12.hours.inWholeMilliseconds

        assertNotNull(sut.reserve(FIVE_DOLLARS))
        assertEquals("2026-11-29", spend.day)
    }

    @Test
    fun `the day is the watch's local date where that differs from the date in UTC`() = test {
        // Noon UTC on the 28th is 02:00 on the 29th at UTC+14.
        zone = ZoneId.of("Pacific/Kiritimati")
        repeat(3) { assertNotNull(sut.reserve(FIVE_DOLLARS)) }
        assertEquals("2026-11-29", spend.day)

        // 23:00 UTC, the 28th still, and 13:00 on the 29th there: the same day.
        now = NOW + 11.hours.inWholeMilliseconds
        assertNull(sut.reserve(1uL))

        // 10:30 UTC on the 29th is 00:30 on the 30th there: a new day, although UTC's date is the one counted.
        now = NOW + 22.hours.inWholeMilliseconds + 30.minutes.inWholeMilliseconds
        assertNotNull(sut.reserve(FIVE_DOLLARS))
        assertEquals("2026-11-30", spend.day)
    }

    @Test
    fun `settings or a count that cannot be read skip nothing`() = test {
        spend.failure = IOException("disk")
        assertNull(sut.reserve(1uL))

        spend.failure = null
        whenever(settingsStore.data).thenReturn(flow { throw IOException("disk") })
        assertNull(sut.reserve(1uL))
    }
}
