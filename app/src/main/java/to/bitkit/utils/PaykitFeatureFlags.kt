package to.bitkit.utils

import to.bitkit.BuildConfig
import to.bitkit.data.SettingsData

object PaykitFeatureFlags {
    const val isUiAvailable = !BuildConfig.PAYKIT_UI_DISABLED

    fun isUiEnabled(settings: SettingsData): Boolean {
        return isUiAvailable && settings.isPaykitEnabled
    }
}
