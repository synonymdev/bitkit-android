package to.bitkit.flags

import to.bitkit.BuildConfig

object OfflineReceiveFeatureFlags {
    /**
     * True only when the app was compiled with `ldkNodeLocalVersion` against an ldk-node build that carries the
     * offline receive API. CI and release builds compile without it, so the native adapter is absent.
     */
    const val isNativeAvailable = BuildConfig.FEATURE_OFFLINE_RECEIVE_NATIVE

    /** The provider is selected only when it was compiled in and the dev toggle is on. */
    fun isEnabled(localFlagEnabled: Boolean): Boolean = isNativeAvailable && localFlagEnabled
}
