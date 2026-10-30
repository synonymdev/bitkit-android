package to.bitkit.flags

import to.bitkit.BuildConfig

object PaykitFeatureFlags {
    /** Allowances follow this unless dev mode is on and the Dev Settings switch set them on a device. */
    const val areAllowancesEnabledByDefault = true

    /** A dev flag: the Dev Settings [override] counts only while dev mode is on. */
    fun areAllowancesEnabled(
        isDevModeEnabled: Boolean,
        override: Boolean?,
        default: Boolean = areAllowancesEnabledByDefault,
    ): Boolean = if (isDevModeEnabled) override ?: default else default

    const val isUiAvailable = !BuildConfig.FEATURE_PAYKIT_UI_DISABLED

    fun isUiEnabled(localFlagEnabled: Boolean): Boolean {
        return isUiAvailable && localFlagEnabled
    }
}
