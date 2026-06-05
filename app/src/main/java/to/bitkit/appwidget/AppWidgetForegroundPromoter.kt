package to.bitkit.appwidget

import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

fun interface AppWidgetForegroundPromoter {
    suspend fun promote(worker: CoroutineWorker, foregroundInfo: ForegroundInfo)
}

@Singleton
class WorkManagerAppWidgetForegroundPromoter @Inject constructor() : AppWidgetForegroundPromoter {
    override suspend fun promote(worker: CoroutineWorker, foregroundInfo: ForegroundInfo) {
        worker.setForeground(foregroundInfo)
    }
}

internal suspend fun promoteWidgetRefreshIfNeeded(
    reason: String,
    activeTypes: List<AppWidgetType>,
    nowMs: Long,
    preferencesStore: AppWidgetPreferencesStore,
    foreground: AppWidgetRefreshForeground,
    foregroundPromoter: AppWidgetForegroundPromoter,
    tag: String,
    worker: CoroutineWorker,
) {
    if (!needsValidatedNetwork(activeTypes)) return
    if (!shouldPromoteWidgetRefreshToForeground(reason, hasRemoteTypes = true)) return
    if (!needsNetworkFetch(activeTypes, nowMs, preferencesStore)) return

    foregroundPromoter.promote(worker, foreground.foregroundInfo())
    Logger.debug("Promoted widget refresh to foreground for '$reason'", context = tag)
}

private fun needsValidatedNetwork(activeTypes: List<AppWidgetType>): Boolean =
    activeTypes.any { it != AppWidgetType.FACTS }

private suspend fun needsNetworkFetch(
    activeTypes: List<AppWidgetType>,
    nowMs: Long,
    preferencesStore: AppWidgetPreferencesStore,
): Boolean =
    activeTypes.any { type ->
        if (type == AppWidgetType.FACTS) return@any false
        val metadata = preferencesStore.getRefreshMetadata(type)
        val hasUncachedPricePeriods = type == AppWidgetType.PRICE &&
            preferencesStore.getUncachedActivePricePeriods().isNotEmpty()
        hasUncachedPricePeriods || AppWidgetRefreshPolicy.shouldRefreshRemote(type, metadata, nowMs)
    }
