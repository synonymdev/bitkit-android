package to.bitkit.appwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.appwidget.model.AppWidgetType
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class AppWidgetRefreshSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val activeWidgets = FakeActiveWidgets()
    private val workClient = FakeWorkClient()
    private val alarmClient = FakeAlarmClient()
    private val elapsedRealtimeProvider = FakeElapsedRealtimeProvider()
    private val scheduler = AppWidgetRefreshScheduler(
        context = context,
        activeWidgets = activeWidgets,
        workClient = workClient,
        alarmClient = alarmClient,
        elapsedRealtimeProvider = elapsedRealtimeProvider,
    )

    @After
    fun tearDown() {
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AppWidgetRefreshReceiver::class.java)
                .setAction(AppWidgetRefreshScheduler.CATCH_UP_ALARM_ACTION),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.cancel()
    }

    @Test
    fun `ensure scheduled enqueues periodic work and alarm`() {
        activeWidgets.activeTypes = setOf(AppWidgetType.PRICE)

        scheduler.ensureScheduled(AppWidgetRefreshReason.APP_START)

        assertEquals(listOf(AppWidgetRefreshScheduler.PERIODIC_WORK_NAME), workClient.periodicNames)
        assertEquals(listOf(ExistingPeriodicWorkPolicy.KEEP), workClient.periodicPolicies)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmClient.lastType)
        assertEquals(
            elapsedRealtimeProvider.nowMs + AppWidgetRefreshScheduler.REFRESH_INTERVAL.inWholeMilliseconds,
            alarmClient.lastTriggerAtMs,
        )
    }

    @Test
    fun `ensure scheduled enqueues local facts work without network`() {
        activeWidgets.activeTypes = setOf(AppWidgetType.FACTS)

        scheduler.ensureScheduled(AppWidgetRefreshReason.APP_START)

        assertEquals(listOf(AppWidgetRefreshScheduler.FACTS_PERIODIC_WORK_NAME), workClient.periodicNames)
        assertEquals(
            NetworkType.NOT_REQUIRED,
            workClient.periodicRequests.single().workSpec.constraints.requiredNetworkType,
        )
        assertEquals(
            AppWidgetRefreshReason.FACTS_LOCAL_REFRESH.name,
            workClient.periodicRequests.single().workSpec.input.getString(AppWidgetRefreshScheduler.WORK_INPUT_REASON),
        )
    }

    @Test
    fun `ensure scheduled keeps periodic work when alarm scheduling fails`() {
        activeWidgets.activeTypes = setOf(AppWidgetType.PRICE)
        alarmClient.throwOnSet = true

        scheduler.ensureScheduled(AppWidgetRefreshReason.APP_START)

        assertEquals(listOf(AppWidgetRefreshScheduler.PERIODIC_WORK_NAME), workClient.periodicNames)
        assertEquals(0, alarmClient.setCount)
    }

    @Test
    fun `request catch up enqueues one-time work with exponential backoff`() {
        activeWidgets.activeTypes = setOf(AppWidgetType.PRICE)

        scheduler.requestCatchUp(AppWidgetRefreshReason.APP_FOREGROUND)

        assertEquals(listOf(AppWidgetRefreshScheduler.CATCH_UP_WORK_NAME), workClient.oneTimeNames)
        assertEquals(listOf(ExistingWorkPolicy.KEEP), workClient.oneTimePolicies)
        assertEquals(BackoffPolicy.EXPONENTIAL, workClient.oneTimeRequests.single().workSpec.backoffPolicy)
        assertEquals(10.seconds.inWholeMilliseconds, workClient.oneTimeRequests.single().workSpec.backoffDelayDuration)
    }

    @Test
    fun `request catch up enqueues local facts work without network`() {
        activeWidgets.activeTypes = setOf(AppWidgetType.FACTS)

        scheduler.requestCatchUp(AppWidgetRefreshReason.FACTS_WIDGET_UPDATE)

        assertEquals(listOf(AppWidgetRefreshScheduler.FACTS_CATCH_UP_WORK_NAME), workClient.oneTimeNames)
        assertEquals(
            NetworkType.NOT_REQUIRED,
            workClient.oneTimeRequests.single().workSpec.constraints.requiredNetworkType,
        )
        assertEquals(
            AppWidgetRefreshReason.FACTS_LOCAL_REFRESH.name,
            workClient.oneTimeRequests.single().workSpec.input.getString(AppWidgetRefreshScheduler.WORK_INPUT_REASON),
        )
    }

    @Test
    fun `cancel if no widgets cancels all work and alarm`() {
        activeWidgets.activeTypes = setOf(AppWidgetType.PRICE, AppWidgetType.FACTS)
        scheduler.ensureScheduled(AppWidgetRefreshReason.APP_START)

        activeWidgets.activeTypes = emptySet()
        scheduler.cancelIfNoWidgets(AppWidgetRefreshReason.PRICE_WIDGET_DISABLED)

        assertEquals(
            listOf(
                AppWidgetRefreshScheduler.PERIODIC_WORK_NAME,
                AppWidgetRefreshScheduler.CATCH_UP_WORK_NAME,
                AppWidgetRefreshScheduler.FACTS_PERIODIC_WORK_NAME,
                AppWidgetRefreshScheduler.FACTS_CATCH_UP_WORK_NAME,
            ),
            workClient.canceledNames,
        )
        assertEquals(1, alarmClient.cancelCount)
    }

    @Test
    fun `catch-up alarm requests work and schedules next alarm`() {
        activeWidgets.activeTypes = setOf(AppWidgetType.PRICE)

        scheduler.handleCatchUpAlarm(AppWidgetRefreshReason.CATCH_UP_ALARM)

        assertEquals(listOf(AppWidgetRefreshScheduler.CATCH_UP_WORK_NAME), workClient.oneTimeNames)
        assertEquals(1, alarmClient.setCount)
    }
}

private class FakeActiveWidgets : AppWidgetActiveWidgets {
    var activeTypes = emptySet<AppWidgetType>()

    override fun hasActiveWidgets(): Boolean = activeTypes.isNotEmpty()

    override fun hasActiveWidgets(type: AppWidgetType): Boolean = type in activeTypes
}

private class FakeWorkClient : AppWidgetWorkClient {
    val periodicNames = mutableListOf<String>()
    val periodicPolicies = mutableListOf<ExistingPeriodicWorkPolicy>()
    val periodicRequests = mutableListOf<PeriodicWorkRequest>()
    val oneTimeNames = mutableListOf<String>()
    val oneTimePolicies = mutableListOf<ExistingWorkPolicy>()
    val oneTimeRequests = mutableListOf<OneTimeWorkRequest>()
    val canceledNames = mutableListOf<String>()

    override fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ) {
        periodicNames += uniqueWorkName
        periodicPolicies += existingPeriodicWorkPolicy
        periodicRequests += request
    }

    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        oneTimeNames += uniqueWorkName
        oneTimePolicies += existingWorkPolicy
        oneTimeRequests += request
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        canceledNames += uniqueWorkName
    }
}

private class FakeAlarmClient : AppWidgetAlarmClient {
    var setCount = 0
    var cancelCount = 0
    var lastType: Int? = null
    var lastTriggerAtMs: Long? = null
    var throwOnSet = false

    override fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
        if (throwOnSet) error("Alarm failure")

        setCount += 1
        lastType = type
        lastTriggerAtMs = triggerAtMillis
    }

    override fun cancel(operation: PendingIntent) {
        cancelCount += 1
    }
}

private class FakeElapsedRealtimeProvider : ElapsedRealtimeProvider {
    val nowMs = 10_000L

    override fun elapsedRealtime(): Long = nowMs
}
