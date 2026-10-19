package to.bitkit.services

import android.content.Context
import to.bitkit.utils.Logger

/**
 * Initializes the Android networking that the bitkit-core Pubky swap bridge needs: the platform
 * certificate verifier and the JNI context for its peer-to-peer transport. It must succeed before
 * the bridge is configured. A failed attempt is not cached, so the next configuration retries it.
 */
object PubkySwapInit {
    private const val TAG = "PubkySwapInit"
    private var initialized = false

    init {
        // JNA loads the library for UniFFI, which does not register its JNI functions with the JVM.
        runCatching { System.loadLibrary("bitkitcore") }
            .onFailure { Logger.error("Failed to load bitkitcore native library", it, context = TAG) }
    }

    /**
     * Native JNI function exported by bitkitcore for Android builds. Its name must match the Rust
     * symbol `Java_to_bitkit_services_PubkySwapInit_nativeInit`.
     */
    @JvmStatic
    private external fun nativeInit(context: Context): Boolean

    /** Initializes the bridge networking once per process, returning whether it is ready. */
    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        if (initialized) return true
        initialized = runCatching { nativeInit(context.applicationContext) }
            .onFailure { Logger.error("Failed to initialize Pubky swap networking", it, context = TAG) }
            .getOrDefault(false)
        if (initialized) Logger.info("Initialized Pubky swap networking", context = TAG)
        return initialized
    }
}
