package to.bitkit.ui.screens.scanner

import org.junit.Test
import to.bitkit.R
import to.bitkit.ui.shared.effects.BluetoothState
import to.bitkit.ui.shared.effects.NfcState
import kotlin.test.assertEquals

class NearbyScanSourcesTest {
    private fun sources(isPaused: Boolean) =
        NearbyScanSources(NfcState.ON, BluetoothState.ON, onEnableNfc = {}, onEnableBluetooth = {}, isPaused = isPaused)

    @Test
    fun `status lists the sources that read`() {
        assertEquals(R.string.other__scanner_status_qr_nfc_bluetooth, scannerStatusText(sources(isPaused = false)))
    }

    @Test
    fun `status lists only the camera while nearby payments pause`() {
        assertEquals(R.string.other__scanner_status_qr, scannerStatusText(sources(isPaused = true)))
    }
}
