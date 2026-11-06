package to.bitkit.repositories

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.text.format.DateFormat
import androidx.core.content.edit
import androidx.core.os.bundleOf
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
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Network
import to.bitkit.App
import to.bitkit.R
import to.bitkit.async.appScope
import to.bitkit.data.AppDb
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.alarmManager
import to.bitkit.ext.connectivityManager
import to.bitkit.ext.nowMs
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.EXTRA_SPARK_EXIT
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
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
    private val db: AppDb,
) {
    companion object {
        private const val TAG = "SparkExitWork"
        private const val PERIODIC_WORK = "spark-exit-tick"
        private const val CATCH_UP_WORK = "spark-exit-catch-up"
        private const val FUNDING_REMINDER_WORK = "spark-exit-funding-reminder"
        const val KEY_EXIT_CREATED_AT = "exit-created-at"
        const val DEADLINE_ACTION = "to.bitkit.spark-exit.DEADLINE"
        private const val PREFERENCES = "spark-exit-work"
        private const val KEY_WARN_AT = "warn-at"
        private const val KEY_DEADLINE_AT = "deadline-at"
        private const val KEY_FEE_LOW_RATE = "fee-low-rate"
        private const val NOTIFICATION_ID = 7797

        /** The shortest period WorkManager runs periodic work at. */
        val PERIOD = 15.minutes
    }

    /** The exit of the wallet Bitkit runs, read before the wallet graph exists. */
    private suspend fun store(): SparkExitStore = SparkExitStore(File(Env.sparkExitRecordPath(activeWalletIndex())))

    /** The wallet Bitkit runs, read from its config before the wallet graph or Spark has started. */
    suspend fun activeWalletIndex(): Int =
        runSuspendCatching { db.configDao().getAll().first().firstOrNull()?.walletIndex }
            .onFailure { Logger.warn("Failed to read the active wallet for the Spark exit", it, context = TAG) }
            .getOrNull()
            ?.toInt()
            ?: 0

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
            val loaded = runSuspendCatching { store().load() }
            // A record that cannot be read is salvaged by the next run, which the alarm and the work stay set for.
            if (loaded.isFailure) {
                Logger.warn("Failed to read the Spark exit record; keeping its background work", context = TAG)
                schedulePeriodic()
                return@launch
            }
            val record = loaded.getOrNull()
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
            remove(KEY_FEE_LOW_RATE)
        }
    }

    /**
     * Keeps the exit's fee rate while the network finds it too low, or forgets it with null, so the deadline alarm
     * names the fee even when the process that saw it is gone.
     */
    fun keepFeeLow(feeRateSatPerVbyte: ULong?) {
        preferences.edit {
            if (feeRateSatPerVbyte == null) {
                remove(KEY_FEE_LOW_RATE)
            } else {
                putLong(KEY_FEE_LOW_RATE, feeRateSatPerVbyte.toLong())
            }
        }
    }

    fun cancelAll() {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(CATCH_UP_WORK)
            cancelAllWorkByTag(FUNDING_REMINDER_WORK)
        }
        cancelDeadline()
    }

    /**
     * Reminds once, after [delay], that the exit created at [exitCreatedAt] waits for its fee funding. Unique per
     * exit and kept as first scheduled, so later ticks do not push it back.
     */
    fun scheduleFundingReminder(exitCreatedAt: Long, delay: Duration) {
        val request = OneTimeWorkRequestBuilder<SparkExitFundingReminderWorker>()
            .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_EXIT_CREATED_AT to exitCreatedAt))
            .addTag(FUNDING_REMINDER_WORK)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(fundingReminderName(exitCreatedAt), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelFundingReminder(exitCreatedAt: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(fundingReminderName(exitCreatedAt))
    }

    private fun fundingReminderName(exitCreatedAt: Long) = "$FUNDING_REMINDER_WORK-$exitCreatedAt"

    /** The reminder fired: posts the funding notification while the exit it was for still waits for funding. */
    suspend fun onFundingReminder(exitCreatedAt: Long) {
        val record = runSuspendCatching { store().load() }.getOrNull() ?: return
        if (record.createdAt != exitCreatedAt || !SparkExitAlerts.isAwaitingFirstFunding(record)) return
        notify(SparkExitAlert.Funding)
    }

    /**
     * The deadline alarm fired, so no run moved the exit forward in time: tells the user why and until when, tries a
     * run now, and warns again later if it stays behind.
     */
    suspend fun onDeadline() {
        val loaded = runSuspendCatching { store().load() }
        if (loaded.isFailure) {
            Logger.warn("Failed to read the Spark exit record at its deadline; running it now", context = TAG)
            enqueueCatchUp()
            armDeadline(SparkExitMath.deadline(Duration.ZERO, Env.network))
            return
        }
        val record = loaded.getOrNull()
        val feeLowRate = preferences.getLong(KEY_FEE_LOW_RATE, 0L).takeIf { it > 0L }?.toULong()
        val alert = SparkExitAlerts.reason(record, isOnline(), backgroundLimits(), feeLowRate)
        Logger.warn(
            "Spark exit deadline alarm fired with alert '$alert' (${context.logBatterySettings()})",
            context = TAG,
        )
        if (alert == null || record?.phase != SparkExitRecord.Phase.Running) {
            cancelDeadline()
            return
        }
        val deadlineAt = preferences.getLong(KEY_DEADLINE_AT, 0L).takeIf { it > clock.nowMs() }
        notify(alert, deadlineAt, feeRateSatPerVbyte = feeLowRate)
        enqueueCatchUp()
        armDeadline(SparkExitMath.deadline(Duration.ZERO, Env.network))
    }

    private fun enqueueCatchUp() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CATCH_UP_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SparkExitWorker>().setConstraints(constraints).build(),
        )
    }

    /** Posts the exit's notification, unless Bitkit is on screen, where the progress screen says the same. */
    fun notify(
        alert: SparkExitAlert,
        deadlineAtMillis: Long? = null,
        amountSats: ULong? = null,
        feeRateSatPerVbyte: ULong? = null,
    ) {
        // Losses and a stopped exit are posted on screen too.
        if (App.currentActivity?.value != null && !alert.isPostedOnScreen) return
        val time = deadlineAtMillis?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
        val amount = amountSats?.let { "$BITCOIN_SYMBOL ${it.formatToModernDisplay()}" }
        val text = context.getString(alert.textRes(hasDeadline = time != null))
            .replace("{time}", time.orEmpty())
            .replace("{amount}", amount.orEmpty())
            .replace("{rate}", feeRateSatPerVbyte?.toString().orEmpty())
        context.pushNotification(
            title = context.getString(R.string.lightning__spark_exit__notification_title),
            text = text,
            extras = bundleOf(EXTRA_SPARK_EXIT to true),
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

/**
 * Why the user is asked to open Bitkit. [NotMoving] is the exit stopping its rebuilds over a broken chain, posted once
 * on the tick it happens, with the refused-steps text.
 */
enum class SparkExitAlert { Funding, Lost, Failed, NotMoving, Rejected, FeeLow, Offline, Restricted, Battery, Behind }

/** Posted while Bitkit is on screen too: the user may be anywhere in the app, and no later run changes them. */
val SparkExitAlert.isPostedOnScreen: Boolean
    get() = this == SparkExitAlert.Lost || this == SparkExitAlert.Failed || this == SparkExitAlert.NotMoving

object SparkExitAlerts {
    /**
     * What a tick that moved the exit from [previous] to [record] lost, each posted once: leaves an older owner
     * claimed, and the exit failing, unless it failed because every leaf was claimed, which the lost leaves say. A leaf
     * in [reportedLost] was already reported by an earlier exit.
     */
    fun losses(
        record: SparkExitRecord,
        previous: SparkExitRecord,
        reportedLost: Set<String> = emptySet(),
    ): List<SparkExitAlert> {
        val phase = record.phase
        val known = previous.lostLeaves.map { it.leafId }.toSet() + reportedLost
        return listOfNotNull(
            SparkExitAlert.Lost.takeIf { record.lostLeaves.any { it.leafId !in known } },
            SparkExitAlert.Failed.takeIf {
                phase is SparkExitRecord.Phase.Failed && !record.isEveryLeafLost &&
                    previous.phase !is SparkExitRecord.Phase.Failed
            },
        )
    }

    /** How long after its start an unfunded exit is reminded about: 4 hours, 10 minutes on regtest, as on iOS. */
    private val fundingReminderDelay = 4.hours
    private val regtestFundingReminderDelay = 10.minutes

    /** A started exit nothing was paid into: no adopted funding, no payment from savings. */
    fun isAwaitingFirstFunding(record: SparkExitRecord): Boolean =
        record.phase is SparkExitRecord.Phase.AwaitingFunding && record.fundingShortfallSats != null &&
            record.fundedSats == 0uL && record.fundingPaymentTxid == null

    /**
     * How long from [nowMillis] until the one reminder about [record] waiting for its first funding, or null when no
     * reminder is due: funding was paid or [seenSats] reached the address, or the exit is past that phase.
     */
    fun fundingReminderIn(record: SparkExitRecord, seenSats: ULong, nowMillis: Long, network: Network): Duration? {
        if (!isAwaitingFirstFunding(record) || seenSats > 0uL) return null
        val delay = if (network == Network.REGTEST) regtestFundingReminderDelay else fundingReminderDelay
        val at = record.createdAt + delay.inWholeMilliseconds
        return maxOf(at - nowMillis, 0L).milliseconds
    }

    /** [record] started saying it is not moving on the tick from [previous]. */
    fun isNewlyStalled(record: SparkExitRecord, previous: SparkExitRecord): Boolean =
        record.isActive && record.isStalled && !previous.isStalled

    /**
     * Why a running exit fell behind its deadline, or null when it needs nothing from the user. [feeLowRate] is the
     * exit's rate while the network finds it too low, which no background run changes.
     */
    fun reason(
        record: SparkExitRecord?,
        isOnline: Boolean,
        limits: SparkExitBackgroundLimits,
        feeLowRate: ULong? = null,
    ): SparkExitAlert? {
        if (record?.isActive != true) return null
        return when {
            record.phase is SparkExitRecord.Phase.NeedsFunding -> SparkExitAlert.Funding
            record.phase != SparkExitRecord.Phase.Running -> null
            record.surfacedRejection != null ||
                (record.stuckStep?.rejections ?: 0) >= SparkExitDriver.REJECTIONS_TO_SURFACE -> SparkExitAlert.Rejected
            feeLowRate != null -> SparkExitAlert.FeeLow
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
    SparkExitAlert.Failed -> R.string.lightning__spark_exit__notification_failed
    SparkExitAlert.FeeLow -> R.string.lightning__spark_exit__fee_low
    SparkExitAlert.Rejected if hasDeadline -> R.string.lightning__spark_exit__notification_rejected
    SparkExitAlert.NotMoving if hasDeadline -> R.string.lightning__spark_exit__notification_rejected
    SparkExitAlert.Offline if hasDeadline -> R.string.lightning__spark_exit__notification_offline
    SparkExitAlert.Restricted if hasDeadline -> R.string.lightning__spark_exit__notification_restricted
    SparkExitAlert.Battery if hasDeadline -> R.string.lightning__spark_exit__notification_battery
    SparkExitAlert.Behind if hasDeadline -> R.string.lightning__spark_exit__notification_behind
    else -> R.string.lightning__spark_exit__notification_late
}

/** The one reminder that a started exit still waits for its fee funding. */
@HiltWorker
class SparkExitFundingReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val work: SparkExitWork,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val createdAt = inputData.getLong(SparkExitWork.KEY_EXIT_CREATED_AT, 0L)
        work.onFundingReminder(createdAt)
        return Result.success()
    }
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
