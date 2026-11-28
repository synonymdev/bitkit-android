package to.bitkit.wear.repositories

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** At 100 000 dollars a bitcoin one sat is a tenth of a cent, so 5 dollars are exactly 5 000 sats. */
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
    private val settings = MutableStateFlow(WatchSettings(quickPay = LIMITS, phoneSyncedAtMs = NOW))
    private val rates = MutableStateFlow(listOf(rate("100000"), rate("90000", quote = "EUR")))
    private var now = NOW
    private var ratesAge: Duration? = 1.minutes

    private lateinit var sut: QuickPayRepo

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(settings)
        whenever(currencyRepo.rates).thenReturn(rates)
        whenever(currencyRepo.ratesAge()).thenAnswer { ratesAge }
        sut = QuickPayRepo(testDispatcher, settingsStore, currencyRepo, spend, { now }, { ZoneId.of("UTC") })
    }

    @Test
    fun `a payment of exactly the phone's amount skips the hold and one sat more does not`() = test {
        assertFalse(sut.reserve(FIVE_DOLLARS + 1uL))
        assertEquals(0L, spend.spentCents)

        assertTrue(sut.reserve(FIVE_DOLLARS))
        assertEquals(500L, spend.spentCents)
    }

    @Test
    fun `the smallest payment skips the hold and counts as a whole cent`() = test {
        assertTrue(sut.reserve(1uL))

        assertEquals(1L, spend.spentCents)
    }

    @Test
    fun `nothing skips the hold before the phone's settings were ever imported`() = test {
        settings.update { WatchSettings() }

        assertFalse(sut.reserve(1uL))
        assertFalse(sut.reserve(FIVE_DOLLARS))
    }

    @Test
    fun `nothing skips the hold while quickpay is off on the phone`() = test {
        settings.update { it.copy(quickPay = LIMITS.copy(isEnabled = false)) }

        assertFalse(sut.reserve(1uL))
    }

    @Test
    fun `limits without the time of their import skip nothing`() = test {
        settings.update { it.copy(phoneSyncedAtMs = null) }

        assertFalse(sut.reserve(1uL))
    }

    @Test
    fun `an import older than fifteen minutes, or from the future, skips nothing`() = test {
        now = NOW + QuickPayRepo.MAX_SETTINGS_AGE.inWholeMilliseconds
        assertTrue(sut.reserve(1uL))

        now += 1
        assertFalse(sut.reserve(1uL))

        now = NOW - 1
        assertFalse(sut.reserve(1uL))
    }

    @Test
    fun `without a fresh dollar rate nothing skips the hold`() = test {
        ratesAge = null
        assertFalse(sut.reserve(1uL))

        ratesAge = QuickPayRepo.MAX_RATES_AGE + 1.milliseconds
        assertFalse(sut.reserve(1uL))

        ratesAge = QuickPayRepo.MAX_RATES_AGE
        rates.update { listOf(rate("90000", quote = "EUR")) }
        assertFalse(sut.reserve(1uL))

        for (price in listOf("n/a", "0", "-100000", "")) {
            rates.update { listOf(rate(price)) }
            assertFalse(sut.reserve(1uL), price)
        }
        assertEquals(0L, spend.spentCents)

        rates.update { listOf(rate("100000")) }
        assertTrue(sut.reserve(1uL))
    }

    @Test
    fun `the limit follows the rate, not the sats`() = test {
        rates.update { listOf(rate("200000")) }

        assertFalse(sut.reserve(2_501uL))
        assertTrue(sut.reserve(2_500uL))
    }

    @Test
    fun `no amount and an absurd amount skip nothing`() = test {
        assertFalse(sut.reserve(0uL))
        assertFalse(sut.reserve(ULong.MAX_VALUE))
        assertFalse(sut.reserve(Long.MAX_VALUE.toULong()))
    }

    @Test
    fun `the day's payments stop at the phone's daily limit`() = test {
        repeat(3) { assertTrue(sut.reserve(FIVE_DOLLARS), "payment $it") }

        assertFalse(sut.reserve(1uL))
        assertEquals(1_500L, spend.spentCents)
    }

    @Test
    fun `a payment that would pass the daily limit is refused whole`() = test {
        assertTrue(sut.reserve(FIVE_DOLLARS))
        assertTrue(sut.reserve(FIVE_DOLLARS))
        assertTrue(sut.reserve(4_000uL))

        assertFalse(sut.reserve(1_001uL))
        assertTrue(sut.reserve(1_000uL))
    }

    @Test
    fun `the next day starts a new count, read in the watch's time zone`() = test {
        repeat(3) { sut.reserve(FIVE_DOLLARS) }
        assertFalse(sut.reserve(1uL))

        now = NOW + 12.hours.inWholeMilliseconds
        settings.update { it.copy(phoneSyncedAtMs = now) }

        assertTrue(sut.reserve(FIVE_DOLLARS))
        assertEquals("2026-11-29", spend.day)
    }

    @Test
    fun `settings or a count that cannot be read skip nothing`() = test {
        spend.failure = IOException("disk")
        assertFalse(sut.reserve(1uL))

        spend.failure = null
        whenever(settingsStore.data).thenReturn(flow { throw IOException("disk") })
        assertFalse(sut.reserve(1uL))
    }
}
