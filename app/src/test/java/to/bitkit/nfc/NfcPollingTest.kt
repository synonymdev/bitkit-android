package to.bitkit.nfc

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NfcPollingTest {
    @Test
    fun `positive property value means the camera stops polling`() {
        assertTrue(cameraStopsNfcPolling("1"))
        assertTrue(cameraStopsNfcPolling("1\n"))
    }

    @Test
    fun `missing, zero or non-numeric property value means polling stays on`() {
        assertFalse(cameraStopsNfcPolling(null))
        assertFalse(cameraStopsNfcPolling(""))
        assertFalse(cameraStopsNfcPolling("\n"))
        assertFalse(cameraStopsNfcPolling("0"))
        // CameraServiceProxy reads the property with SystemProperties.getInt, which falls back to 0 for "true".
        assertFalse(cameraStopsNfcPolling("true"))
    }
}
