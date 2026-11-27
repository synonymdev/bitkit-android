package to.bitkit.wear.ui.components

import org.junit.Test
import kotlin.test.assertEquals

class NfcHintStateTest {
    @Test
    fun `a ready wallet shows the hint for nfc on, the disabled hint for nfc off, and nothing without nfc`() {
        assertEquals(NfcHintState.Active, nfcHintState(NfcState.On, isWalletReady = true, isAmbient = false))
        assertEquals(NfcHintState.Disabled, nfcHintState(NfcState.Off, isWalletReady = true, isAmbient = false))
        assertEquals(NfcHintState.Hidden, nfcHintState(NfcState.Unavailable, isWalletReady = true, isAmbient = false))
    }

    @Test
    fun `a wallet that is not ready hides the hint in every nfc state`() {
        NfcState.entries.forEach {
            assertEquals(NfcHintState.Hidden, nfcHintState(it, isWalletReady = false, isAmbient = false))
        }
    }

    @Test
    fun `ambient mode hides the hint in every nfc state`() {
        NfcState.entries.forEach {
            assertEquals(NfcHintState.Hidden, nfcHintState(it, isWalletReady = true, isAmbient = true))
        }
    }
}
