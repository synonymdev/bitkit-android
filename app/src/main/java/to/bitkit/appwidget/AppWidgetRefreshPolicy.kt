package to.bitkit.appwidget

import to.bitkit.appwidget.model.AppWidgetRefreshMetadata
import to.bitkit.appwidget.model.AppWidgetType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

object AppWidgetRefreshPolicy {
    fun shouldRefreshRemote(
        type: AppWidgetType,
        metadata: AppWidgetRefreshMetadata,
        nowMs: Long,
        hasUncachedData: Boolean = false,
        bypassFailedAttemptBackoff: Boolean = false,
    ): Boolean {
        val minRefreshInterval = type.minRefreshInterval ?: return false
        if (!bypassFailedAttemptBackoff && shouldBackOffFailedAttempt(metadata, nowMs, minRefreshInterval)) {
            return false
        }
        if (hasUncachedData) return true
        if (metadata.lastSuccessAtMs <= 0L) return true
        return nowMs - metadata.lastSuccessAtMs >= minRefreshInterval.inWholeMilliseconds
    }

    fun shouldBackOffFailedAttempt(
        metadata: AppWidgetRefreshMetadata,
        nowMs: Long,
        minRefreshInterval: Duration,
    ): Boolean =
        metadata.lastAttemptAtMs > metadata.lastSuccessAtMs &&
            nowMs - metadata.lastAttemptAtMs < minRefreshInterval.inWholeMilliseconds

    val AppWidgetType.minRefreshInterval: Duration?
        get() = when (this) {
            AppWidgetType.PRICE,
            AppWidgetType.BLOCKS -> AppWidgetRefreshScheduler.REFRESH_INTERVAL
            AppWidgetType.WEATHER -> 30.minutes
            AppWidgetType.HEADLINES -> 60.minutes
            AppWidgetType.FACTS -> null
        }
}
