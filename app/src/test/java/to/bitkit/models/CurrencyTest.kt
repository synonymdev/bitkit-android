package to.bitkit.models

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class CurrencyTest {

    @Test
    fun `formatToModernDisplay uses space grouping`() {
        val sats = 123_456_789L

        val formatted = sats.formatToModernDisplay(Locale.US)

        assertEquals("123 456 789", formatted)
    }

    @Test
    fun `formatToModernDisplay handles zero`() {
        val formatted = 0L.formatToModernDisplay(Locale.US)

        assertEquals("0", formatted)
    }

    @Test
    fun `formatToClassicDisplay always shows eight decimals`() {
        val formatted = 0L.formatToClassicDisplay(Locale.US)

        assertEquals("0.00000000", formatted)
    }

    @Test
    fun `formatToClassicDisplay converts sats to btc`() {
        val sats = 12_345L // 0.00012345 BTC

        val formatted = sats.formatToClassicDisplay(Locale.US)

        assertEquals("0.00012345", formatted)
    }

    @Test
    fun `formatCurrencyWithSymbol places USD symbol before amount`() {
        val value = BigDecimal("10.50")

        val formatted = value.formatCurrencyWithSymbol("USD")

        assertEquals("$10.50", formatted)
    }

    @Test
    fun `formatCurrencyWithSymbol places GBP symbol before amount`() {
        val value = BigDecimal("10.50")

        val formatted = value.formatCurrencyWithSymbol("GBP")

        assertEquals("£10.50", formatted)
    }

    @Test
    fun `formatCurrencyWithSymbol places PLN symbol after amount`() {
        val value = BigDecimal("0.35")

        val formatted = value.formatCurrencyWithSymbol("PLN", "zł")

        assertEquals("0.35zł", formatted)
    }

    @Test
    fun `formatCurrencyWithSymbol places EUR symbol before amount`() {
        val value = BigDecimal("10.00")

        val formatted = value.formatCurrencyWithSymbol("EUR", "€")

        assertEquals("€10.00", formatted)
    }

    @Test
    fun `formatCurrencyWithSymbol places CZK symbol after amount`() {
        val value = BigDecimal("250.00")

        val formatted = value.formatCurrencyWithSymbol("CZK", "Kč")

        assertEquals("250.00Kč", formatted)
    }

    @Test
    fun `formatCurrencyWithSymbol places SEK symbol after amount`() {
        val value = BigDecimal("100.00")

        val formatted = value.formatCurrencyWithSymbol("SEK", "kr")

        assertEquals("100.00kr", formatted)
    }

    @Test
    fun `formatCurrencyWithSymbol places CHF symbol after amount`() {
        val value = BigDecimal("50.00")

        val formatted = value.formatCurrencyWithSymbol("CHF", "CHF")

        assertEquals("50.00CHF", formatted)
    }

    @Test
    fun `formatCurrencyWithSymbol falls back to currency code when no symbol provided`() {
        val value = BigDecimal("100.00")

        val formatted = value.formatCurrencyWithSymbol("PLN")

        // Without explicit symbol, falls back to Java Currency symbol (PLN in non-Polish locale)
        assertEquals("100.00PLN", formatted)
    }

    @Test
    fun `formatCurrencyWithSymbol handles unknown currency code`() {
        val value = BigDecimal("100.00")

        val formatted = value.formatCurrencyWithSymbol("XYZ")

        assertEquals("XYZ100.00", formatted)
    }

    @Test
    fun `formatCurrencyWithSymbol formats large amounts with grouping`() {
        val value = BigDecimal("1234567.89")

        val formatted = value.formatCurrencyWithSymbol("USD")

        assertEquals("$1,234,567.89", formatted)
    }

    @Test
    fun `ConvertedAmount formattedWithSymbol returns correct format for prefix currency`() {
        val converted = ConvertedAmount(
            value = BigDecimal("10.50"),
            formatted = "10.50",
            symbol = "$",
            currency = "USD",
            flag = "🇺🇸",
            sats = 1000L,
        )

        assertEquals("$10.50", converted.formattedWithSymbol())
    }

    @Test
    fun `ConvertedAmount formattedWithSymbol returns correct format for suffix currency`() {
        val converted = ConvertedAmount(
            value = BigDecimal("0.35"),
            formatted = "0.35",
            symbol = "zł",
            currency = "PLN",
            flag = "🇵🇱",
            sats = 100L,
        )

        assertEquals("0.35zł", converted.formattedWithSymbol())
    }
}
