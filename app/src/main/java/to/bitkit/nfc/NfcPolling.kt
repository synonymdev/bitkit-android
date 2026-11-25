package to.bitkit.nfc

import to.bitkit.utils.Logger

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

/** Whether this phone stops NFC polling while a camera is open. Read once per process. */
internal val deviceCameraStopsNfcPolling: Boolean by lazy {
    runCatching {
        val process = ProcessBuilder("getprop", NOTIFY_NFC_PROPERTY).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        value
    }.onFailure {
        Logger.warn("Failed to read '$NOTIFY_NFC_PROPERTY'", it, context = TAG)
    }.getOrNull().let(::cameraStopsNfcPolling).also {
        Logger.info("Read camera stops NFC polling '$it'", context = TAG)
    }
}
