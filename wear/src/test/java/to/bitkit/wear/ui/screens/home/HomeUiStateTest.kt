package to.bitkit.wear.ui.screens.home

import org.junit.Test
import to.bitkit.wear.data.NfcState
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.models.rate
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.ui.components.NfcHintState
import to.bitkit.wear.ui.components.nfcHintState
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeUiStateTest {
    private val ready = WalletState(WalletStatus.Ready, 150_000uL, hasSynced = true)
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
        val wallet = WalletState(WalletStatus.Ready, 1_000uL, hasSynced = true)
        val state = homeUiState(wallet, rates, WatchSettings(), Locale.US)

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

    @Test
    fun `never shows a balance no sync confirmed this session, such as 0 restored offline`() {
        val unsynced = WalletState(WalletStatus.Connecting, 0uL, hasSynced = false)

        val state = homeUiState(unsynced, rates, WatchSettings(), Locale.US)

        assertNull(state.primaryAmount)
        assertNull(state.secondaryAmount)
        assertFalse(state.isWalletReady)
    }

    @Test
    fun `offline keeps the last synced balance and is not ready to pay`() {
        val state = homeUiState(ready.copy(status = WalletStatus.Offline), rates, WatchSettings(), Locale.US)

        assertTrue(state.isOffline)
        assertFalse(state.isError)
        assertFalse(state.isLoading)
        assertEquals(DisplayAmount("₿", "150 000"), state.primaryAmount)
        assertFalse(state.isWalletReady)
    }

    @Test
    fun `only a synced, settled wallet is ready`() {
        assertTrue(homeUiState(ready, rates, WatchSettings(), Locale.US).isWalletReady)
        val connecting = homeUiState(ready.copy(status = WalletStatus.Connecting), rates, WatchSettings(), Locale.US)
        assertFalse(connecting.isWalletReady)
        val error = homeUiState(ready.copy(status = WalletStatus.Error), rates, WatchSettings(), Locale.US)
        assertFalse(error.isWalletReady)
    }

    @Test
    fun `a wallet that still shows its balance while locked or paused is not ready`() {
        for (status in listOf(WalletStatus.Locked, WalletStatus.Paused, WalletStatus.Idle)) {
            val state = homeUiState(ready.copy(status = status), rates, WatchSettings(), Locale.US)

            assertTrue(state.primaryAmount != null, "$status")
            assertFalse(state.isWalletReady, "$status")
        }
    }

    @Test
    fun `the state carries the nfc state it was given`() {
        assertEquals(NfcState.Off, homeUiState(ready, rates, WatchSettings(), Locale.US, NfcState.Off).nfc)
    }

    @Test
    fun `the nfc hint shows only once the wallet is ready, never while loading, failed, offline or locked`() {
        val statuses = listOf(WalletStatus.Connecting, WalletStatus.Error, WalletStatus.Offline, WalletStatus.Locked)
        statuses.forEach { status ->
            val state = homeUiState(ready.copy(status = status), rates, WatchSettings(), Locale.US)
            assertEquals(NfcHintState.Hidden, nfcHintState(NfcState.On, state.isWalletReady, isAmbient = false))
            assertEquals(NfcHintState.Hidden, nfcHintState(NfcState.Off, state.isWalletReady, isAmbient = false))
        }
        val readyState = homeUiState(ready, rates, WatchSettings(), Locale.US)
        assertEquals(NfcHintState.Active, nfcHintState(NfcState.On, readyState.isWalletReady, isAmbient = false))
        assertEquals(NfcHintState.Disabled, nfcHintState(NfcState.Off, readyState.isWalletReady, isAmbient = false))
    }
}
