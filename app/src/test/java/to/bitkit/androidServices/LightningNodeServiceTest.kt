package to.bitkit.androidServices

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.KArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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
import to.bitkit.domain.commands.NotifyPaymentReceived
import to.bitkit.domain.commands.NotifyPaymentReceivedHandler
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.NotificationDetails
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.NodeEventHandler
import to.bitkit.test.BaseUnitTest

@HiltAndroidTest
@UninstallModules(DispatchersModule::class, DbModule::class)
@Config(application = HiltTestApplication::class, sdk = [34]) // Pin Robolectric to an SDK that supports Java 17
@RunWith(RobolectricTestRunner::class)
class LightningNodeServiceTest : BaseUnitTest() {

    @get:Rule(order = 1)
    var hiltRule = HiltAndroidRule(this)

    @BindValue
    val lightningRepo = mock<LightningRepo>()

    @BindValue
    val walletRepo = mock<WalletRepo>()

    @BindValue
    val notifyPaymentReceivedHandler = mock<NotifyPaymentReceivedHandler>()

    @BindValue
    val cacheStore = mock<CacheStore>()

    private val captor: KArgumentCaptor<NodeEventHandler?> = argumentCaptor()
    private val cacheDataFlow = MutableSharedFlow<AppCacheData>(replay = 1)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = runBlocking {
        hiltRule.inject()
        whenever(lightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), captor.capture()))
            .thenReturn(Result.success(Unit))
        whenever(lightningRepo.stop()).thenReturn(Result.success(Unit))

        // Set up CacheStore mock
        whenever(cacheStore.data).thenReturn(cacheDataFlow)

        // Mock NotifyPaymentReceivedHandler to return ShowNotification result
        val sheet = NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED,
            paymentHashOrTxId = "test_hash",
            sats = 100L,
        )
        val notification = NotificationDetails(
            title = context.getString(R.string.notification_received_title),
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

        val capturedHandler = captor.lastValue
        assertNotNull("Event handler should be captured", capturedHandler)

        val event = Event.PaymentReceived(
            paymentId = "payment_id",
            paymentHash = "test_hash",
            amountMsat = 100000u,
            customRecords = emptyList()
        )

        capturedHandler?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val paymentNotification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == context.getString(R.string.notification_received_title)
        }
        assertNotNull("Payment notification should be present", paymentNotification)

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

        captor.lastValue?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val paymentNotification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == context.getString(R.string.notification_received_title)
        }

        assertNull("Payment notification should NOT be present in foreground", paymentNotification)

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

        captor.lastValue?.invoke(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val paymentNotification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == context.getString(R.string.notification_received_title)
        }
        assertNotNull("Payment notification should be present", paymentNotification)

        val body = paymentNotification?.extras?.getString(Notification.EXTRA_TEXT)
        assertEquals($$"Received ₿ 100 ($0.10)", body)
    }
}
