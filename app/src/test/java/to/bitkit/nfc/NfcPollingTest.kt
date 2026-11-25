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
        assertTrue(tracker.isAnyCameraUnavailable)
        tracker.onCameraUnavailable("1", present)
        tracker.onCameraAvailable("0")
        assertTrue(tracker.isAnyCameraUnavailable)
        tracker.onCameraAvailable("1")
        assertFalse(tracker.isAnyCameraUnavailable)
    }

    @Test
    fun `same camera reported unavailable twice resumes after one available`() {
        tracker.onCameraUnavailable("0", present)
        tracker.onCameraUnavailable("0", present)
        tracker.onCameraAvailable("0")
        assertFalse(tracker.isAnyCameraUnavailable)
    }

    @Test
    fun `available for a camera never seen keeps the stop of an open one`() {
        tracker.onCameraUnavailable("0", present)
        tracker.onCameraAvailable("1")
        assertTrue(tracker.isAnyCameraUnavailable)
    }

    @Test
    fun `camera missing from the camera list counts as removed, not opened`() {
        tracker.onCameraUnavailable("9", present)
        assertFalse(tracker.isAnyCameraUnavailable)
        tracker.onCameraUnavailable("0", present)
        tracker.onCameraUnavailable("0", present - "0")
        assertFalse(tracker.isAnyCameraUnavailable)
    }

    @Test
    fun `cameras gone from the camera list on resume resume polling`() {
        tracker.onCameraUnavailable("0", present)
        tracker.retainPresent(setOf("1"))
        assertFalse(tracker.isAnyCameraUnavailable)
    }

    @Test
    fun `retain keeps the stop while an open camera is still present`() {
        tracker.onCameraUnavailable("0", present)
        tracker.retainPresent(present)
        assertTrue(tracker.isAnyCameraUnavailable)
    }

    @Test
    fun `stop survives nfc turned off and on with a camera open`() {
        tracker.onCameraUnavailable("0", present)
        assertTrue(tracker.isNfcStopped(isNfcOn = true))
        assertFalse(tracker.isNfcStopped(isNfcOn = false))
        assertTrue(tracker.isNfcStopped(isNfcOn = true))
    }

    @Test
    fun `nfc on with no camera open is not stopped`() {
        assertFalse(tracker.isNfcStopped(isNfcOn = true))
    }

    @Test
    fun `camera counts as present when the camera list cannot be read`() {
        tracker.onCameraUnavailable("0", presentCameras = null)
        assertTrue(tracker.isNfcStopped(isNfcOn = true))
    }
}
