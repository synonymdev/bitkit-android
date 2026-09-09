@file:OptIn(ExperimentalTime::class)

package to.bitkit.repositories

import android.content.Context
import android.os.Bundle
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import to.bitkit.App
import to.bitkit.R
import to.bitkit.ui.EXTRA_PAYKIT_BILLING_PERIOD_STARTS_AT
import to.bitkit.ui.EXTRA_PAYKIT_COUNTERPARTY
import to.bitkit.ui.EXTRA_PAYKIT_COUNTERPARTY_RECEIVER_PATH
import to.bitkit.ui.EXTRA_PAYKIT_PAYER_IDENTITY
import to.bitkit.ui.EXTRA_PAYKIT_PAYMENT_REQUEST_ID
import to.bitkit.ui.EXTRA_PAYKIT_SUBSCRIPTION_PAYMENT_DUE
import to.bitkit.ui.pushNotification
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Singleton
class PaykitSubscriptionNotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    private val workClient: PaykitSubscriptionWorkClient,
) {
    private companion object {
        const val MAX_NOTIFICATIONS = 32
        const val PREFERENCES_NAME = "paykit-subscription-notifications"
        const val SCHEDULED_WORK_NAMES_KEY = "scheduled-work-names"
        const val WORK_PREFIX = "paykit-subscription-"
        const val WORK_TAG = "paykit-subscriptions"
    }

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var scheduledWorkNames = preferences.getStringSet(SCHEDULED_WORK_NAMES_KEY, emptySet()).orEmpty()
    private var notificationsWereEnabled: Boolean? = null

    @Synchronized
    fun synchronize(
        subscriptions: List<PaykitSubscription>,
        acceptedAt: (PaykitSubscription) -> Instant?,
        pendingRequestIds: Set<PaykitPaymentRequestId>,
        payerIdentity: String,
        notificationsEnabled: Boolean,
    ) {
        if (!notificationsEnabled) {
            if (notificationsWereEnabled != false) workClient.cancelAllWorkByTag(WORK_TAG)
            updateScheduledWorkNames(emptySet())
            notificationsWereEnabled = false
            return
        }

        val now = clock.now()
        val scheduledWork = subscriptions
            .filter {
                it.isActive(now) &&
                    it.recurrence.unit.isSupported &&
                    acceptedAt(it) != null
            }
            .flatMap { subscription ->
                subscription.recurrence.upcomingPeriodsAfter(now, MAX_NOTIFICATIONS)
                    .map { subscription to it }
            }
            .sortedBy { it.second.startsAt }
            .take(MAX_NOTIFICATIONS)
            .associate { (subscription, period) ->
                val workName = "$WORK_PREFIX$payerIdentity|${subscription.counterparty}|" +
                    "${subscription.counterpartyReceiverPath}|${subscription.paymentRequestId}|${period.startsAt}"
                val delay = (period.startsAt - now).inWholeMilliseconds.coerceAtLeast(0)
                val work = OneTimeWorkRequestBuilder<PaykitSubscriptionNotificationWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(
                        workDataOf(
                            EXTRA_PAYKIT_PAYMENT_REQUEST_ID to subscription.paymentRequestId,
                            EXTRA_PAYKIT_PAYER_IDENTITY to payerIdentity,
                            EXTRA_PAYKIT_COUNTERPARTY to subscription.counterparty,
                            EXTRA_PAYKIT_COUNTERPARTY_RECEIVER_PATH to subscription.counterpartyReceiverPath,
                            EXTRA_PAYKIT_BILLING_PERIOD_STARTS_AT to period.startsAt.toString(),
                        )
                    )
                    .addTag(WORK_TAG)
                    .build()
                workName to work
            }
        val pendingWorkNames = pendingRequestIds.mapNotNullTo(mutableSetOf()) { requestId ->
            requestId.billingPeriodStartsAt?.let {
                "$WORK_PREFIX$payerIdentity|${requestId.counterparty}|${requestId.counterpartyReceiverPath}|" +
                    "${requestId.paymentRequestId}|$it"
            }
        }
        val desiredWorkNames = scheduledWork.keys + scheduledWorkNames.intersect(pendingWorkNames)
        (scheduledWorkNames - desiredWorkNames).forEach(workClient::cancelUniqueWork)
        scheduledWork
            .filterKeys { it !in scheduledWorkNames }
            .forEach { (workName, work) ->
                workClient.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, work)
            }
        updateScheduledWorkNames(desiredWorkNames)
        notificationsWereEnabled = true
    }

    @Synchronized
    fun cancel() {
        workClient.cancelAllWorkByTag(WORK_TAG)
        updateScheduledWorkNames(emptySet())
        notificationsWereEnabled = false
    }

    private fun updateScheduledWorkNames(workNames: Set<String>) {
        scheduledWorkNames = workNames
        preferences.edit().putStringSet(SCHEDULED_WORK_NAMES_KEY, workNames).apply()
    }
}

@Singleton
class PaykitSubscriptionWorkClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName, existingWorkPolicy, request)
    }

    fun cancelUniqueWork(uniqueWorkName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName)
    }

    fun cancelAllWorkByTag(tag: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tag)
    }
}

@HiltWorker
class PaykitSubscriptionNotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (App.currentActivity?.value != null) return Result.success()
        applicationContext.pushNotification(
            title = applicationContext.getString(R.string.subscriptions__payment_due_title),
            text = applicationContext.getString(R.string.subscriptions__payment_due_description),
            extras = Bundle().apply {
                putBoolean(EXTRA_PAYKIT_SUBSCRIPTION_PAYMENT_DUE, true)
                putString(EXTRA_PAYKIT_PAYER_IDENTITY, inputData.getString(EXTRA_PAYKIT_PAYER_IDENTITY))
                putString(EXTRA_PAYKIT_PAYMENT_REQUEST_ID, inputData.getString(EXTRA_PAYKIT_PAYMENT_REQUEST_ID))
                putString(EXTRA_PAYKIT_COUNTERPARTY, inputData.getString(EXTRA_PAYKIT_COUNTERPARTY))
                putString(
                    EXTRA_PAYKIT_COUNTERPARTY_RECEIVER_PATH,
                    inputData.getString(EXTRA_PAYKIT_COUNTERPARTY_RECEIVER_PATH),
                )
                putString(
                    EXTRA_PAYKIT_BILLING_PERIOD_STARTS_AT,
                    inputData.getString(EXTRA_PAYKIT_BILLING_PERIOD_STARTS_AT),
                )
            },
        )
        return Result.success()
    }
}
