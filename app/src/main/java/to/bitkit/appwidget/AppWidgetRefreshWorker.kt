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
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.price.PriceGlanceReceiver
import to.bitkit.appwidget.ui.price.PriceGlanceWidget
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
                AppWidgetType.PRICE -> {
                    val periods = preferencesStore.getActivePricePeriods()
                    periods.forEach { period ->
                        dataRepository.fetchPriceData(period)
                            .onSuccess { preferencesStore.cachePriceData(period, it) }
                            .onFailure {
                                Logger.warn("Failed to refresh price for '$period'", it, context = TAG)
                            }
                    }
                    PriceGlanceWidget().updateAll(appContext)
                }
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

        fun cancelIfNoWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val hasAny = manager.getAppWidgetIds(
                ComponentName(context, PriceGlanceReceiver::class.java),
            ).isNotEmpty()
            if (!hasAny) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
