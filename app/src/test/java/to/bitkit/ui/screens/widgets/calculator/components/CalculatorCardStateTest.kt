package to.bitkit.ui.screens.widgets.calculator.components

import org.junit.Before
import org.junit.Test
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.models.widget.resolveCalculatorSatsValue
import to.bitkit.ui.components.KEY_000
import to.bitkit.ui.components.KEY_DECIMAL
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.screens.widgets.calculator.CALCULATOR_FIAT_DECIMAL_PLACES
import to.bitkit.ui.screens.widgets.calculator.applyNumberPadInput
import to.bitkit.ui.screens.widgets.calculator.calculatorBtcValueToSats
import to.bitkit.ui.screens.widgets.calculator.calculatorDecimalSeparator
import to.bitkit.ui.screens.widgets.calculator.calculatorSatsToBtcValue
import to.bitkit.ui.screens.widgets.calculator.formatBitcoinPlaceholder
import to.bitkit.ui.screens.widgets.calculator.formatBitcoinValue
import to.bitkit.ui.screens.widgets.calculator.formatFiatPlaceholder
import to.bitkit.ui.screens.widgets.calculator.formatFiatValue
import to.bitkit.ui.screens.widgets.calculator.isBtcValueInSatsRange
import to.bitkit.ui.screens.widgets.calculator.sanitizeDecimalInput
import to.bitkit.ui.screens.widgets.calculator.sanitizeIntegerInput
import to.bitkit.ui.screens.widgets.calculator.shouldHydrateFiatFromStoredBtc
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalculatorCardStateTest {

    @Before
    fun setLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns true when btc exists and fiat values are empty`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "10000",
            storedFiatValue = "",
            currentFiatValue = "",
            displayUnit = BitcoinDisplayUnit.MODERN,
        )

        assertTrue(result)
    }

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns false when stored fiat exists`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "10000",
            storedFiatValue = "6.25",
            currentFiatValue = "",
            displayUnit = BitcoinDisplayUnit.MODERN,
        )

        assertFalse(result)
    }

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns false when current fiat is already set`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "10000",
            storedFiatValue = "",
            currentFiatValue = "1.23",
            displayUnit = BitcoinDisplayUnit.MODERN,
        )

        assertFalse(result)
    }

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns false when stored btc is empty`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "",
            storedFiatValue = "",
            currentFiatValue = "",
            displayUnit = BitcoinDisplayUnit.MODERN,
        )

        assertFalse(result)
    }

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns false when stored btc is zero`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "0",
            storedFiatValue = "",
            currentFiatValue = "",
            displayUnit = BitcoinDisplayUnit.MODERN,
        )

        assertFalse(result)
    }

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns false when classic btc is zero`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "0.00000000",
            storedFiatValue = "",
            currentFiatValue = "",
            displayUnit = BitcoinDisplayUnit.CLASSIC,
        )

        assertFalse(result)
    }

    @Test
    fun `toCalculatorDisplaySymbol trims and keeps up to two chars`() {
        assertEquals("$", " $ ".toCalculatorDisplaySymbol())
        assertEquals("zł", "zł".toCalculatorDisplaySymbol())
        assertEquals("C", "CHF".toCalculatorDisplaySymbol())
        assertEquals("X", " XDR ".toCalculatorDisplaySymbol())
    }

    @Test
    fun `sanitizeIntegerInput strips non-digit characters and leading zeros`() {
        assertEquals("88800000000", sanitizeIntegerInput("0888,,,,,,,.00000000"))
        assertEquals("12345", sanitizeIntegerInput("12,345"))
        assertEquals("100", sanitizeIntegerInput("1.0.0"))
        assertEquals("", sanitizeIntegerInput(".,,,"))
        assertEquals("42", sanitizeIntegerInput("42"))
        assertEquals("", sanitizeIntegerInput(""))
        assertEquals("0", sanitizeIntegerInput("0"))
        assertEquals("0", sanitizeIntegerInput("00"))
        assertEquals("1000", sanitizeIntegerInput("01000"))
        assertEquals("100", sanitizeIntegerInput("00100"))
    }

    @Test
    fun `sanitizeDecimalInput allows single dot and digits only`() {
        assertEquals("12.34", sanitizeDecimalInput("12.34"))
        assertEquals("12.34", sanitizeDecimalInput("12.3.4"))
        assertEquals("0.", sanitizeDecimalInput("0."))
        assertEquals(".5", sanitizeDecimalInput(".5"))
        assertEquals("1234", sanitizeDecimalInput("1,234"))
        assertEquals("", sanitizeDecimalInput(",,,"))
        assertEquals("100.00", sanitizeDecimalInput("1,00.00"))
    }

    @Test
    fun `sanitizeDecimalInput caps fraction digits when maxDecimalPlaces given`() {
        assertEquals("12.34", sanitizeDecimalInput("12.345678", maxDecimalPlaces = 2))
        assertEquals("12.34", sanitizeDecimalInput("12.34", maxDecimalPlaces = 2))
        assertEquals("12", sanitizeDecimalInput("12", maxDecimalPlaces = 2))
        assertEquals("0.", sanitizeDecimalInput("0.", maxDecimalPlaces = 2))
        assertEquals(".5", sanitizeDecimalInput(".5", maxDecimalPlaces = 2))
        assertEquals("12.", sanitizeDecimalInput("12.", maxDecimalPlaces = 2))
    }

    @Test
    fun `formatBitcoinValue groups modern sats without changing raw value`() {
        assertEquals("1 345", formatBitcoinValue("1345", BitcoinDisplayUnit.MODERN))
        assertEquals("12 345", formatBitcoinValue("12345", BitcoinDisplayUnit.MODERN))
        assertEquals("1 234 567", formatBitcoinValue("1234567", BitcoinDisplayUnit.MODERN))
    }

    @Test
    fun `formatBitcoinValue groups classic bitcoin integer part`() {
        assertEquals("1 345.67", formatBitcoinValue("1345.67", BitcoinDisplayUnit.CLASSIC))
        assertEquals("0.00010000", formatBitcoinValue("0.00010000", BitcoinDisplayUnit.CLASSIC))
    }

    @Test
    fun `calculatorSatsToBtcValue formats canonical sats for display unit`() {
        assertEquals("10000", calculatorSatsToBtcValue(10_000L, BitcoinDisplayUnit.MODERN))
        assertEquals("0.00010000", calculatorSatsToBtcValue(10_000L, BitcoinDisplayUnit.CLASSIC))
    }

    @Test
    fun `calculatorBtcValueToSats clamps values above sats range`() {
        assertEquals(Long.MAX_VALUE, calculatorBtcValueToSats("999999999999999999999", BitcoinDisplayUnit.MODERN))
        assertEquals(Long.MAX_VALUE, calculatorBtcValueToSats("100000000000", BitcoinDisplayUnit.CLASSIC))
    }

    @Test
    fun `isBtcValueInSatsRange rejects values above sats range`() {
        assertTrue(isBtcValueInSatsRange("", BitcoinDisplayUnit.MODERN))
        assertTrue(isBtcValueInSatsRange("9223372036854775807", BitcoinDisplayUnit.MODERN))
        assertFalse(isBtcValueInSatsRange("9223372036854775808", BitcoinDisplayUnit.MODERN))
        assertTrue(isBtcValueInSatsRange("92233720368.54775807", BitcoinDisplayUnit.CLASSIC))
        assertFalse(isBtcValueInSatsRange("92233720368.54775808", BitcoinDisplayUnit.CLASSIC))
    }

    @Test
    fun `resolveCalculatorSatsValue prefers stored canonical sats`() {
        val values = CalculatorValues(
            btcValue = "1.00000000",
            satsValue = 10_000L,
            displayUnit = BitcoinDisplayUnit.CLASSIC,
        )

        assertEquals(10_000L, values.resolveCalculatorSatsValue())
    }

    @Test
    fun `resolveCalculatorSatsValue infers legacy modern integer values`() {
        val values = CalculatorValues(btcValue = "10000")

        assertEquals(10_000L, values.resolveCalculatorSatsValue())
    }

    @Test
    fun `resolveCalculatorSatsValue infers legacy classic decimal values`() {
        val values = CalculatorValues(btcValue = "0.00010000")

        assertEquals(10_000L, values.resolveCalculatorSatsValue())
    }

    @Test
    fun `formatFiatValue groups fiat integer part`() {
        assertEquals("1,345.67", formatFiatValue("1345.67"))
        assertEquals("39,621.05", formatFiatValue("39,621.05"))
        assertEquals("388,056,887.45", formatFiatValue("388056887.45"))
        assertEquals("8.29", formatFiatValue("8.29"))
    }

    @Test
    fun `formatFiatPlaceholder returns missing decimal zeros`() {
        assertEquals("0", formatFiatPlaceholder(""))
        assertEquals("", formatFiatPlaceholder("1"))
        assertEquals("00", formatFiatPlaceholder("1."))
        assertEquals("0", formatFiatPlaceholder("1.2"))
        assertEquals("", formatFiatPlaceholder("1.23"))
        assertEquals("0", formatFiatPlaceholder("1,2", Locale.GERMANY))
    }

    @Test
    fun `formatBitcoinPlaceholder returns missing classic decimal zeros`() {
        assertEquals("0", formatBitcoinPlaceholder("", BitcoinDisplayUnit.MODERN))
        assertEquals("0.00000000", formatBitcoinPlaceholder("", BitcoinDisplayUnit.CLASSIC))
        assertEquals(".00000000", formatBitcoinPlaceholder("1", BitcoinDisplayUnit.CLASSIC))
        assertEquals("", formatBitcoinPlaceholder("1.2", BitcoinDisplayUnit.MODERN))
        assertEquals("00000000", formatBitcoinPlaceholder("1.", BitcoinDisplayUnit.CLASSIC))
        assertEquals("0000", formatBitcoinPlaceholder("1.2345", BitcoinDisplayUnit.CLASSIC))
        assertEquals("", formatBitcoinPlaceholder("1.23456789", BitcoinDisplayUnit.CLASSIC))
        assertEquals("0000000", formatBitcoinPlaceholder("1,2", BitcoinDisplayUnit.CLASSIC, Locale.GERMANY))
    }

    @Test
    fun `formatted values use app money separators`() {
        assertEquals(".", calculatorDecimalSeparator())
        assertEquals(
            "1 345.67",
            formatBitcoinValue("1345.67", BitcoinDisplayUnit.CLASSIC, Locale.GERMANY),
        )
        assertEquals("1,345.67", formatFiatValue("1345,67", Locale.GERMANY))
        assertEquals("388,056,887.45", formatFiatValue("388056887,45", Locale.GERMANY))
    }

    @Test
    fun `applyNumberPadInput builds integer values`() {
        assertEquals("1", applyNumberPadInput("", "1", maxDecimalPlaces = null))
        assertEquals("1000", applyNumberPadInput("1", KEY_000, maxDecimalPlaces = null))
        assertEquals("100", applyNumberPadInput("1000", KEY_DELETE, maxDecimalPlaces = null))
        assertEquals("100", applyNumberPadInput("100", KEY_DECIMAL, maxDecimalPlaces = null))
    }

    @Test
    fun `applyNumberPadInput builds decimal values`() {
        assertEquals("0.", applyNumberPadInput("", KEY_DECIMAL, maxDecimalPlaces = 2))
        assertEquals("0.5", applyNumberPadInput("0.", "5", maxDecimalPlaces = 2))
        assertEquals("0.56", applyNumberPadInput("0.5", "6", maxDecimalPlaces = 2))
        assertEquals("0.56", applyNumberPadInput("0.56", "7", maxDecimalPlaces = 2))
        assertEquals("0.5", applyNumberPadInput("0.56", KEY_DELETE, maxDecimalPlaces = 2))
    }

    @Test
    fun `applyNumberPadInput deletes from grouped fiat values`() {
        assertEquals(
            "3960.5",
            applyNumberPadInput("3,960.50", KEY_DELETE, CALCULATOR_FIAT_DECIMAL_PLACES, Locale.GERMANY),
        )
        assertEquals(
            "396",
            applyNumberPadInput("3,960", KEY_DELETE, CALCULATOR_FIAT_DECIMAL_PLACES, Locale.GERMANY),
        )
        assertEquals(
            "3.9",
            applyNumberPadInput("3,96", KEY_DELETE, CALCULATOR_FIAT_DECIMAL_PLACES, Locale.GERMANY),
        )
    }
}
