package to.bitkit.appwidget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import to.bitkit.appwidget.model.AppWidgetRefreshMetadata
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.ext.nowMs
import to.bitkit.utils.Logger
import to.bitkit.utils.isConnectivityFailure
import to.bitkit.utils.logBatterySettings
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@HiltWorker
class AppWidgetRefreshWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dataRepository: AppWidgetDataRepository,
    private val preferencesStore: AppWidgetPreferencesStore,
    private val appWidgetUpdater: AppWidgetUpdater,
    private val clock: Clock,
) : CoroutineWorker(appContext, workerParams) {

    private companion object {
        private const val TAG = "AppWidgetRefreshWorker"
    }

    override suspend fun doWork(): Result {
        val reason = inputData.getString(AppWidgetRefreshScheduler.WORK_INPUT_REASON) ?: "unknown"
        val batterySettings = appContext.logBatterySettings()
        val activeTypes = activeWidgetTypesFor(reason)
        if (activeTypes.isEmpty()) {
            Logger.debug(
                "Skipped widget refresh for '$reason' because no active types matched ($batterySettings)",
                context = TAG,
            )
            return Result.success()
        }

        val nowMs = clock.nowMs()
        Logger.debug("Refreshing widget types '$activeTypes' for '$reason' ($batterySettings)", context = TAG)

        var shouldRetry = false
        for (type in activeTypes) {
            val result = runCatching { refresh(type, reason, nowMs, batterySettings) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    Logger.warn(
                        "Failed to refresh widget type '$type' ($batterySettings)",
                        it,
                        context = TAG,
                    )
                    it.toRefreshResult()
                }
            if (result == RefreshResult.ConnectivityFailure) {
                shouldRetry = true
                Logger.debug(
                    "Queued widget refresh retry after connectivity failure for '$type' ($batterySettings)",
                    context = TAG,
                )
            }
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    private suspend fun activeWidgetTypesFor(reason: String): List<AppWidgetType> {
        val activeTypes = preferencesStore.getActiveWidgetTypes()
        return AppWidgetType.entries.filter {
            it in activeTypes && shouldRefreshForReason(type = it, reason = reason)
        }
    }

    private fun shouldRefreshForReason(type: AppWidgetType, reason: String): Boolean {
        val isFactsLocalRefresh = reason == AppWidgetRefreshReason.FACTS_LOCAL_REFRESH.name
        return if (isFactsLocalRefresh) type == AppWidgetType.FACTS else type != AppWidgetType.FACTS
    }

    private suspend fun refresh(
        type: AppWidgetType,
        reason: String,
        nowMs: Long,
        batterySettings: String,
    ): RefreshResult {
        if (type == AppWidgetType.FACTS) {
            refreshFacts(batterySettings)
            return RefreshResult.Success
        }

        val metadata = preferencesStore.getRefreshMetadata(type)
        if (!shouldRefreshRemote(type = type, reason = reason, metadata = metadata, nowMs = nowMs)) {
            Logger.debug("Skipped widget type '$type' because refresh is not due ($batterySettings)", context = TAG)
            appWidgetUpdater.update(type, appContext)
            return RefreshResult.Success
        }

        preferencesStore.markRefreshAttempt(type, nowMs)
        val refreshResult = refreshRemote(type, batterySettings)
        if (refreshResult == RefreshResult.Success) {
            preferencesStore.markRefreshSuccess(type, nowMs)
        }
        appWidgetUpdater.update(type, appContext)
        return refreshResult
    }

    private suspend fun shouldRefreshRemote(
        type: AppWidgetType,
        reason: String,
        metadata: AppWidgetRefreshMetadata,
        nowMs: Long,
    ): Boolean {
        val hasUncachedPricePeriods = type == AppWidgetType.PRICE &&
            preferencesStore.getUncachedActivePricePeriods().isNotEmpty()

        return AppWidgetRefreshPolicy.shouldRefreshRemote(
            type = type,
            metadata = metadata,
            nowMs = nowMs,
            hasUncachedData = hasUncachedPricePeriods,
            bypassFailedAttemptBackoff = shouldBypassFailedAttemptBackoff(reason),
        )
    }

    private fun shouldBypassFailedAttemptBackoff(reason: String): Boolean =
        runAttemptCount > 0 ||
            reason == AppWidgetRefreshReason.APP_START.name ||
            reason == AppWidgetRefreshReason.APP_FOREGROUND.name

    private suspend fun refreshRemote(
        type: AppWidgetType,
        batterySettings: String,
    ): RefreshResult = when (type) {
        AppWidgetType.PRICE -> refreshPrice(batterySettings)
        AppWidgetType.HEADLINES -> refreshHeadlines(batterySettings)
        AppWidgetType.BLOCKS -> refreshBlocks(batterySettings)
        AppWidgetType.WEATHER -> refreshWeather(batterySettings)
        AppWidgetType.FACTS -> RefreshResult.Failure
    }

    private suspend fun refreshPrice(batterySettings: String): RefreshResult {
        val periods = preferencesStore.getActivePricePeriods()
        if (periods.isEmpty()) return RefreshResult.Failure

        var refreshResult: RefreshResult = RefreshResult.Success

        for (period in periods) {
            dataRepository.fetchPriceData(period)
                .onSuccess { preferencesStore.cachePriceData(period, it) }
                .onFailure {
                    val periodResult = it.toRefreshResult()
                    refreshResult = refreshResult.merge(periodResult)
                    Logger.warn(
                        "Failed to refresh price for '$period' ($batterySettings)",
                        it,
                        context = TAG,
                    )
                    if (periodResult == RefreshResult.ConnectivityFailure) return refreshResult
                }
        }

        return refreshResult
    }

    private suspend fun refreshHeadlines(batterySettings: String): RefreshResult =
        dataRepository.fetchArticles()
            .fold(
                onSuccess = {
                    preferencesStore.cacheArticlesAndRotate(it)
                    RefreshResult.Success
                },
                onFailure = {
                    val result = it.toRefreshResult()
                    Logger.warn("Failed to refresh headlines ($batterySettings)", it, context = TAG)
                    result
                },
            )

    private suspend fun refreshBlocks(batterySettings: String): RefreshResult =
        dataRepository.fetchBlock()
            .fold(
                onSuccess = {
                    preferencesStore.cacheBlock(it)
                    RefreshResult.Success
                },
                onFailure = {
                    val result = it.toRefreshResult()
                    Logger.warn("Failed to refresh block ($batterySettings)", it, context = TAG)
                    result
                },
            )

    private suspend fun refreshFacts(batterySettings: String) {
        dataRepository.fetchFacts()
            .onSuccess { preferencesStore.cacheFacts(it) }
            .onFailure {
                it.throwIfCancellation()
                Logger.warn("Failed to refresh facts ($batterySettings)", it, context = TAG)
            }
        preferencesStore.bumpFactsRotationTick()
        appWidgetUpdater.update(AppWidgetType.FACTS, appContext)
    }

    private suspend fun refreshWeather(batterySettings: String): RefreshResult =
        dataRepository.fetchWeather()
            .fold(
                onSuccess = {
                    preferencesStore.cacheWeather(it)
                    RefreshResult.Success
                },
                onFailure = {
                    val result = it.toRefreshResult()
                    Logger.warn("Failed to refresh weather ($batterySettings)", it, context = TAG)
                    result
                },
            )
}

private sealed interface RefreshResult {
    object Success : RefreshResult
    object ConnectivityFailure : RefreshResult
    object Failure : RefreshResult
}

private fun Throwable.toRefreshResult(): RefreshResult {
    throwIfCancellation()
    return if (isConnectivityFailure()) RefreshResult.ConnectivityFailure else RefreshResult.Failure
}

private fun Throwable.throwIfCancellation() {
    if (this is CancellationException) throw this
}

private fun RefreshResult.merge(other: RefreshResult): RefreshResult = when (this) {
    RefreshResult.ConnectivityFailure -> this
    RefreshResult.Failure -> if (other == RefreshResult.ConnectivityFailure) other else this
    RefreshResult.Success -> other
}
