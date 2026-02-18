package to.bitkit.services

import to.bitkit.utils.Logger

/**
 * Helper object to initialize btleplug (droidplug) on Android.
 * This must be called before using any Bluetooth functionality with Trezor devices.
 *
 * The initialization is performed via JNI to the Rust bitkitcore library,
 * which in turn initializes btleplug's Android Bluetooth adapter.
 */
object BluetoothInit {
    private const val TAG = "BluetoothInit"
    private var initialized = false
    private var initResult = false

    init {
        // We must load the native library before calling JNI functions.
        // UniFFI loads it lazily, but we need it now for Bluetooth init.
        try {
            System.loadLibrary("bitkitcore")
            Logger.info("Loaded bitkitcore native library", context = TAG)
        } catch (e: UnsatisfiedLinkError) {
            Logger.error("Failed to load bitkitcore native library", e, context = TAG)
        }
    }

    /**
     * Native JNI function to initialize btleplug on Android.
     * This function name must match the Rust JNI function name pattern:
     * Java_to_bitkit_services_BluetoothInit_nativeInit
     */
    private external fun nativeInit(): Boolean

    /**
     * Ensures Bluetooth is initialized for btleplug usage.
     * This is idempotent - subsequent calls after the first will return
     * the cached result without re-initializing.
     *
     * @return true if initialization succeeded, false otherwise
     */
    @Synchronized
    fun ensureInitialized(): Boolean {
        if (!initialized) {
            try {
                initResult = nativeInit()
                initialized = true
                if (initResult) {
                    Logger.info("Bluetooth (btleplug) initialized successfully", context = TAG)
                } else {
                    Logger.error("Bluetooth (btleplug) initialization returned false", context = TAG)
                }
            } catch (e: UnsatisfiedLinkError) {
                Logger.error("Failed to initialize Bluetooth - native method not found", e, context = TAG)
                initialized = true
                initResult = false
            } catch (e: Exception) {
                Logger.error("Failed to initialize Bluetooth", e, context = TAG)
                initialized = true
                initResult = false
            }
        }
        return initResult
    }

    /**
     * Returns whether Bluetooth has been successfully initialized.
     */
    fun isInitialized(): Boolean = initialized && initResult
}
