package to.bitkit.androidServices

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import to.bitkit.App
import to.bitkit.CurrentActivity
import to.bitkit.R
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.di.DbModule
import to.bitkit.di.DispatchersModule
import to.bitkit.di.ViewModelModule
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

@HiltAndroidTest
@UninstallModules(DispatchersModule::class, DbModule::class, ViewModelModule::class)
@Config(application = HiltTestApplication::class, sdk = [34]) // Pin Robolectric to an SDK that supports Java 17
@RunWith(RobolectricTestRunner::class)
class LightningNodeServiceTest : BaseUnitTest() {

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
    val notifyPendingPaymentResolvedHandler = mock<NotifyPendingPaymentResolvedHandler>()

    @BindValue
    val cacheStore = mock<CacheStore>()

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
    fun `payment received in background shows notification`() = test {
        val controller = Robolectric.buildService(LightningNodeService::class.java)
        controller.create().startCommand(0, 0)
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

        val controller = Robolectric.buildService(LightningNodeService::class.java)
        controller.create().startCommand(0, 0)
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
        val controller = Robolectric.buildService(LightningNodeService::class.java)
        controller.create().startCommand(0, 0)
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

        val controller = Robolectric.buildService(LightningNodeService::class.java)
        controller.create().startCommand(0, 0)
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

        val controller = Robolectric.buildService(LightningNodeService::class.java)
        controller.create().startCommand(0, 0)
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

        val controller = Robolectric.buildService(LightningNodeService::class.java)
        controller.create().startCommand(0, 0)
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

        val controller = Robolectric.buildService(LightningNodeService::class.java)
        controller.create().startCommand(0, 0)
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
}
