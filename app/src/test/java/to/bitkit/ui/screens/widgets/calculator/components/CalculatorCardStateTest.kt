package to.bitkit.ui.screens.widgets.calculator.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalculatorCardStateTest {

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns true when btc exists and fiat values are empty`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "10000",
            storedFiatValue = "",
            currentFiatValue = "",
        )

        assertTrue(result)
    }

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns false when stored fiat exists`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "10000",
            storedFiatValue = "6.25",
            currentFiatValue = "",
        )

        assertFalse(result)
    }

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns false when current fiat is already set`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "10000",
            storedFiatValue = "",
            currentFiatValue = "1.23",
        )

        assertFalse(result)
    }

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns false when stored btc is empty`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "",
            storedFiatValue = "",
            currentFiatValue = "",
        )

        assertFalse(result)
    }

    @Test
    fun `shouldHydrateFiatFromStoredBtc returns false when stored btc is zero`() {
        val result = shouldHydrateFiatFromStoredBtc(
            storedBtcValue = "0",
            storedFiatValue = "",
            currentFiatValue = "",
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
}
