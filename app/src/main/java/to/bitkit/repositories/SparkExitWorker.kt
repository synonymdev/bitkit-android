package to.bitkit.repositories

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.text.format.DateFormat
import androidx.core.content.edit
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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import to.bitkit.App
import to.bitkit.R
import to.bitkit.async.appScope
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.alarmManager
import to.bitkit.ext.connectivityManager
import to.bitkit.ext.nowMs
import to.bitkit.ui.areNotificationsEnabled
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import to.bitkit.utils.batterySettingsSnapshot
import to.bitkit.utils.logBatterySettings
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs a Spark exit while Bitkit is closed, and tells the user when it cannot.
 *
 * A unique periodic work drives the exit every 15 minutes while it runs; WorkManager keeps it across app kills and
 * reboots, and app start queues it again if it is missing. Each run that moves the exit forward re-arms a deadline
 * alarm 12 blocks into the operators' window of the next step. The alarm fires only when no run made it in time
 * (no network, battery settings, background restrictions, failures), and then posts a notification naming the
 * deadline. Needing more fee funding is notified at once.
 */
@Singleton
class SparkExitWork @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "SparkExitWork"
        private const val PERIODIC_WORK = "spark-exit-tick"
        private const val CATCH_UP_WORK = "spark-exit-catch-up"
        const val DEADLINE_ACTION = "to.bitkit.spark-exit.DEADLINE"
        private const val PREFERENCES = "spark-exit-work"
        private const val KEY_WARN_AT = "warn-at"
        private const val KEY_DEADLINE_AT = "deadline-at"
        private const val NOTIFICATION_ID = 7797

        /** The shortest period WorkManager runs periodic work at. */
        val PERIOD = 15.minutes

        /** The exit of the wallet Bitkit runs, read before the wallet graph exists. */
        private fun store() = SparkExitStore(File(Env.sparkExitRecordPath(0)))
    }

    private val scope = appScope(bgDispatcher, TAG)
    private val preferences get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SparkExitWorker>(PERIOD.inWholeMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * At app start, boot and app update: queues the periodic work again when an exit runs, and re-arms its deadline
     * alarm, which a reboot clears.
     */
    fun ensureScheduled() {
        scope.launch {
            val record = runCatching { store().load() }.getOrNull()
            if (record?.needsDriving != true) {
                if (preferences.contains(KEY_WARN_AT)) cancelAll()
                return@launch
            }
            schedulePeriodic()
            val now = clock.nowMs()
            val warnAt = preferences.getLong(KEY_WARN_AT, 0L)
            val deadlineAt = preferences.getLong(KEY_DEADLINE_AT, 0L)
            if (record.phase == SparkExitRecord.Phase.Running && (warnAt == 0L || !isDeadlineArmed())) {
                if (warnAt == 0L) {
                    armDeadline(SparkExitMath.deadline(Duration.ZERO, Env.network), now)
                } else {
                    setAlarm(maxOf(warnAt, now), deadlineAt)
                }
            }
            Logger.debug("Ensured Spark exit background work (${context.logBatterySettings()})", context = TAG)
        }
    }

    /** Re-arms the warning that the exit fell behind; every run that moves the exit forward pushes it back. */
    fun armDeadline(deadline: SparkExitDeadline, nowMillis: Long = clock.nowMs()) {
        setAlarm(
            warnAt = nowMillis + deadline.warnIn.inWholeMilliseconds,
            deadlineAt = nowMillis + deadline.deadlineIn.inWholeMilliseconds,
        )
    }

    fun cancelDeadline() {
        deadlinePendingIntent(PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            context.alarmManager.cancel(it)
            it.cancel()
        }
        preferences.edit {
            remove(KEY_WARN_AT)
            remove(KEY_DEADLINE_AT)
        }
    }

    fun cancelAll() {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(CATCH_UP_WORK)
        }
        cancelDeadline()
    }

    /**
     * The deadline alarm fired, so no run moved the exit forward in time: tells the user why and until when, tries a
     * run now, and warns again later if it stays behind.
     */
    suspend fun onDeadline() {
        val record = runCatching { store().load() }.getOrNull()
        val alert = SparkExitAlerts.reason(record, isOnline(), backgroundLimits())
        Logger.warn(
            "Spark exit deadline alarm fired with alert '$alert' (${context.logBatterySettings()})",
            context = TAG,
        )
        if (alert == null || record?.phase != SparkExitRecord.Phase.Running) {
            cancelDeadline()
            return
        }
        val deadlineAt = preferences.getLong(KEY_DEADLINE_AT, 0L).takeIf { it > clock.nowMs() }
        notify(alert, deadlineAt)
        WorkManager.getInstance(context).enqueueUniqueWork(
            CATCH_UP_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SparkExitWorker>().setConstraints(constraints).build(),
        )
        armDeadline(SparkExitMath.deadline(Duration.ZERO, Env.network))
    }

    /** Posts the exit's notification, unless Bitkit is on screen, where the progress screen says the same. */
    fun notify(alert: SparkExitAlert, deadlineAtMillis: Long? = null) {
        if (App.currentActivity?.value != null) return
        val time = deadlineAtMillis?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
        val text = context.getString(alert.textRes(hasDeadline = time != null)).replace("{time}", time.orEmpty())
        context.pushNotification(
            title = context.getString(R.string.lightning__spark_exit__notification_title),
            text = text,
            bigText = text,
            id = NOTIFICATION_ID,
        )
    }

    fun backgroundLimits(): SparkExitBackgroundLimits {
        val battery = context.batterySettingsSnapshot()
        return SparkExitBackgroundLimits(
            isRestricted = battery.isBackgroundRestricted == true,
            isPowerSaveMode = battery.isPowerSaveMode == true,
            isOptimized = battery.isIgnoringBatteryOptimizations == false,
            areNotificationsEnabled = context.areNotificationsEnabled(),
        )
    }

    private fun isOnline(): Boolean {
        val connectivity = context.connectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setAlarm(warnAt: Long, deadlineAt: Long) {
        preferences.edit {
            putLong(KEY_WARN_AT, warnAt)
            putLong(KEY_DEADLINE_AT, deadlineAt)
        }
        val operation = checkNotNull(
            deadlinePendingIntent(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        ) { "Expected the Spark exit deadline PendingIntent" }
        // Inexact but allowed in Doze, which needs no exact alarm permission; the window is hours on mainnet.
        runCatching { context.alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, warnAt, operation) }
            .onFailure { Logger.error("Failed to arm the Spark exit deadline alarm", it, context = TAG) }
    }

    private fun isDeadlineArmed(): Boolean =
        deadlinePendingIntent(PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null

    private fun deadlinePendingIntent(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, SparkExitReceiver::class.java).setAction(DEADLINE_ACTION),
        flags,
    )
}

/** What Android lets Bitkit do in the background, for the notification and the progress screen. */
data class SparkExitBackgroundLimits(
    /** The user restricted Bitkit's background use: no work or alarm runs while it is closed. */
    val isRestricted: Boolean = false,
    val isPowerSaveMode: Boolean = false,
    /** Battery optimization is on, Android's default, so background runs wait for Doze's windows. */
    val isOptimized: Boolean = false,
    val areNotificationsEnabled: Boolean = true,
)

/** Why the user is asked to open Bitkit. */
enum class SparkExitAlert { Funding, Lost, Rejected, Offline, Restricted, Battery, Behind }

object SparkExitAlerts {
    /** Why a running exit fell behind its deadline, or null when it needs nothing from the user. */
    fun reason(record: SparkExitRecord?, isOnline: Boolean, limits: SparkExitBackgroundLimits): SparkExitAlert? {
        if (record?.isActive != true) return null
        return when {
            record.phase is SparkExitRecord.Phase.NeedsFunding -> SparkExitAlert.Funding
            record.phase != SparkExitRecord.Phase.Running -> null
            record.surfacedRejection != null -> SparkExitAlert.Rejected
            !isOnline -> SparkExitAlert.Offline
            limits.isRestricted -> SparkExitAlert.Restricted
            // Battery optimization alone is Android's default for nearly every app, so it names no cause.
            limits.isPowerSaveMode -> SparkExitAlert.Battery
            else -> SparkExitAlert.Behind
        }
    }
}

private fun SparkExitAlert.textRes(hasDeadline: Boolean): Int = when (this) {
    SparkExitAlert.Funding -> R.string.lightning__spark_exit__notification_funding
    SparkExitAlert.Lost -> R.string.lightning__spark_exit__notification_lost
    SparkExitAlert.Rejected if hasDeadline -> R.string.lightning__spark_exit__notification_rejected
    SparkExitAlert.Offline if hasDeadline -> R.string.lightning__spark_exit__notification_offline
    SparkExitAlert.Restricted if hasDeadline -> R.string.lightning__spark_exit__notification_restricted
    SparkExitAlert.Battery if hasDeadline -> R.string.lightning__spark_exit__notification_battery
    SparkExitAlert.Behind if hasDeadline -> R.string.lightning__spark_exit__notification_behind
    else -> R.string.lightning__spark_exit__notification_late
}

@HiltWorker
class SparkExitWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sparkExitRepo: SparkExitRepo,
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        private const val TAG = "SparkExitWorker"
    }

    override suspend fun doWork(): Result {
        Logger.debug("Driving the Spark exit in background (${applicationContext.logBatterySettings()})", context = TAG)
        sparkExitRepo.runInBackground().onFailure {
            Logger.warn("Failed to drive the Spark exit in background", it, context = TAG)
            return Result.retry()
        }
        return Result.success()
    }
}

/** The deadline alarm, and boot and app updates, which clear alarms. */
class SparkExitReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SparkExitReceiverEntryPoint {
        fun sparkExitWork(): SparkExitWork

        @BgDispatcher
        fun bgDispatcher(): CoroutineDispatcher
    }

    override fun onReceive(context: Context, intent: Intent) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SparkExitReceiverEntryPoint::class.java,
        )
        val work = entryPoint.sparkExitWork()
        when (intent.action) {
            SparkExitWork.DEADLINE_ACTION -> {
                val pending = goAsync()
                appScope(entryPoint.bgDispatcher(), TAG).launch {
                    try {
                        work.onDeadline()
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> work.ensureScheduled()
        }
    }

    private companion object {
        const val TAG = "SparkExitReceiver"
    }
}
