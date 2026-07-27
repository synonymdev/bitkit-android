package to.bitkit.androidServices

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Event
import to.bitkit.App
import to.bitkit.R
import to.bitkit.appwidget.AppWidgetRefreshReason
import to.bitkit.appwidget.AppWidgetRefreshScheduler
import to.bitkit.async.appScope
import to.bitkit.data.CacheStore
import to.bitkit.di.UiDispatcher
import to.bitkit.domain.commands.NotifyChannelReady
import to.bitkit.domain.commands.NotifyChannelReadyHandler
import to.bitkit.domain.commands.NotifyPaymentReceived
import to.bitkit.domain.commands.NotifyPaymentReceivedHandler
import to.bitkit.domain.commands.NotifyPendingPaymentResolved
import to.bitkit.domain.commands.NotifyPendingPaymentResolvedHandler
import to.bitkit.ext.activityManager
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NotificationDetails
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.NodeEventHandler
import to.bitkit.services.NodeServiceFgState
import to.bitkit.ui.ID_NOTIFICATION_NODE
import to.bitkit.ui.MainActivity
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import to.bitkit.utils.jsonLogOf
import javax.inject.Inject

typealias Hook = (() -> Unit)?

@AndroidEntryPoint
class LightningNodeService : Service() {

    @Inject
    @UiDispatcher
    lateinit var uiDispatcher: CoroutineDispatcher

    private val serviceScope by lazy { appScope(uiDispatcher, TAG) }

    @Inject
    lateinit var lightningRepo: LightningRepo

    @Inject
    lateinit var walletRepo: WalletRepo

    @Inject
    lateinit var notifyPaymentReceivedHandler: NotifyPaymentReceivedHandler

    @Inject
    lateinit var notifyChannelReadyHandler: NotifyChannelReadyHandler

    @Inject
    lateinit var notifyPendingPaymentResolvedHandler: NotifyPendingPaymentResolvedHandler

    @Inject
    lateinit var cacheStore: CacheStore

    @Inject
    lateinit var appWidgetRefreshScheduler: AppWidgetRefreshScheduler

    @Inject
    lateinit var nodeServiceFgState: NodeServiceFgState

    private var hasStartedNode = false

    private val nodeEventHandler: NodeEventHandler = { event ->
        Logger.debug("LDK-node event received in $TAG: ${jsonLogOf(event)}", context = TAG)
        handlePaymentReceived(event)
        if (event is Event.ChannelReady) handleChannelReady(event)
        handlePendingPaymentResolved(event)
    }

    private fun setupService() {
        if (hasStartedNode) return
        hasStartedNode = true

        serviceScope.launch {
            lightningRepo.start(
                eventHandler = nodeEventHandler,
            ).onSuccess {
                walletRepo.setWalletExistsState()
                walletRepo.refreshBip21()
                walletRepo.syncBalances()
            }
        }
    }

    private suspend fun handlePaymentReceived(event: Event) {
        if (event !is Event.PaymentReceived && event !is Event.OnchainTransactionReceived) return
        val command = NotifyPaymentReceived.Command.from(event, includeNotification = true) ?: return

        notifyPaymentReceivedHandler(command).onSuccess { result ->
            Logger.debug("Handled payment notification with result '$result'", context = TAG)
            if (result !is NotifyPaymentReceived.Result.ShowNotification) return
            withContext(uiDispatcher) {
                notifyPaymentReceivedHandler.present(
                    command = command,
                    canPresent = { App.currentActivity?.value == null },
                ) {
                    showPaymentNotification(result.sheet, result.notification)
                }
            }
        }
    }

    private suspend fun handleChannelReady(event: Event.ChannelReady) {
        // When the app is in the foreground, AppViewModel handles this event and shows the receive
        // sheet. Running here would consume the CJIT dedup gate (insertActivityFromCjit) and then
        // skip the notification (foreground), leaving the in-app handler to see Duplicate and show
        // nothing — so defer entirely.
        if (App.currentActivity?.value != null) return

        val command = NotifyChannelReady.Command(event = event, includeNotification = true)
        notifyChannelReadyHandler(command).onSuccess {
            Logger.debug("Channel ready notification result: $it", context = TAG)
            if (it !is NotifyChannelReady.Result.ShowNotification) return
            showPaymentNotification(it.sheet, it.notification)
        }
    }

    private fun showPaymentNotification(
        sheet: NewTransactionSheetDetails,
        notification: NotificationDetails,
    ) {
        Logger.debug("Showing payment notification: ${notification.title}", context = TAG)
        serviceScope.launch { cacheStore.setBackgroundReceive(sheet) }
        pushNotification(notification.title, notification.body)
    }

    private suspend fun handlePendingPaymentResolved(event: Event) {
        val command = NotifyPendingPaymentResolved.Command.from(event) ?: return

        notifyPendingPaymentResolvedHandler(command).onSuccess {
            if (it !is NotifyPendingPaymentResolved.Result.ShowNotification) return
            if (App.currentActivity?.value != null) {
                Logger.debug("Skipping pending payment notification: activity is active", context = TAG)
                return
            }
            Logger.debug("Showing pending payment notification for '${command.paymentHash}'", context = TAG)
            pushNotification(it.notification.title, it.notification.body)
        }
    }

    private fun createNotification(
        contentText: String = getString(R.string.notification__service__body),
    ): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        // Create stop action that will close both service and app
        val stopIntent = Intent(this, LightningNodeService::class.java).apply {
            action = ACTION_STOP_SERVICE_AND_APP
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID_NODE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_bitkit_outlined)
            .setColor(ContextCompat.getColor(this, R.color.brand))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_x,
                getString(R.string.notification__service__stop),
                stopPendingIntent
            )
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Logger.debug("Received start command action '$action'", context = TAG)
        when (action) {
            ACTION_STOP_SERVICE_AND_APP -> {
                appWidgetRefreshScheduler.ensureScheduled(AppWidgetRefreshReason.SERVICE_STOP_ACTION)
                appWidgetRefreshScheduler.requestCatchUp(AppWidgetRefreshReason.SERVICE_STOP_ACTION)
                stopForegroundService(startId) { Logger.debug("Received stop service action", context = TAG) }
                activityManager.appTasks.forEach { it.finishAndRemoveTask() }
                serviceScope.launch { lightningRepo.stop() }
            }

            ACTION_START_SERVICE -> if (promoteToForeground(startId)) {
                nodeServiceFgState.setForegroundServiceRunning(true)
                setupService()
            }
            else -> stop(startId) { Logger.warn("Stopped service for unsupported action '$action'", context = TAG) }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(startId: Int): Boolean {
        return runCatching {
            ServiceCompat.startForeground(
                this,
                ID_NOTIFICATION_NODE,
                createNotification(),
                foregroundServiceTypeDataSync(),
            )
        }.fold(
            onSuccess = { true },
            onFailure = {
                if (it !is RuntimeException) throw it
                stop(startId) { Logger.error("Failed to promote foreground service", it, context = TAG) }
                false
            }
        )
    }

    private fun foregroundServiceTypeDataSync(): Int {
        return if (VERSION.SDK_INT >= VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
    }

    private fun stopForegroundService(startId: Int, hook: Hook = null) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stop(startId, hook)
    }

    private fun stop(startId: Int, hook: Hook = null) {
        hook?.invoke()
        stopSelf(startId)
    }

    override fun onDestroy() {
        Logger.debug("onDestroy", context = TAG)
        nodeServiceFgState.setForegroundServiceRunning(false)
        // Drop our event handler so it isn't retained by the repo singleton across service restarts.
        lightningRepo.removeEventHandler(nodeEventHandler)
        stopNodeIfBackgrounded()
        super.onDestroy()
    }

    @RequiresApi(VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Logger.warn("Reached foreground service timeout for type '$fgsType'", context = TAG)
        stopForegroundService(startId)
        stopNodeIfBackgrounded()
        super.onTimeout(startId, fgsType)
    }

    private fun stopNodeIfBackgrounded() {
        if (App.currentActivity?.value == null) {
            serviceScope.launch { lightningRepo.stop() }
        } else {
            Logger.debug("Skipping node stop: activity is active", context = TAG)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID_NODE = "bitkit_notification_channel_node"
        const val TAG = "LightningNodeService"
        const val ACTION_START_SERVICE = "to.bitkit.androidServices.action.START_SERVICE"
        const val ACTION_STOP_SERVICE_AND_APP = "to.bitkit.androidServices.action.STOP_SERVICE_AND_APP"
    }
}
