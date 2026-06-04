package to.bitkit.appwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.blocks.BlocksGlanceReceiver
import to.bitkit.appwidget.ui.facts.FactsGlanceReceiver
import to.bitkit.appwidget.ui.headlines.HeadlinesGlanceReceiver
import to.bitkit.appwidget.ui.price.PriceGlanceReceiver
import to.bitkit.appwidget.ui.weather.WeatherGlanceReceiver
import to.bitkit.ext.alarmManager
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@Singleton
class AppWidgetRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeWidgets: AppWidgetActiveWidgets,
    private val workClient: AppWidgetWorkClient,
    private val alarmClient: AppWidgetAlarmClient,
    private val elapsedRealtimeProvider: ElapsedRealtimeProvider,
) {
    fun ensureScheduled(reason: AppWidgetRefreshReason) {
        if (!activeWidgets.hasActiveWidgets()) {
            cancelAll(reason)
            return
        }

        ensureRemotePeriodicWork(reason)
        ensureFactsPeriodicWork()
        scheduleCatchUpAlarm(reason)
        Logger.debug("Ensured widget refresh schedule for '${reason.name}'", context = TAG)
    }

    fun requestCatchUp(reason: AppWidgetRefreshReason) {
        if (!activeWidgets.hasActiveWidgets()) {
            cancelAll(reason)
            return
        }

        requestRemoteCatchUp(reason)
        requestFactsCatchUp()
        Logger.debug("Requested widget catch-up refresh for '${reason.name}'", context = TAG)
    }

    fun handleCatchUpAlarm(reason: AppWidgetRefreshReason) {
        requestCatchUp(reason)
        scheduleCatchUpAlarm(reason)
    }

    private fun ensureRemotePeriodicWork(reason: AppWidgetRefreshReason) {
        if (!activeWidgets.hasRemoteBackedWidgets()) {
            workClient.cancelUniqueWork(PERIODIC_WORK_NAME)
            workClient.cancelUniqueWork(CATCH_UP_WORK_NAME)
            return
        }

        workClient.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest(reason, requiresNetwork = true),
        )
    }

    private fun ensureFactsPeriodicWork() {
        if (!activeWidgets.hasActiveWidgets(AppWidgetType.FACTS)) {
            workClient.cancelUniqueWork(FACTS_PERIODIC_WORK_NAME)
            workClient.cancelUniqueWork(FACTS_CATCH_UP_WORK_NAME)
            return
        }

        workClient.enqueueUniquePeriodicWork(
            FACTS_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest(AppWidgetRefreshReason.FACTS_LOCAL_REFRESH, requiresNetwork = false),
        )
    }

    private fun requestRemoteCatchUp(reason: AppWidgetRefreshReason) {
        if (!activeWidgets.hasRemoteBackedWidgets()) {
            workClient.cancelUniqueWork(CATCH_UP_WORK_NAME)
            return
        }

        workClient.enqueueUniqueWork(
            CATCH_UP_WORK_NAME,
            catchUpWorkPolicy(reason),
            oneTimeRequest(reason, requiresNetwork = true),
        )
    }

    private fun catchUpWorkPolicy(reason: AppWidgetRefreshReason): ExistingWorkPolicy =
        when (reason) {
            AppWidgetRefreshReason.APP_START,
            AppWidgetRefreshReason.APP_FOREGROUND -> ExistingWorkPolicy.REPLACE
            else -> ExistingWorkPolicy.KEEP
        }

    private fun requestFactsCatchUp() {
        if (!activeWidgets.hasActiveWidgets(AppWidgetType.FACTS)) {
            workClient.cancelUniqueWork(FACTS_CATCH_UP_WORK_NAME)
            return
        }

        workClient.enqueueUniqueWork(
            FACTS_CATCH_UP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            oneTimeRequest(AppWidgetRefreshReason.FACTS_LOCAL_REFRESH, requiresNetwork = false),
        )
    }

    private fun scheduleCatchUpAlarm(reason: AppWidgetRefreshReason) {
        if (!activeWidgets.hasActiveWidgets()) {
            cancelAll(reason)
            return
        }

        val triggerAt = elapsedRealtimeProvider.elapsedRealtime() + REFRESH_INTERVAL.inWholeMilliseconds
        runCatching {
            alarmClient.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                checkNotNull(
                    catchUpAlarmPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                ) { "Expected catch-up alarm PendingIntent" },
            )
        }.onSuccess {
            Logger.debug("Scheduled widget catch-up alarm for '${reason.name}'", context = TAG)
        }.onFailure {
            Logger.error("Failed to schedule widget catch-up alarm for '${reason.name}'", it, context = TAG)
        }
    }

    private fun cancelAll(reason: AppWidgetRefreshReason) {
        workClient.cancelUniqueWork(PERIODIC_WORK_NAME)
        workClient.cancelUniqueWork(CATCH_UP_WORK_NAME)
        workClient.cancelUniqueWork(FACTS_PERIODIC_WORK_NAME)
        workClient.cancelUniqueWork(FACTS_CATCH_UP_WORK_NAME)
        cancelCatchUpAlarm()
        Logger.debug("Canceled widget refresh schedule for '${reason.name}'", context = TAG)
    }

    private fun cancelCatchUpAlarm() {
        val pendingIntent = catchUpAlarmPendingIntent(
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return

        alarmClient.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun catchUpAlarmPendingIntent(flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            CATCH_UP_ALARM_REQUEST_CODE,
            catchUpAlarmIntent(),
            flags,
        )

    private fun catchUpAlarmIntent(): Intent =
        Intent(context, AppWidgetRefreshReceiver::class.java)
            .setAction(CATCH_UP_ALARM_ACTION)

    private fun periodicRequest(
        reason: AppWidgetRefreshReason,
        requiresNetwork: Boolean,
    ): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<AppWidgetRefreshWorker>(REFRESH_INTERVAL.toJavaDuration())
            .apply {
                if (requiresNetwork) setConstraints(networkConstraints())
                setInputData(workDataOf(WORK_INPUT_REASON to reason.name))
            }
            .build()

    private fun oneTimeRequest(
        reason: AppWidgetRefreshReason,
        requiresNetwork: Boolean,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<AppWidgetRefreshWorker>()
            .apply {
                if (requiresNetwork) setConstraints(networkConstraints())
                setBackoffCriteria(BackoffPolicy.EXPONENTIAL, CATCH_UP_RETRY_BACKOFF.toJavaDuration())
                setInputData(workDataOf(WORK_INPUT_REASON to reason.name))
            }
            .build()

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    companion object {
        const val CATCH_UP_ALARM_ACTION = "to.bitkit.appwidget.REFRESH_ALARM"
        const val WORK_INPUT_REASON = "reason"
        const val PERIODIC_WORK_NAME = "appwidget_refresh"
        const val CATCH_UP_WORK_NAME = "appwidget_refresh_catch_up"
        const val FACTS_PERIODIC_WORK_NAME = "appwidget_facts_refresh"
        const val FACTS_CATCH_UP_WORK_NAME = "appwidget_facts_refresh_catch_up"
        private const val TAG = "AppWidgetRefreshScheduler"
        private const val CATCH_UP_ALARM_REQUEST_CODE = 0
        val REFRESH_INTERVAL = 15.minutes
        private val CATCH_UP_RETRY_BACKOFF = 10.seconds
    }
}

enum class AppWidgetRefreshReason {
    APP_START,
    APP_FOREGROUND,
    BLOCKS_WIDGET_DISABLED,
    BLOCKS_WIDGET_ENABLED,
    BLOCKS_WIDGET_UPDATE,
    BOOT_COMPLETED,
    CATCH_UP_ALARM,
    FACTS_WIDGET_DISABLED,
    FACTS_WIDGET_ENABLED,
    FACTS_LOCAL_REFRESH,
    FACTS_WIDGET_REGISTERED,
    FACTS_WIDGET_UPDATE,
    HEADLINES_WIDGET_DISABLED,
    HEADLINES_WIDGET_ENABLED,
    HEADLINES_WIDGET_UPDATE,
    PACKAGE_REPLACED,
    PRICE_WIDGET_DISABLED,
    PRICE_WIDGET_ENABLED,
    PRICE_WIDGET_UPDATE,
    SERVICE_STOP_ACTION,
    WEATHER_WIDGET_DISABLED,
    WEATHER_WIDGET_ENABLED,
    WEATHER_WIDGET_UPDATE,
    WIDGET_CONFIG_CONFIRM,
}

fun AppWidgetType.receiverClass(): Class<out GlanceAppWidgetReceiver> = when (this) {
    AppWidgetType.PRICE -> PriceGlanceReceiver::class.java
    AppWidgetType.HEADLINES -> HeadlinesGlanceReceiver::class.java
    AppWidgetType.BLOCKS -> BlocksGlanceReceiver::class.java
    AppWidgetType.FACTS -> FactsGlanceReceiver::class.java
    AppWidgetType.WEATHER -> WeatherGlanceReceiver::class.java
}

interface AppWidgetActiveWidgets {
    fun hasActiveWidgets(): Boolean
    fun hasActiveWidgets(type: AppWidgetType): Boolean
}

private fun AppWidgetActiveWidgets.hasRemoteBackedWidgets(): Boolean =
    AppWidgetType.entries.any { it != AppWidgetType.FACTS && hasActiveWidgets(it) }

@Singleton
class AndroidAppWidgetActiveWidgets @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppWidgetActiveWidgets {
    override fun hasActiveWidgets(): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return AppWidgetType.entries.any {
            manager.getAppWidgetIds(ComponentName(context, it.receiverClass())).isNotEmpty()
        }
    }

    override fun hasActiveWidgets(type: AppWidgetType): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context, type.receiverClass())).isNotEmpty()
    }
}

interface AppWidgetWorkClient {
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )

    fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    fun cancelUniqueWork(uniqueWorkName: String)
}

@Singleton
class AndroidAppWidgetWorkClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppWidgetWorkClient {
    override fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueWorkName,
            existingPeriodicWorkPolicy,
            request,
        )
    }

    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName,
            existingWorkPolicy,
            request,
        )
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName)
    }
}

interface AppWidgetAlarmClient {
    fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent)
    fun cancel(operation: PendingIntent)
}

@Singleton
class AndroidAppWidgetAlarmClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppWidgetAlarmClient {
    override fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
        context.alarmManager.setAndAllowWhileIdle(type, triggerAtMillis, operation)
    }

    override fun cancel(operation: PendingIntent) {
        context.alarmManager.cancel(operation)
    }
}

interface ElapsedRealtimeProvider {
    fun elapsedRealtime(): Long
}

@Singleton
class AndroidElapsedRealtimeProvider @Inject constructor() : ElapsedRealtimeProvider {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}
