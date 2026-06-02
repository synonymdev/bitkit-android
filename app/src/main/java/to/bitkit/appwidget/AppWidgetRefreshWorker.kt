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
        val activeTypes = activeWidgetTypesFor(reason)
        if (activeTypes.isEmpty()) return Result.success()

        val nowMs = clock.nowMs()
        Logger.debug("Refreshing widget types '$activeTypes' for '$reason'", context = TAG)

        for (type in activeTypes) {
            runCatching { refresh(type, nowMs) }.onFailure {
                if (it is CancellationException) throw it
                Logger.warn("Failed to refresh widget type '$type'", it, context = TAG)
            }
        }

        return Result.success()
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

    private suspend fun refresh(type: AppWidgetType, nowMs: Long) {
        if (type == AppWidgetType.FACTS) {
            refreshFacts()
            return
        }

        val metadata = preferencesStore.getRefreshMetadata(type)
        if (!shouldRefreshRemote(type = type, metadata = metadata, nowMs = nowMs)) {
            Logger.debug("Skipped fresh widget type '$type'", context = TAG)
            appWidgetUpdater.update(type, appContext)
            return
        }

        preferencesStore.markRefreshAttempt(type, nowMs)
        if (refreshRemote(type)) {
            preferencesStore.markRefreshSuccess(type, nowMs)
        }
        appWidgetUpdater.update(type, appContext)
    }

    private suspend fun shouldRefreshRemote(
        type: AppWidgetType,
        metadata: AppWidgetRefreshMetadata,
        nowMs: Long,
    ): Boolean {
        val hasUncachedPricePeriods = type == AppWidgetType.PRICE &&
            preferencesStore.getUncachedActivePricePeriods().isNotEmpty()
        if (hasUncachedPricePeriods) {
            return true
        }

        return AppWidgetRefreshPolicy.shouldRefreshRemote(type, metadata, nowMs)
    }

    private suspend fun refreshRemote(type: AppWidgetType): Boolean = when (type) {
        AppWidgetType.PRICE -> refreshPrice()
        AppWidgetType.HEADLINES -> refreshHeadlines()
        AppWidgetType.BLOCKS -> refreshBlocks()
        AppWidgetType.WEATHER -> refreshWeather()
        AppWidgetType.FACTS -> false
    }

    private suspend fun refreshPrice(): Boolean {
        val periods = preferencesStore.getActivePricePeriods()
        var didSucceed = periods.isNotEmpty()

        periods.forEach { period ->
            dataRepository.fetchPriceData(period)
                .onSuccess { preferencesStore.cachePriceData(period, it) }
                .onFailure {
                    didSucceed = false
                    Logger.warn("Failed to refresh price for '$period'", it, context = TAG)
                }
        }

        return didSucceed
    }

    private suspend fun refreshHeadlines(): Boolean =
        dataRepository.fetchArticles()
            .onSuccess { preferencesStore.cacheArticlesAndRotate(it) }
            .onFailure { Logger.warn("Failed to refresh headlines", it, context = TAG) }
            .isSuccess

    private suspend fun refreshBlocks(): Boolean =
        dataRepository.fetchBlock()
            .onSuccess { preferencesStore.cacheBlock(it) }
            .onFailure { Logger.warn("Failed to refresh block", it, context = TAG) }
            .isSuccess

    private suspend fun refreshFacts() {
        dataRepository.fetchFacts()
            .onSuccess { preferencesStore.cacheFacts(it) }
            .onFailure { Logger.warn("Failed to refresh facts", it, context = TAG) }
        preferencesStore.bumpFactsRotationTick()
        appWidgetUpdater.update(AppWidgetType.FACTS, appContext)
    }

    private suspend fun refreshWeather(): Boolean =
        dataRepository.fetchWeather()
            .onSuccess { preferencesStore.cacheWeather(it) }
            .onFailure { Logger.warn("Failed to refresh weather", it, context = TAG) }
            .isSuccess
}
