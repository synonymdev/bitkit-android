package to.bitkit.appwidget

import to.bitkit.appwidget.model.AppWidgetRefreshMetadata
import to.bitkit.appwidget.model.AppWidgetType

object AppWidgetRefreshPolicy {
    fun shouldRefreshRemote(
        type: AppWidgetType,
        metadata: AppWidgetRefreshMetadata,
        nowMs: Long,
    ): Boolean {
        if (!type.isRemoteBacked()) return false
        if (metadata.lastSuccessAtMs <= 0L) return true
        return nowMs - metadata.lastSuccessAtMs >= AppWidgetRefreshScheduler.REFRESH_INTERVAL.inWholeMilliseconds
    }

    fun AppWidgetType.isRemoteBacked(): Boolean = this != AppWidgetType.FACTS
}
