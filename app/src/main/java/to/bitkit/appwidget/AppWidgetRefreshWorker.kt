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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@HiltWorker
class AppWidgetRefreshWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dataRepository: AppWidgetDataRepository,
    private val preferencesStore: AppWidgetPreferencesStore,
    private val appWidgetUpdater: AppWidgetUpdater,
    private val validatedNetworkGate: ValidatedNetworkGate,
    private val appWidgetRefreshScheduler: AppWidgetRefreshScheduler,
    private val appWidgetRefreshForeground: AppWidgetRefreshForeground,
    private val foregroundPromoter: AppWidgetForegroundPromoter,
    private val clock: Clock,
) : CoroutineWorker(appContext, workerParams) {

    private companion object {
        private const val TAG = "AppWidgetRefreshWorker"
        private val NETWORK_READY_TIMEOUT = 15.seconds
    }

    override suspend fun doWork(): Result {
        val reason = inputData.getString(AppWidgetRefreshScheduler.WORK_INPUT_REASON) ?: "unknown"
        val activeTypes = activeWidgetTypesFor(reason)
        if (activeTypes.isEmpty()) return Result.success()

        val nowMs = clock.nowMs()
        promoteWidgetRefreshIfNeeded(
            reason = reason,
            activeTypes = activeTypes,
            nowMs = nowMs,
            preferencesStore = preferencesStore,
            foreground = appWidgetRefreshForeground,
            foregroundPromoter = foregroundPromoter,
            tag = TAG,
            worker = this,
        )

        if (needsValidatedNetwork(activeTypes)) {
            if (!validatedNetworkGate.awaitReady(NETWORK_READY_TIMEOUT)) {
                Logger.debug("Network readiness probe timed out for '$reason', attempting refresh anyway", context = TAG)
            }
        }
        Logger.debug("Refreshing widget types '$activeTypes' for '$reason'", context = TAG)

        var hadConnectivityFailure = false
        for (type in activeTypes) {
            val result = runCatching { refresh(type, nowMs) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    Logger.warn("Failed to refresh widget type '$type'", it, context = TAG)
                    it.toRefreshResult()
                }
            if (result == RefreshResult.ConnectivityFailure) {
                hadConnectivityFailure = true
            }
        }

        if (hadConnectivityFailure) {
            appWidgetRefreshScheduler.scheduleSoonCatchUp()
            return Result.retry()
        }
        return Result.success()
    }

    private fun needsValidatedNetwork(activeTypes: List<AppWidgetType>): Boolean =
        activeTypes.any { it != AppWidgetType.FACTS }

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

    private suspend fun refresh(type: AppWidgetType, nowMs: Long): RefreshResult {
        if (type == AppWidgetType.FACTS) {
            refreshFacts()
            return RefreshResult.Success
        }

        val metadata = preferencesStore.getRefreshMetadata(type)
        if (!shouldRefreshRemote(type = type, metadata = metadata, nowMs = nowMs)) {
            Logger.debug("Skipped fresh widget type '$type'", context = TAG)
            appWidgetUpdater.update(type, appContext)
            return RefreshResult.Success
        }

        preferencesStore.markRefreshAttempt(type, nowMs)
        val refreshResult = refreshRemote(type)
        if (refreshResult == RefreshResult.Success) {
            preferencesStore.markRefreshSuccess(type, nowMs)
        }
        appWidgetUpdater.update(type, appContext)
        return refreshResult
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

    private suspend fun refreshRemote(type: AppWidgetType): RefreshResult = when (type) {
        AppWidgetType.PRICE -> refreshPrice()
        AppWidgetType.HEADLINES -> refreshHeadlines()
        AppWidgetType.BLOCKS -> refreshBlocks()
        AppWidgetType.WEATHER -> refreshWeather()
        AppWidgetType.FACTS -> RefreshResult.Failure
    }

    private suspend fun refreshPrice(): RefreshResult {
        val periods = preferencesStore.getActivePricePeriods()
        if (periods.isEmpty()) return RefreshResult.Failure

        var refreshResult: RefreshResult = RefreshResult.Success

        for (period in periods) {
            dataRepository.fetchPriceData(period)
                .onSuccess { preferencesStore.cachePriceData(period, it) }
                .onFailure {
                    val periodResult = it.toRefreshResult()
                    refreshResult = refreshResult.merge(periodResult)
                    Logger.warn("Failed to refresh price for '$period'", it, context = TAG)
                    if (periodResult == RefreshResult.ConnectivityFailure) return refreshResult
                }
        }

        return refreshResult
    }

    private suspend fun refreshHeadlines(): RefreshResult =
        dataRepository.fetchArticles()
            .fold(
                onSuccess = {
                    preferencesStore.cacheArticlesAndRotate(it)
                    RefreshResult.Success
                },
                onFailure = {
                    Logger.warn("Failed to refresh headlines", it, context = TAG)
                    it.toRefreshResult()
                },
            )

    private suspend fun refreshBlocks(): RefreshResult =
        dataRepository.fetchBlock()
            .fold(
                onSuccess = {
                    preferencesStore.cacheBlock(it)
                    RefreshResult.Success
                },
                onFailure = {
                    Logger.warn("Failed to refresh block", it, context = TAG)
                    it.toRefreshResult()
                },
            )

    private suspend fun refreshFacts() {
        dataRepository.fetchFacts()
            .onSuccess { preferencesStore.cacheFacts(it) }
            .onFailure { Logger.warn("Failed to refresh facts", it, context = TAG) }
        preferencesStore.bumpFactsRotationTick()
        appWidgetUpdater.update(AppWidgetType.FACTS, appContext)
    }

    private suspend fun refreshWeather(): RefreshResult =
        dataRepository.fetchWeather()
            .fold(
                onSuccess = {
                    preferencesStore.cacheWeather(it)
                    RefreshResult.Success
                },
                onFailure = {
                    Logger.warn("Failed to refresh weather", it, context = TAG)
                    it.toRefreshResult()
                },
            )
}

private sealed interface RefreshResult {
    object Success : RefreshResult
    object ConnectivityFailure : RefreshResult
    object Failure : RefreshResult
}

private fun Throwable.toRefreshResult(): RefreshResult =
    if (isConnectivityFailure()) RefreshResult.ConnectivityFailure else RefreshResult.Failure

private fun RefreshResult.merge(other: RefreshResult): RefreshResult = when (this) {
    RefreshResult.ConnectivityFailure -> this
    RefreshResult.Failure -> if (other == RefreshResult.ConnectivityFailure) other else this
    RefreshResult.Success -> other
}
