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

class CameraNfcStopTrackerTest {
    private val present = setOf("0", "1", "2")
    private val tracker = CameraNfcStopTracker()

    @Test
    fun `first camera opening stops polling and the last one closing resumes it`() {
        tracker.onCameraUnavailable("0", present)
        assertTrue(tracker.isStopped)
        tracker.onCameraUnavailable("1", present)
        tracker.onCameraAvailable("0")
        assertTrue(tracker.isStopped)
        tracker.onCameraAvailable("1")
        assertFalse(tracker.isStopped)
    }

    @Test
    fun `nfc turned back on while a camera stays open polls until the cameras close and one opens again`() {
        tracker.onCameraUnavailable("0", present)
        tracker.onNfcOff()
        assertFalse(tracker.isStopped)
        tracker.onCameraUnavailable("1", present)
        assertFalse(tracker.isStopped)
        tracker.onCameraAvailable("0")
        tracker.onCameraAvailable("1")
        assertFalse(tracker.isStopped)
        tracker.onCameraUnavailable("0", present)
        assertTrue(tracker.isStopped)
    }

    @Test
    fun `camera opened while nfc is off still stops polling`() {
        tracker.onNfcOff()
        tracker.onCameraUnavailable("0", present)
        assertTrue(tracker.isStopped)
    }

    @Test
    fun `camera missing from the camera list counts as removed, not opened`() {
        tracker.onCameraUnavailable("9", present)
        assertFalse(tracker.isStopped)
        tracker.onCameraUnavailable("0", present)
        tracker.onCameraUnavailable("0", present - "0")
        assertFalse(tracker.isStopped)
    }

    @Test
    fun `cameras gone from the camera list on resume resume polling`() {
        tracker.onCameraUnavailable("0", present)
        tracker.retainPresent(setOf("1"))
        assertFalse(tracker.isStopped)
    }

    @Test
    fun `retain keeps the stop while an open camera is still present`() {
        tracker.onCameraUnavailable("0", present)
        tracker.retainPresent(present)
        assertTrue(tracker.isStopped)
    }
}
