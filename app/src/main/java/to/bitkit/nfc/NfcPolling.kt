package to.bitkit.nfc

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
 * toggle hides the stop from apps until it is sent again, so this errs toward reporting NFC as not reading.
 *
 * Not thread safe: feed it from the main thread only.
 */
internal class CameraNfcStopTracker {
    private val unavailableCameras = mutableSetOf<String>()

    val isStopped get() = unavailableCameras.isNotEmpty()

    /** [presentCameras] is `CameraManager.cameraIdList`; a camera missing from it was removed, not opened. */
    fun onCameraUnavailable(cameraId: String, presentCameras: Set<String>) {
        if (cameraId in presentCameras) unavailableCameras.add(cameraId) else unavailableCameras.remove(cameraId)
    }

    fun onCameraAvailable(cameraId: String) = run { unavailableCameras.remove(cameraId) }

    /** Drops cameras that left without an available callback, e.g. after the camera service restarted. */
    fun retainPresent(presentCameras: Set<String>) = run { unavailableCameras.retainAll(presentCameras) }
}
