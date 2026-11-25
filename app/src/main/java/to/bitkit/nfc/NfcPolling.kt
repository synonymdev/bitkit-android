package to.bitkit.nfc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import to.bitkit.utils.Logger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NfcPolling"

/**
 * When this read-only property is above 0, Android's CameraServiceProxy switches NFC polling off while any camera
 * is active and back on when none is. Apps cannot turn polling back on meanwhile: NfcService drops their reader
 * mode requests while that stop is in place.
 */
internal const val NOTIFY_NFC_PROPERTY = "ro.camera.notify_nfc"

/** Parses [NOTIFY_NFC_PROPERTY] the way CameraServiceProxy does: `SystemProperties.getInt(name, 0) > 0`. */
internal fun cameraStopsNfcPolling(propertyValue: String?): Boolean =
    (propertyValue?.trim()?.toIntOrNull() ?: 0) > 0

/** How long reading a system property may take before the read counts as failed. */
private val GETPROP_TIMEOUT: Duration = 2.seconds

private val cachedCameraStopsNfcPolling = AtomicReference<Boolean?>(null)

/** The cached [deviceCameraStopsNfcPolling] result, without reading the property; null until a read succeeds. */
internal fun peekCameraStopsNfcPolling(): Boolean? = cachedCameraStopsNfcPolling.get()

/**
 * Whether this phone stops NFC polling while a camera is open. Only a successful read is cached; a failed one
 * returns false and is retried on the next call. Blocks, so call it off the main thread.
 */
internal fun deviceCameraStopsNfcPolling(): Boolean {
    cachedCameraStopsNfcPolling.get()?.let { return it }
    return runCatching { readSystemProperty(NOTIFY_NFC_PROPERTY) }
        .onFailure { Logger.warn("Failed to read '$NOTIFY_NFC_PROPERTY'", it, context = TAG) }
        .map(::cameraStopsNfcPolling)
        .onSuccess {
            cachedCameraStopsNfcPolling.set(it)
            Logger.info("Read camera stops NFC polling '$it'", context = TAG)
        }
        .getOrDefault(false)
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
 * Treats NFC polling as stopped while any present camera is unavailable, on phones where [NOTIFY_NFC_PROPERTY] is
 * set. CameraServiceProxy stops polling when a camera becomes active and resumes it when none is, and an NFC
 * toggle hides the stop from apps until it is sent again, so this errs toward reporting NFC as not reading. The
 * camera state is kept across NFC toggles, and Compose observes it.
 *
 * Not thread safe: feed it from the main thread only.
 */
internal class CameraNfcStopTracker {
    private var unavailableCameras by mutableStateOf(emptySet<String>())

    val isAnyCameraUnavailable get() = unavailableCameras.isNotEmpty()

    fun isNfcStopped(isNfcOn: Boolean) = isNfcOn && isAnyCameraUnavailable

    /**
     * [presentCameras] is `CameraManager.cameraIdList`, or null when it could not be read. A camera missing from it
     * was removed, not opened; without the list the camera counts as present.
     */
    fun onCameraUnavailable(cameraId: String, presentCameras: Set<String>?) {
        unavailableCameras = if (presentCameras == null || cameraId in presentCameras) {
            unavailableCameras + cameraId
        } else {
            unavailableCameras - cameraId
        }
    }

    fun onCameraAvailable(cameraId: String) = run { unavailableCameras = unavailableCameras - cameraId }

    /** Drops cameras that left without an available callback, e.g. after the camera service restarted. */
    fun retainPresent(presentCameras: Set<String>) = run {
        unavailableCameras = unavailableCameras intersect presentCameras
    }

    fun clear() = run { unavailableCameras = emptySet() }
}
