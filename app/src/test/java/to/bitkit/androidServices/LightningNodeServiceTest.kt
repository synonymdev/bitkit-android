package to.bitkit.androidServices

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import to.bitkit.App
import to.bitkit.CurrentActivity
import to.bitkit.R
import to.bitkit.appwidget.AppWidgetRefreshReason
import to.bitkit.appwidget.AppWidgetRefreshScheduler
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.di.DbModule
import to.bitkit.di.DispatchersModule
import to.bitkit.di.ViewModelModule
import to.bitkit.domain.commands.NotifyChannelReady
import to.bitkit.domain.commands.NotifyChannelReadyHandler
import to.bitkit.domain.commands.NotifyPaymentReceived
import to.bitkit.domain.commands.NotifyPaymentReceivedHandler
import to.bitkit.domain.commands.NotifyPendingPaymentResolved
import to.bitkit.domain.commands.NotifyPendingPaymentResolvedHandler
import to.bitkit.ext.notificationManager
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.NotificationDetails
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.NodeEventHandler
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastQueueManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@HiltAndroidTest
@UninstallModules(DispatchersModule::class, DbModule::class, ViewModelModule::class)
@Config(application = HiltTestApplication::class, sdk = [34]) // Pin Robolectric to an SDK that supports Java 17
@RunWith(RobolectricTestRunner::class)
class LightningNodeServiceTest : BaseUnitTest() {
    companion object {
        private const val START_ID = 1
        private const val STOP_START_ID = 2
        private const val TIMEOUT_START_ID = 3
    }

    @get:Rule(order = 1)
    var hiltRule = HiltAndroidRule(this)

    @BindValue
    val firebaseMessaging = mock<FirebaseMessaging>()

    @BindValue
    val toastManagerProvider = mock<(CoroutineScope) -> ToastQueueManager>()

    @BindValue
    val lightningRepo = mock<LightningRepo>()

    @BindValue
    val walletRepo = mock<WalletRepo>()

    @BindValue
    val notifyPaymentReceivedHandler = mock<NotifyPaymentReceivedHandler>()

    @BindValue
    val notifyChannelReadyHandler = mock<NotifyChannelReadyHandler>()

    @BindValue
    val notifyPendingPaymentResolvedHandler = mock<NotifyPendingPaymentResolvedHandler>()

    @BindValue
    val cacheStore = mock<CacheStore>()

    @BindValue
    val appWidgetRefreshScheduler = mock<AppWidgetRefreshScheduler>()

    private var capturedHandler: NodeEventHandler? = null
    private val cacheData = MutableSharedFlow<AppCacheData>(replay = 1)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = runBlocking {
        hiltRule.inject()
        lightningRepo.stub {
            on {
                start(
                    any(),
                    anyOrNull(),
                    any(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    any(),
                )
            } doAnswer {
                capturedHandler = it.getArgument(5) as? NodeEventHandler
                Result.success(Unit)
            }
        }
        whenever(lightningRepo.stop()).thenReturn(Result.success(Unit))

        // Set up CacheStore mock
        whenever(cacheStore.data).thenReturn(cacheData)

        // Mock NotifyPaymentReceivedHandler to return ShowNotification result
        val sheet = NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED,
            paymentHashOrTxId = "test_hash",
            sats = 100L,
        )
        val notification = NotificationDetails(
            title = context.getString(R.string.notification__received__title),
            body = "Received ₿ 100 ($0.10)",
        )
        whenever(notifyPaymentReceivedHandler.invoke(any()))
            .thenReturn(Result.success(NotifyPaymentReceived.Result.ShowNotification(sheet, notification)))

        // Default: not a CJIT channel ready unless a test overrides it
        whenever(notifyChannelReadyHandler.invoke(any()))
            .thenReturn(Result.success(NotifyChannelReady.Result.Skip))

        // Grant permissions for notifications
        val app = context as Application
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // Reset App.currentActivity to simulate background state
        App.currentActivity = CurrentActivity()
    }

    @After
    fun tearDown() {
        App.currentActivity = null
    }

    @Test
    fun `start action promotes data sync foreground service and starts node`() = test {
        val service = createService()
        val result = service.onStartCommand(serviceIntent(), 0, START_ID)
        testScheduler.advanceUntilIdle()

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
        assertNotNull(Shadows.shadowOf(service).lastForegroundNotification)
        assertNotNull(capturedHandler, "Event handler should be captured")
    }

    @Test
    fun `start action only starts node once`() = test {
        val service = createService()
        service.onStartCommand(serviceIntent(), 0, START_ID)
        service.onStartCommand(serviceIntent(), 0, START_ID + 1)
        testScheduler.advanceUntilIdle()

        verifyLightningStart(count = 1)
    }

    @Test
    fun `null intent stops service without starting node`() = test {
        val service = createService()
        val result = service.onStartCommand(null, 0, START_ID)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(START_ID, Shadows.shadowOf(service).stopSelfId)
        assertNull(capturedHandler)
        verifyLightningNeverStarted()
    }

    @Test
    fun `unsupported action stops service without starting node`() = test {
        val service = createService()
        val result = service.onStartCommand(serviceIntent("unsupported"), 0, START_ID)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(START_ID, Shadows.shadowOf(service).stopSelfId)
        assertNull(capturedHandler)
        verifyLightningNeverStarted()
    }

    @Test
    fun `foreground promotion failure stops service without starting node`() = test {
        val service = createService()
        val shadowService = Shadows.shadowOf(service)
        shadowService.setThrowInStartForeground(RuntimeException("blocked"))

        val result = service.onStartCommand(serviceIntent(), 0, START_ID)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(START_ID, shadowService.stopSelfId)
        assertNull(capturedHandler)
        verifyLightningNeverStarted()
    }

    @Test
    fun `stop action stops service before node stop completes`() = test {
        val stopResult = CompletableDeferred<Result<Unit>>()
        whenever { lightningRepo.stop() }.doSuspendableAnswer { stopResult.await() }

        val service = startService()
        testScheduler.advanceUntilIdle()
        val shadowService = Shadows.shadowOf(service)

        val result = service.onStartCommand(
            serviceIntent(LightningNodeService.ACTION_STOP_SERVICE_AND_APP),
            0,
            STOP_START_ID,
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(shadowService.isStoppedBySelf)
        assertTrue(shadowService.isForegroundStopped)
        assertEquals(STOP_START_ID, shadowService.stopSelfId)

        stopResult.complete(Result.success(Unit))
        testScheduler.advanceUntilIdle()
    }

    @Test
    @Config(sdk = [35])
    fun `foreground service timeout stops service before node stop completes`() = test {
        val stopResult = CompletableDeferred<Result<Unit>>()
        whenever { lightningRepo.stop() }.doSuspendableAnswer { stopResult.await() }

        val service = startService()
        testScheduler.advanceUntilIdle()
        val shadowService = Shadows.shadowOf(service)

        service.onTimeout(TIMEOUT_START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(shadowService.isStoppedBySelf)
        assertTrue(shadowService.isForegroundStopped)
        assertEquals(TIMEOUT_START_ID, shadowService.stopSelfId)

        stopResult.complete(Result.success(Unit))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `payment received in background shows notification`() = test {
        startService()
        testScheduler.advanceUntilIdle()

        assertNotNull(capturedHandler, "Event handler should be captured")

        val event = Event.PaymentReceived(
            paymentId = "payment_id",
            paymentHash = "test_hash",
            amountMsat = 100000u,
            customRecords = emptyList()
        )

        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val paymentNotification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == context.getString(R.string.notification__received__title)
        }
        assertNotNull(paymentNotification, "Payment notification should be present")

        val expected = NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED,
            paymentHashOrTxId = "test_hash",
            sats = 100L,
        )
        verify(cacheStore).setBackgroundReceive(expected)
    }

    @Test
    fun `payment received in foreground does nothing`() = test {
        // Simulate foreground by setting App.currentActivity.value via lifecycle callback
        val mockActivity: Activity = mock()
        App.currentActivity?.onActivityStarted(mockActivity)

        startService()
        testScheduler.advanceUntilIdle()

        val event = Event.PaymentReceived(
            paymentId = "payment_id_fg",
            paymentHash = "test_hash_fg",
            amountMsat = 200000u,
            customRecords = emptyList()
        )

        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val paymentNotification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == context.getString(R.string.notification__received__title)
        }

        assertNull(paymentNotification, "Payment notification should NOT be present in foreground")

        verify(cacheStore, never()).setBackgroundReceive(any())
    }

    @Test
    fun `notification uses content from use case result`() = test {
        startService()
        testScheduler.advanceUntilIdle()

        val event = Event.PaymentReceived(
            paymentId = "payment_id",
            paymentHash = "test_hash",
            amountMsat = 100000u,
            customRecords = emptyList()
        )

        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val paymentNotification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == context.getString(R.string.notification__received__title)
        }
        assertNotNull(paymentNotification, "Payment notification should be present")

        val body = paymentNotification.extras?.getString(Notification.EXTRA_TEXT)
        assertEquals($$"Received ₿ 100 ($0.10)", body)
    }

    @Test
    fun `pending payment success in background shows notification`() = test {
        val sentTitle = context.getString(R.string.wallet__toast_payment_sent_title)
        val sentBody = context.getString(R.string.wallet__toast_payment_sent_description)
        whenever(notifyPendingPaymentResolvedHandler.invoke(any())).thenReturn(
            Result.success(
                NotifyPendingPaymentResolved.Result.ShowNotification(
                    NotificationDetails(title = sentTitle, body = sentBody)
                )
            )
        )

        startService()
        testScheduler.advanceUntilIdle()

        val event = Event.PaymentSuccessful(
            paymentId = "payment_id",
            paymentHash = "test_hash",
            paymentPreimage = "preimage",
            feePaidMsat = 10uL,
        )

        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val notification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == sentTitle
        }
        assertNotNull(notification, "Pending payment success notification should be present")
        assertEquals(sentBody, notification?.extras?.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `pending payment failure in background shows notification`() = test {
        val failedTitle = context.getString(R.string.wallet__toast_payment_failed_title)
        val failedBody = context.getString(R.string.wallet__toast_payment_failed_description)
        whenever(notifyPendingPaymentResolvedHandler.invoke(any())).thenReturn(
            Result.success(
                NotifyPendingPaymentResolved.Result.ShowNotification(
                    NotificationDetails(title = failedTitle, body = failedBody)
                )
            )
        )

        startService()
        testScheduler.advanceUntilIdle()

        val event = Event.PaymentFailed(
            paymentId = "payment_id",
            paymentHash = "test_hash",
            reason = null,
        )

        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val notification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == failedTitle
        }
        assertNotNull(notification, "Pending payment failure notification should be present")
        assertEquals(failedBody, notification?.extras?.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `pending payment success in foreground skips notification`() = test {
        val mockActivity: Activity = mock()
        App.currentActivity?.onActivityStarted(mockActivity)

        val sentTitle = context.getString(R.string.wallet__toast_payment_sent_title)
        whenever(notifyPendingPaymentResolvedHandler.invoke(any())).thenReturn(
            Result.success(
                NotifyPendingPaymentResolved.Result.ShowNotification(
                    NotificationDetails(title = sentTitle, body = "body")
                )
            )
        )

        startService()
        testScheduler.advanceUntilIdle()

        val event = Event.PaymentSuccessful(
            paymentId = "payment_id",
            paymentHash = "test_hash",
            paymentPreimage = "preimage",
            feePaidMsat = 10uL,
        )

        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val notification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == sentTitle
        }
        assertNull(notification, "Pending payment notification should NOT be present in foreground")
    }

    @Test
    fun `non-pending payment skips notification`() = test {
        whenever(notifyPendingPaymentResolvedHandler.invoke(any())).thenReturn(
            Result.success(NotifyPendingPaymentResolved.Result.Skip)
        )

        startService()
        testScheduler.advanceUntilIdle()

        val event = Event.PaymentSuccessful(
            paymentId = "payment_id",
            paymentHash = "non_pending_hash",
            paymentPreimage = "preimage",
            feePaidMsat = 10uL,
        )

        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val sentTitle = context.getString(R.string.wallet__toast_payment_sent_title)
        val notification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == sentTitle
        }
        assertNull(notification, "Non-pending payment should NOT trigger notification")
    }

    @Test
    fun `cjit channel ready in background shows notification`() = test {
        val cjitTitle = context.getString(R.string.notification__received__title)
        val sheet = NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED,
            sats = 48064L,
        )
        whenever(notifyChannelReadyHandler.invoke(any())).thenReturn(
            Result.success(
                NotifyChannelReady.Result.ShowNotification(
                    sheet = sheet,
                    notification = NotificationDetails(title = cjitTitle, body = $$"Received ₿ 48 064 ($30.79)"),
                )
            )
        )

        startService()
        testScheduler.advanceUntilIdle()

        val event = Event.ChannelReady(
            channelId = "channel-1",
            userChannelId = "u1",
            counterpartyNodeId = null,
            fundingTxo = null,
        )
        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)
        val notification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == cjitTitle
        }
        assertNotNull(notification, "CJIT channel ready notification should be present")
        assertEquals($$"Received ₿ 48 064 ($30.79)", notification?.extras?.getString(Notification.EXTRA_TEXT))
        verify(cacheStore).setBackgroundReceive(sheet)
    }

    @Test
    fun `non-cjit channel ready shows no notification`() = test {
        whenever(notifyChannelReadyHandler.invoke(any()))
            .thenReturn(Result.success(NotifyChannelReady.Result.Skip))

        startService()
        testScheduler.advanceUntilIdle()

        val event = Event.ChannelReady(
            channelId = "channel-1",
            userChannelId = "u1",
            counterpartyNodeId = null,
            fundingTxo = null,
        )
        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)
        val cjitTitle = context.getString(R.string.notification__received__title)
        val notification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == cjitTitle
        }
        assertNull(notification, "Non-CJIT channel ready should NOT trigger a notification")
        verify(cacheStore, never()).setBackgroundReceive(any())
    }

    @Test
    fun `duplicate cjit channel ready shows no notification`() = test {
        whenever(notifyChannelReadyHandler.invoke(any()))
            .thenReturn(Result.success(NotifyChannelReady.Result.Duplicate))

        startService()
        testScheduler.advanceUntilIdle()

        val event = Event.ChannelReady(
            channelId = "channel-1",
            userChannelId = "u1",
            counterpartyNodeId = null,
            fundingTxo = null,
        )
        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)
        val cjitTitle = context.getString(R.string.notification__received__title)
        val notification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == cjitTitle
        }
        assertNull(notification, "A duplicate CJIT channel ready should NOT trigger a notification")
        verify(cacheStore, never()).setBackgroundReceive(any())
    }

    @Test
    fun `cjit channel ready in foreground shows no notification`() = test {
        val mockActivity: Activity = mock()
        App.currentActivity?.onActivityStarted(mockActivity)

        val cjitTitle = context.getString(R.string.notification__received__title)
        whenever(notifyChannelReadyHandler.invoke(any())).thenReturn(
            Result.success(
                NotifyChannelReady.Result.ShowNotification(
                    sheet = NewTransactionSheetDetails(
                        type = NewTransactionSheetType.LIGHTNING,
                        direction = NewTransactionSheetDirection.RECEIVED,
                        sats = 48064L,
                    ),
                    notification = NotificationDetails(title = cjitTitle, body = "body"),
                )
            )
        )

        startService()
        testScheduler.advanceUntilIdle()

        val event = Event.ChannelReady(
            channelId = "channel-1",
            userChannelId = "u1",
            counterpartyNodeId = null,
            fundingTxo = null,
        )
        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.notificationManager
        val shadows = Shadows.shadowOf(notificationManager)
        val notification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == cjitTitle
        }
        assertNull(notification, "CJIT notification should NOT be present in foreground")
        // Defers to AppViewModel: must not invoke the handler, or it would consume the CJIT dedup gate
        verify(notifyChannelReadyHandler, never()).invoke(any())
    }

    @Test
    fun `stop service action schedules widget catch-up before shutdown`() = test {
        val service = createService()

        service.onStartCommand(
            serviceIntent(LightningNodeService.ACTION_STOP_SERVICE_AND_APP),
            0,
            STOP_START_ID,
        )
        testScheduler.advanceUntilIdle()

        inOrder(appWidgetRefreshScheduler, lightningRepo) {
            verify(appWidgetRefreshScheduler).ensureScheduled(AppWidgetRefreshReason.SERVICE_STOP_ACTION)
            verify(appWidgetRefreshScheduler).requestCatchUp(AppWidgetRefreshReason.SERVICE_STOP_ACTION)
            verify(lightningRepo).stop()
        }
    }

    private fun createService(): LightningNodeService {
        return Robolectric.buildService(LightningNodeService::class.java)
            .create()
            .get()
    }

    private fun startService(): LightningNodeService {
        val service = createService()
        service.onStartCommand(serviceIntent(), 0, START_ID)
        return service
    }

    private fun serviceIntent(action: String = LightningNodeService.ACTION_START_SERVICE): Intent {
        return Intent(context, LightningNodeService::class.java).apply {
            this.action = action
        }
    }

    private suspend fun verifyLightningStart(count: Int) {
        verify(lightningRepo, times(count)).start(
            any(),
            anyOrNull(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
        )
    }

    private suspend fun verifyLightningNeverStarted() {
        verify(lightningRepo, never()).start(
            any(),
            anyOrNull(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
        )
    }
}
