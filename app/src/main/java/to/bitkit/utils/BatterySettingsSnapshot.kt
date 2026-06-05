package to.bitkit.utils

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import kotlinx.serialization.Serializable
import to.bitkit.ext.powerManager
import to.bitkit.ext.usageStatsManager

@Serializable
data class BatterySettingsSnapshot(
    val isPowerSaveMode: Boolean?,
    val isIgnoringBatteryOptimizations: Boolean?,
    val isDeviceIdleMode: Boolean?,
    val isDeviceLightIdleMode: Boolean?,
    val isLowPowerStandbyEnabled: Boolean?,
    val isExemptFromLowPowerStandby: Boolean?,
    val appStandbyBucket: String?,
) {
    val logSummary: String
        get() = listOf(
            "powerSaveMode='${isPowerSaveMode.logValue()}'",
            "ignoringBatteryOptimizations='${isIgnoringBatteryOptimizations.logValue()}'",
            "deviceIdleMode='${isDeviceIdleMode.logValue()}'",
            "deviceLightIdleMode='${isDeviceLightIdleMode.logValue()}'",
            "lowPowerStandbyEnabled='${isLowPowerStandbyEnabled.logValue()}'",
            "exemptFromLowPowerStandby='${isExemptFromLowPowerStandby.logValue()}'",
            "appStandbyBucket='${appStandbyBucket.logValue()}'",
        ).joinToString()
}

fun Context.batterySettingsSnapshot(): BatterySettingsSnapshot {
    val powerManager = powerManager
    return BatterySettingsSnapshot(
        isPowerSaveMode = runCatching { powerManager.isPowerSaveMode }.getOrNull(),
        isIgnoringBatteryOptimizations = if (VERSION.SDK_INT >= VERSION_CODES.M) {
            runCatching { powerManager.isIgnoringBatteryOptimizations(packageName) }.getOrNull()
        } else {
            null
        },
        isDeviceIdleMode = if (VERSION.SDK_INT >= VERSION_CODES.M) {
            runCatching { powerManager.isDeviceIdleMode }.getOrNull()
        } else {
            null
        },
        isDeviceLightIdleMode = if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            runCatching { powerManager.isDeviceLightIdleMode }.getOrNull()
        } else {
            null
        },
        isLowPowerStandbyEnabled = if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            runCatching { powerManager.isLowPowerStandbyEnabled }.getOrNull()
        } else {
            null
        },
        isExemptFromLowPowerStandby = if (VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { powerManager.isExemptFromLowPowerStandby }.getOrNull()
        } else {
            null
        },
        appStandbyBucket = if (VERSION.SDK_INT >= VERSION_CODES.P) {
            runCatching { usageStatsManager.appStandbyBucket.toAppStandbyBucketName() }.getOrNull()
        } else {
            null
        },
    )
}

fun Context.logBatterySettings(): String = batterySettingsSnapshot().logSummary

private fun Int.toAppStandbyBucketName(): String =
    when (this) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
        else -> "UNKNOWN_$this"
    }

private fun Any?.logValue(): String = this?.toString() ?: "unknown"
