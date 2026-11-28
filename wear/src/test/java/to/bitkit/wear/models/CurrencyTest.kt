package to.bitkit.wear.models

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrencyTest {
    @Test
    fun `sats group by thousands with spaces`() {
        assertEquals("1 234 567", 1_234_567L.formatToModernDisplay(Locale.US))
        assertEquals("0", 0L.formatToModernDisplay(Locale.US))
        assertEquals("1 000", 1_000L.formatToModernDisplay(Locale.US))
        assertEquals("21 000 000", 21_000_000L.formatToModernDisplay(Locale.US))
    }

    @Test
    fun `the classic unit writes bitcoin with eight decimals`() {
        assertEquals("0.00050000", 50_000L.formatToClassicDisplay(Locale.US))
        assertEquals("1.00000000", 100_000_000L.formatToClassicDisplay(Locale.GERMANY))
        assertEquals("0.00000001", 1L.formatMoney(BitcoinDisplayUnit.CLASSIC, Locale.US))
        assertEquals("50 000", 50_000L.formatMoney(BitcoinDisplayUnit.MODERN, Locale.US))
    }

    @Test
    fun `fiat value converts sats at the rate and formats with the symbol`() {
        val value = fiatValue(sats = 150_000, rate = rate("100000"))

        assertEquals("150.00", value?.formatCurrency(Locale.US))
    }

    @Test
    fun `an unusable rate has no fiat value`() {
        assertNull(fiatValue(sats = 1_000, rate = rate("n/a")))
        assertNull(fiatValue(sats = 1_000, rate = rate("0")))
    }
}

internal fun rate(lastPrice: String, quote: String = USD, symbol: String = "$") = FxRate(
    symbol = "BTC$quote",
    lastPrice = lastPrice,
    base = "BTC",
    baseName = "Bitcoin",
    quote = quote,
    quoteName = quote,
    currencySymbol = symbol,
    currencyFlag = "",
    lastUpdatedAt = 0,
)
