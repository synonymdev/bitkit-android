package to.bitkit.appwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
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
import to.bitkit.ext.alarmManager
import to.bitkit.utils.Logger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

@HiltWorker
class AppWidgetRefreshWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dataRepository: AppWidgetDataRepository,
    private val preferencesStore: AppWidgetPreferencesStore,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AppWidgetRefreshWorker"
        private const val WORK_NAME = "appwidget_refresh"
        private const val CATCH_UP_WORK_NAME = "appwidget_refresh_catch_up"
        private const val CATCH_UP_ALARM_REQUEST_CODE = 0
        internal const val CATCH_UP_ALARM_ACTION = "to.bitkit.appwidget.REFRESH_ALARM"
        private val REFRESH_INTERVAL = 15.minutes

        fun enqueue(context: Context) {
            if (!hasActiveWidgets(context)) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AppWidgetRefreshWorker>(REFRESH_INTERVAL.toJavaDuration())
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            scheduleCatchUpAlarm(context)
        }

        fun enqueueCatchUp(context: Context) {
            if (!hasActiveWidgets(context)) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AppWidgetRefreshWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                CATCH_UP_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelIfNoWidgets(context: Context) {
            if (!hasActiveWidgets(context)) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork(CATCH_UP_WORK_NAME)
                cancelCatchUpAlarm(context)
            }
        }

        fun scheduleCatchUpAlarm(context: Context) {
            if (!hasActiveWidgets(context)) {
                cancelCatchUpAlarm(context)
                return
            }

            val triggerAt = SystemClock.elapsedRealtime() + REFRESH_INTERVAL.inWholeMilliseconds
            context.alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                catchUpAlarmPendingIntent(context),
            )
        }

        private fun hasActiveWidgets(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return AppWidgetType.entries.any { type ->
                manager.getAppWidgetIds(ComponentName(context, receiverClassFor(type))).isNotEmpty()
            }
        }

        private fun cancelCatchUpAlarm(context: Context) {
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                CATCH_UP_ALARM_REQUEST_CODE,
                catchUpAlarmIntent(context),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return

            context.alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        private fun catchUpAlarmPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                CATCH_UP_ALARM_REQUEST_CODE,
                catchUpAlarmIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun catchUpAlarmIntent(context: Context): Intent =
            Intent(context, AppWidgetRefreshAlarmReceiver::class.java)
                .setAction(CATCH_UP_ALARM_ACTION)

        private fun receiverClassFor(type: AppWidgetType): Class<out GlanceAppWidgetReceiver> = when (type) {
            AppWidgetType.PRICE -> PriceGlanceReceiver::class.java
            AppWidgetType.HEADLINES -> HeadlinesGlanceReceiver::class.java
            AppWidgetType.BLOCKS -> BlocksGlanceReceiver::class.java
            AppWidgetType.FACTS -> FactsGlanceReceiver::class.java
            AppWidgetType.WEATHER -> WeatherGlanceReceiver::class.java
        }
    }

    override suspend fun doWork(): Result {
        val activeTypes = preferencesStore.getActiveWidgetTypes()
        if (activeTypes.isEmpty()) return Result.success()

        Logger.debug("Refreshing data for widget types: '$activeTypes'", context = TAG)

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

                AppWidgetType.HEADLINES -> {
                    dataRepository.fetchArticles()
                        .onSuccess { preferencesStore.cacheArticlesAndRotate(it) }
                        .onFailure {
                            Logger.warn("Failed to refresh headlines", it, context = TAG)
                        }
                    HeadlinesGlanceWidget().updateAll(appContext)
                }

                AppWidgetType.BLOCKS -> {
                    dataRepository.fetchBlock()
                        .onSuccess { preferencesStore.cacheBlock(it) }
                        .onFailure {
                            Logger.warn("Failed to refresh block", it, context = TAG)
                        }
                    BlocksGlanceWidget().updateAll(appContext)
                }

                AppWidgetType.FACTS -> {
                    dataRepository.fetchFacts()
                        .onSuccess { preferencesStore.cacheFacts(it) }
                        .onFailure {
                            Logger.warn("Failed to refresh facts", it, context = TAG)
                        }
                    preferencesStore.bumpFactsRotationTick()
                    FactsGlanceWidget().updateAll(appContext)
                }

                AppWidgetType.WEATHER -> {
                    dataRepository.fetchWeather()
                        .onSuccess { preferencesStore.cacheWeather(it) }
                        .onFailure {
                            Logger.warn("Failed to refresh weather", it, context = TAG)
                        }
                    WeatherGlanceWidget().updateAll(appContext)
                }
            }
        }

        return Result.success()
    }
}
