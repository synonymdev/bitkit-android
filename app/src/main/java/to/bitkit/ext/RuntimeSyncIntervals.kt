package to.bitkit.ext

import org.lightningdevkit.ldknode.BackgroundSyncConfig
import org.lightningdevkit.ldknode.RuntimeSyncIntervals

fun RuntimeSyncIntervals.toBackgroundSyncConfig(): BackgroundSyncConfig = BackgroundSyncConfig(
    onchainWalletSyncIntervalSecs = onchainWalletSyncIntervalSecs,
    lightningWalletSyncIntervalSecs = lightningWalletSyncIntervalSecs,
    feeRateCacheUpdateIntervalSecs = feeRateCacheUpdateIntervalSecs,
)
