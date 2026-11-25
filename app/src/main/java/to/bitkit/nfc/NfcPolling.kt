package to.bitkit.nfc

import to.bitkit.utils.Logger
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NfcPolling"

/**
 * When this read-only property is above 0, Android's CameraServiceProxy switches NFC polling off while any camera
 * is open and back on when the last one closes. Apps cannot turn polling back on meanwhile: NfcService drops their
 * reader mode requests until the camera closes.
 */
internal const val NOTIFY_NFC_PROPERTY = "ro.camera.notify_nfc"

/** Parses [NOTIFY_NFC_PROPERTY] the way CameraServiceProxy does: `SystemProperties.getInt(name, 0) > 0`. */
internal fun cameraStopsNfcPolling(propertyValue: String?): Boolean =
    (propertyValue?.trim()?.toIntOrNull() ?: 0) > 0

/** How long reading a system property may take before it counts as unset. */
private val GETPROP_TIMEOUT: Duration = 2.seconds

/** Whether this phone stops NFC polling while a camera is open. Read once per process. */
internal val deviceCameraStopsNfcPolling: Boolean by lazy {
    runCatching { readSystemProperty(NOTIFY_NFC_PROPERTY) }.onFailure {
        Logger.warn("Failed to read '$NOTIFY_NFC_PROPERTY'", it, context = TAG)
    }.getOrNull().let(::cameraStopsNfcPolling).also {
        Logger.info("Read camera stops NFC polling '$it'", context = TAG)
    }
}

private fun readSystemProperty(name: String): String {
    val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
    return runCatching {
        val exited = process.waitFor(GETPROP_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        check(exited) { "getprop timed out" }
        process.inputStream.bufferedReader().use { it.readText() }
    }.also { process.destroy() }.getOrThrow()
}

/**
 * Follows NFC polling the way CameraServiceProxy and NfcService switch it on phones where [NOTIFY_NFC_PROPERTY]
 * is set. Polling stops when the first camera becomes unavailable and resumes when the last one is available again.
 * Turning NFC off drops the stop, and CameraServiceProxy only notifies on those first/last changes, so NFC turned
 * back on while a camera stays open polls until the cameras all close and one opens again.
 *
 * Not thread safe: feed it from the main thread only.
 */
internal class CameraNfcStopTracker {
    private val unavailableCameras = mutableSetOf<String>()

    var isStopped = false
        private set

    /** [presentCameras] is `CameraManager.cameraIdList`; a camera missing from it was removed, not opened. */
    fun onCameraUnavailable(cameraId: String, presentCameras: Set<String>) {
        if (cameraId !in presentCameras) return onCameraAvailable(cameraId)
        val wasEmpty = unavailableCameras.isEmpty()
        if (unavailableCameras.add(cameraId) && wasEmpty) isStopped = true
    }

    fun onCameraAvailable(cameraId: String) {
        if (unavailableCameras.remove(cameraId) && unavailableCameras.isEmpty()) isStopped = false
    }

    /** Drops cameras that left without an available callback, e.g. after the camera service restarted. */
    fun retainPresent(presentCameras: Set<String>) {
        if (!unavailableCameras.retainAll(presentCameras)) return
        if (unavailableCameras.isEmpty()) isStopped = false
    }

    fun onNfcOff() = run { isStopped = false }
}
