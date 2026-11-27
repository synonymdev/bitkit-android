package to.bitkit.wear.ui.screens.home

import org.junit.Test
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.models.rate
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeUiStateTest {
    private val ready = WalletState(WalletStatus.Ready, 150_000uL)
    private val rates = listOf(rate("100000"))

    @Test
    fun `shows sats first with fiat secondary by default`() {
        val state = homeUiState(ready, rates, WatchSettings(), Locale.US)

        assertEquals("₿ 150 000", state.primaryAmount)
        assertEquals("$150.00", state.secondaryAmount)
    }

    @Test
    fun `shows fiat first when the user picks it`() {
        val state = homeUiState(ready, rates, WatchSettings(primaryDisplay = PrimaryDisplay.FIAT), Locale.US)

        assertEquals("$150.00", state.primaryAmount)
        assertEquals("₿ 150 000", state.secondaryAmount)
    }

    @Test
    fun `falls back to sats alone without a rate for the currency`() {
        val settings = WatchSettings(primaryDisplay = PrimaryDisplay.FIAT, selectedCurrency = "EUR")
        val state = homeUiState(ready, rates, settings, Locale.US)

        assertEquals("₿ 150 000", state.primaryAmount)
        assertNull(state.secondaryAmount)
    }

    @Test
    fun `an error shows only while no balance is known`() {
        assertTrue(homeUiState(WalletState(WalletStatus.Error), rates, WatchSettings(), Locale.US).isError)
        assertFalse(homeUiState(ready.copy(status = WalletStatus.Error), rates, WatchSettings(), Locale.US).isError)
    }
}
