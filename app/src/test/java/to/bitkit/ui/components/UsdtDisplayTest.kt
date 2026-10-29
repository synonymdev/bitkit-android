package to.bitkit.ui.components

import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import to.bitkit.models.FxRate
import to.bitkit.models.PaykitExchangeRate
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.ui.screens.wallets.usdtBalanceUpdating
import to.bitkit.ui.screens.wallets.usdtHeadlineSats
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsdtDisplayTest {
    @Test
    fun `updating remains visible until balance and rates finish`() {
        val now = 1_800_000_000_000L
        val currency = CurrencyState(
            rates = persistentListOf(
                FxRate(
                    symbol = "BTCUSD", lastPrice = "100000", base = "BTC",
                    baseName = "Bitcoin", quote = "USD", quoteName = "US Dollar", currencySymbol = "$",
                    currencyFlag = "", lastUpdatedAt = now - 300_000
                )
            ),
            isRefreshing = true,
        )
        val usdt = UsdtWalletState(balance = 1_000_000uL, balanceIsStale = false, refreshing = true)
        assertTrue(usdtBalanceUpdating(usdt, currency, now))
        assertTrue(usdtBalanceUpdating(usdt.copy(refreshing = false), currency, now))
        assertFalse(usdtBalanceUpdating(usdt.copy(refreshing = false), currency.copy(isRefreshing = false), now))
    }

    @Test
    fun `Home uses old cached rates without making them valid for payments`() {
        val now = 1_800_000_000_000L
        val oldRate = PaykitExchangeRate("100000", now - 3_600_000)
        assertEquals(1234L, usdtHeadlineSats(1_234_567uL, true, oldRate, now))
        assertNull(usdtDisplaySats("1.234567", oldRate, now))
        assertNull(usdtHeadlineSats(null, true, oldRate, now))
        assertNull(usdtHeadlineSats(1uL, true, null, now))
        assertNull(usdtHeadlineSats(1uL, true, oldRate.copy(timestampMillis = now + 1), now))
        assertNull(usdtHeadlineSats(1uL, true, oldRate.copy(price = "0"), now))
        assertEquals(0L, usdtHeadlineSats(0uL, true, null, now))
        assertEquals(0L, usdtHeadlineSats(null, false, null, now))
    }

    @Test
    fun `exact amounts preserve token precision across regions`() {
        listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("nl-NL"), Locale.FRANCE).forEach { locale ->
            listOf(
                0uL to "0.00",
                1uL to "0.000001",
                4_996_000uL to "4.996",
                1_234_567uL to "1.234567",
                3_500_000uL to "3.50",
                ULong.MAX_VALUE to "18,446,744,073,709.551615",
            ).forEach { (amount, expected) ->
                assertEquals(expected, usdtExactAmount(amount, locale), locale.toString())
            }
        }
    }

    @Test
    fun `bitcoin equivalent requires a fresh rate and fits display bounds`() {
        val now = 1_800_000_000_000L
        val rate = PaykitExchangeRate("100000", now)
        assertEquals(3500L, usdtDisplaySats("3.5", rate, now))
        assertEquals(0L, usdtDisplaySats("0.000001", rate, now))
        assertNull(usdtDisplaySats("3.5", null, now))
        assertNull(usdtDisplaySats("3.5", rate.copy(timestampMillis = now - 601_000), now))
        assertNull(usdtDisplaySats("—", rate, now))
        assertNull(usdtDisplaySats("-1", rate, now))
        assertNull(usdtDisplaySats("999999999999999999999999", rate, now))
    }

    @Test
    fun `overview amounts round to cents without hiding small balances`() {
        listOf(
            0uL to "0.00",
            1uL to "<0.01",
            9999uL to "<0.01",
            10000uL to "0.01",
            10000000uL to "10.00",
            123456789uL to "123.46",
            1025000uL to "1.03",
            1234567890uL to "1,234.57",
        ).forEach { (amount, expected) ->
            listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("nl-NL"), Locale.FRANCE).forEach { locale ->
                assertEquals(expected, usdtOverviewAmount(amount, locale), locale.toString())
            }
        }
    }
}
