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

        assertEquals(DisplayAmount("₿", "150 000"), state.primaryAmount)
        assertEquals(DisplayAmount("$", "150.00"), state.secondaryAmount)
        assertTrue(state.isBitcoinPrimary)
    }

    @Test
    fun `shows fiat first when the user picks it`() {
        val state = homeUiState(ready, rates, WatchSettings(primaryDisplay = PrimaryDisplay.FIAT), Locale.US)

        assertEquals(DisplayAmount("$", "150.00"), state.primaryAmount)
        assertEquals(DisplayAmount("₿", "150 000"), state.secondaryAmount)
    }

    @Test
    fun `falls back to sats alone without a rate for the currency`() {
        val settings = WatchSettings(primaryDisplay = PrimaryDisplay.FIAT, selectedCurrency = "EUR")
        val state = homeUiState(ready, rates, settings, Locale.US)

        assertEquals(DisplayAmount("₿", "150 000"), state.primaryAmount)
        assertNull(state.secondaryAmount)
    }

    @Test
    fun `groups the large bitcoin amount by thousands like the phone`() {
        val state = homeUiState(WalletState(WalletStatus.Ready, 1_000uL), rates, WatchSettings(), Locale.US)

        assertEquals(DisplayAmount("₿", "1 000"), state.primaryAmount)
    }

    @Test
    fun `places the symbol after the amount for suffix currencies`() {
        val settings = WatchSettings(primaryDisplay = PrimaryDisplay.FIAT, selectedCurrency = "CHF")
        val state = homeUiState(ready, listOf(rate("100000", quote = "CHF", symbol = "CHF")), settings, Locale.US)

        assertEquals(DisplayAmount("CHF", "150.00", isSymbolSuffix = true), state.primaryAmount)
        assertFalse(state.isBitcoinPrimary)
    }

    @Test
    fun `a failed start shows the error even with a known balance, and stops loading`() {
        val state = homeUiState(ready.copy(status = WalletStatus.Error), rates, WatchSettings(), Locale.US)

        assertTrue(state.isError)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a missing seed is reported so the app leaves the balance`() {
        assertTrue(homeUiState(WalletState(WalletStatus.Missing), rates, WatchSettings(), Locale.US).isMissing)
    }

    @Test
    fun `loads until the wallet is ready, keeping a known balance visible`() {
        assertTrue(homeUiState(WalletState(), rates, WatchSettings(), Locale.US).isLoading)
        val connecting = homeUiState(ready.copy(status = WalletStatus.Connecting), rates, WatchSettings(), Locale.US)
        assertTrue(connecting.isLoading)
        assertEquals(DisplayAmount("₿", "150 000"), connecting.primaryAmount)
        assertFalse(homeUiState(ready, rates, WatchSettings(), Locale.US).isLoading)
        assertFalse(homeUiState(WalletState(WalletStatus.Error), rates, WatchSettings(), Locale.US).isLoading)
    }
}
