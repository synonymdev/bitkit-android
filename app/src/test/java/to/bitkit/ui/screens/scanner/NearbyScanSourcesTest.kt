package to.bitkit.ui.screens.scanner

import org.junit.Test
import to.bitkit.R
import to.bitkit.ui.shared.effects.BluetoothState
import to.bitkit.ui.shared.effects.NfcState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NearbyScanSourcesTest {
    private fun sources(
        isPaused: Boolean = false,
        nfcState: NfcState = NfcState.ON,
        bluetoothState: BluetoothState = BluetoothState.ON,
        isNfcStoppedByCamera: Boolean = false,
    ) = NearbyScanSources(
        nfcState = nfcState,
        bluetoothState = bluetoothState,
        onEnableNfc = {},
        onEnableBluetooth = {},
        isPaused = isPaused,
        isNfcStoppedByCamera = isNfcStoppedByCamera,
    )

    @Test
    fun `status lists the sources that read`() {
        assertEquals(R.string.other__scanner_status_qr_nfc_bluetooth, scannerStatusText(sources(isPaused = false)))
    }

    @Test
    fun `status lists only the camera while nearby payments pause`() {
        assertEquals(R.string.other__scanner_status_qr, scannerStatusText(sources(isPaused = true)))
    }

    @Test
    fun `status drops nfc while the camera stops polling and keeps bluetooth`() {
        val sources = sources(isNfcStoppedByCamera = true)
        assertFalse(sources.isNfcActive)
        assertTrue(sources.isBluetoothActive)
        assertFalse(sources.canEnableNfc)
        assertEquals(R.string.other__scanner_status_qr_bluetooth, scannerStatusText(sources))
    }

    @Test
    fun `status drops nfc while the camera stops polling without bluetooth`() {
        val sources = sources(isNfcStoppedByCamera = true, bluetoothState = BluetoothState.OFF)
        assertEquals(R.string.other__scanner_status_qr, scannerStatusText(sources))
    }

    @Test
    fun `status drops nfc and offers to enable it while the adapter is off`() {
        val sources = sources(nfcState = NfcState.OFF)
        assertFalse(sources.isNfcActive)
        assertTrue(sources.canEnableNfc)
        assertEquals(R.string.other__scanner_status_qr_bluetooth, scannerStatusText(sources))
    }

    @Test
    fun `status lists nfc again once the camera stop ends`() {
        assertEquals(
            R.string.other__scanner_status_qr_nfc_bluetooth,
            scannerStatusText(sources(isNfcStoppedByCamera = false)),
        )
    }
}
