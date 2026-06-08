package to.bitkit.flags

import to.bitkit.BuildConfig

object PaykitFeatureFlags {
    const val isUiAvailable = !BuildConfig.FEATURE_PAYKIT_UI_DISABLED

    fun isUiEnabled(localFlagEnabled: Boolean): Boolean {
        return isUiAvailable && localFlagEnabled
    }
}
