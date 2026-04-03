package to.bitkit.appwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.blocks.BlocksGlanceReceiver
import to.bitkit.appwidget.ui.blocks.BlocksGlanceWidget
import to.bitkit.appwidget.ui.facts.FactsGlanceReceiver
import to.bitkit.appwidget.ui.facts.FactsGlanceWidget
import to.bitkit.appwidget.ui.headlines.HeadlinesGlanceReceiver
import to.bitkit.appwidget.ui.headlines.HeadlinesGlanceWidget
import to.bitkit.appwidget.ui.price.PriceGlanceReceiver
import to.bitkit.appwidget.ui.price.PriceGlanceWidget
import to.bitkit.appwidget.ui.weather.WeatherGlanceReceiver
import to.bitkit.appwidget.ui.weather.WeatherGlanceWidget
import to.bitkit.utils.Logger
import java.util.concurrent.TimeUnit

@HiltWorker
class AppWidgetRefreshWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dataRepository: AppWidgetDataRepository,
    private val preferencesStore: AppWidgetPreferencesStore,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val activeTypes = preferencesStore.getActiveWidgetTypes()
        if (activeTypes.isEmpty()) return Result.success()

        Logger.debug("Refreshing data for widget types: $activeTypes", context = TAG)

        for (type in activeTypes) {
            when (type) {
                AppWidgetType.BLOCKS -> dataRepository.fetchBlockData()
                    .onSuccess {
                        preferencesStore.cacheBlockData(it)
                        BlocksGlanceWidget().updateAll(appContext)
                    }
                    .onFailure { Logger.warn("Failed to refresh blocks", e = it, context = TAG) }

                AppWidgetType.PRICE -> dataRepository.fetchPriceData()
                    .onSuccess {
                        preferencesStore.cachePriceData(it)
                        PriceGlanceWidget().updateAll(appContext)
                    }
                    .onFailure { Logger.warn("Failed to refresh price", e = it, context = TAG) }

                AppWidgetType.WEATHER -> dataRepository.fetchWeatherData()
                    .onSuccess {
                        preferencesStore.cacheWeatherData(it)
                        WeatherGlanceWidget().updateAll(appContext)
                    }
                    .onFailure { Logger.warn("Failed to refresh weather", e = it, context = TAG) }

                AppWidgetType.HEADLINES -> dataRepository.fetchHeadlines()
                    .onSuccess {
                        preferencesStore.cacheArticles(it)
                        HeadlinesGlanceWidget().updateAll(appContext)
                    }
                    .onFailure { Logger.warn("Failed to refresh headlines", e = it, context = TAG) }

                AppWidgetType.FACTS -> dataRepository.fetchFacts()
                    .onSuccess {
                        preferencesStore.cacheFacts(it)
                        FactsGlanceWidget().updateAll(appContext)
                    }
                    .onFailure { Logger.warn("Failed to refresh facts", e = it, context = TAG) }
            }
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "AppWidgetRefreshWorker"
        private const val WORK_NAME = "appwidget_refresh"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AppWidgetRefreshWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
            ).setConstraints(constraints).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun enqueueImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AppWidgetRefreshWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }

        fun cancelIfNoWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val receivers = listOf(
                BlocksGlanceReceiver::class.java,
                PriceGlanceReceiver::class.java,
                WeatherGlanceReceiver::class.java,
                HeadlinesGlanceReceiver::class.java,
                FactsGlanceReceiver::class.java,
            )
            val hasAny = receivers.any { receiver ->
                manager.getAppWidgetIds(ComponentName(context, receiver)).isNotEmpty()
            }
            if (!hasAny) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
