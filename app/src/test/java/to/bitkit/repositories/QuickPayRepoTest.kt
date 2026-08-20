package to.bitkit.repositories

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.USD
import to.bitkit.test.BaseUnitTest
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class QuickPayRepoTest : BaseUnitTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cacheStore = CacheStore(context)
    private val settingsStore: SettingsStore = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val clock = MutableClock(Instant.parse("2026-08-15T12:00:00Z"))
    private val settingsData = MutableStateFlow(
        SettingsData(isQuickPayEnabled = true, quickPayAmount = 5, quickPayDailyLimitMultiplier = 5),
    )

    private lateinit var sut: QuickPayRepo

    @Before
    fun setUp() = runBlocking {
        cacheStore.reset()
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever(currencyRepo.convertFiatToSats(5.0, USD)).thenAnswer { 1000uL }
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenAnswer { invocation ->
            val sats = invocation.getArgument<Long>(0)
            val usd = 5.0 * sats.toDouble() / 1000.0
            ConvertedAmount(
                value = BigDecimal.valueOf(usd),
                formatted = usd.toString(),
                symbol = "$",
                currency = "USD",
                flag = "",
                sats = sats,
                locale = Locale.US,
            )
        }
        sut = QuickPayRepo(
            cacheStore = cacheStore,
            settingsStore = settingsStore,
            currencyRepo = currencyRepo,
            ioDispatcher = testDispatcher,
            clock = clock,
        )
    }

    @After
    fun tearDown() = runBlocking { cacheStore.reset() }

    @Test
    fun `tryReserve on clock rollback keeps existing spend`() = test {
        assertNotNull(sut.tryReserve(500u).getOrThrow())
        clock.instant = Instant.parse("2026-08-14T12:00:00Z")

        assertNotNull(sut.tryReserve(200u).getOrThrow())
        assertEquals(350L, cacheStore.data.first().quickPaySpentCentsToday)
        clock.instant = Instant.parse("2026-08-15T12:00:00Z")
        assertEquals(350L, cacheStore.data.first().quickPaySpentCentsToday)
    }

    @Test
    fun `tryReserve accumulates on the same day and resets on a new day`() = test {
        assertNotNull(sut.tryReserve(400u).getOrThrow())
        assertNotNull(sut.tryReserve(300u).getOrThrow())
        assertEquals(350L, cacheStore.data.first().quickPaySpentCentsToday)

        clock.instant = Instant.parse("2026-08-16T12:00:00Z")
        assertNotNull(sut.tryReserve(800u).getOrThrow())
        assertEquals(400L, cacheStore.data.first().quickPaySpentCentsToday)
    }

    @Test
    fun `tryReserve reserves under the cap and rejects over it`() = test {
        settingsData.value = settingsData.value.copy(quickPayDailyLimitMultiplier = 2)
        assertNotNull(sut.tryReserve(1000u).getOrThrow())
        assertNotNull(sut.tryReserve(1000u).getOrThrow())
        assertNull(sut.tryReserve(1000u).getOrThrow())
        assertEquals(1000L, cacheStore.data.first().quickPaySpentCentsToday)
    }

    @Test
    fun `releaseUnbound rolls back a reservation`() = test {
        val reserved = requireNotNull(sut.tryReserve(1000u).getOrThrow())

        sut.releaseUnbound(reserved).getOrThrow()

        assertEquals(0L, cacheStore.data.first().quickPaySpentCentsToday)
    }

    @Test
    fun `releaseUnbound on a prior day does not decrement the new day`() = test {
        val old = requireNotNull(sut.tryReserve(1000u).getOrThrow())
        clock.instant = Instant.parse("2026-08-16T12:00:00Z")
        assertNotNull(sut.tryReserve(800u).getOrThrow())

        sut.releaseUnbound(old).getOrThrow()

        assertEquals(400L, cacheStore.data.first().quickPaySpentCentsToday)
    }

    @Test
    fun `release frees pending spend by payment hash`() = test {
        val reserved = requireNotNull(sut.tryReserve(1000u).getOrThrow())
        sut.remember("abc", reserved).getOrThrow()

        sut.release("abc").getOrThrow()

        assertEquals(0L, cacheStore.data.first().quickPaySpentCentsToday)
        assertNull(sut.reservation("abc").getOrThrow())
    }

    @Test
    fun `clear keeps spend after success`() = test {
        val reserved = requireNotNull(sut.tryReserve(1000u).getOrThrow())
        sut.remember("abc", reserved).getOrThrow()

        sut.clear("abc").getOrThrow()

        assertEquals(500L, cacheStore.data.first().quickPaySpentCentsToday)
        assertNull(sut.reservation("abc").getOrThrow())
    }

    @Test
    fun `release on a prior day does not decrement the new day`() = test {
        val old = requireNotNull(sut.tryReserve(1000u).getOrThrow())
        sut.remember("old", old).getOrThrow()
        clock.instant = Instant.parse("2026-08-16T12:00:00Z")
        assertNotNull(sut.tryReserve(800u).getOrThrow())

        sut.release("old").getOrThrow()

        assertEquals(400L, cacheStore.data.first().quickPaySpentCentsToday)
        assertNull(sut.reservation("old").getOrThrow())
    }

    @Test
    fun `canApply is true under threshold and cap`() = test {
        assertTrue(sut.canApply(500u).getOrThrow())
    }

    @Test
    fun `canApply is false when daily cap would be exceeded`() = test {
        settingsData.value = settingsData.value.copy(quickPayDailyLimitMultiplier = 1)
        assertNotNull(sut.tryReserve(1000u).getOrThrow())

        assertFalse(sut.canApply(1000u).getOrThrow())
    }

    @Test
    fun `canApply is false when disabled`() = test {
        settingsData.value = settingsData.value.copy(isQuickPayEnabled = false)

        assertFalse(sut.canApply(500u).getOrThrow())
    }

    @Test
    fun `zero cent conversion at a full cap is rejected`() = test {
        stubZeroCentConversion(7L)
        repeat(5) { assertNotNull(sut.tryReserve(1000u).getOrThrow()) }

        assertFalse(sut.canApply(7u).getOrThrow())
        assertNull(sut.tryReserve(7u).getOrThrow())
    }

    @Test
    fun `zero cent conversion on a fresh day reserves one cent`() = test {
        stubZeroCentConversion(7L)

        val reserved = requireNotNull(sut.tryReserve(7u).getOrThrow())

        assertEquals(1L, reserved.amountCents)
        assertEquals(1L, cacheStore.data.first().quickPaySpentCentsToday)
        assertTrue(sut.canApply(7u).getOrThrow())
    }

    private fun stubZeroCentConversion(dustSats: Long) {
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenAnswer { invocation ->
            val sats = invocation.getArgument<Long>(0)
            val usd = if (sats == dustSats) 0.004 else 5.0 * sats.toDouble() / 1000.0
            ConvertedAmount(
                value = BigDecimal.valueOf(usd),
                formatted = usd.toString(),
                symbol = "$",
                currency = "USD",
                flag = "",
                sats = sats,
                locale = Locale.US,
            )
        }
    }

    @Test
    fun `tryReserve fails with conversion error when rates are unavailable`() = test {
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenAnswer {
            throw QuickPayConversionError()
        }

        val result = sut.tryReserve(500u)

        assertTrue(result.exceptionOrNull() is QuickPayConversionError)
    }
}

private class MutableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}
