package to.bitkit.ui.screens.widgets.calculator.components

import org.junit.Before
import org.junit.Test
import to.bitkit.models.BitcoinDisplayUnit
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
}
