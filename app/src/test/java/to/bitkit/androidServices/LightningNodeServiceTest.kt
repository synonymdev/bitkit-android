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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import to.bitkit.App
import to.bitkit.CurrentActivity
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.test.BaseUnitTest
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class LightningNodeServiceTest : BaseUnitTest() {

    @get:Rule(order = 0)
    val mainDispatcherRule = coroutinesTestRule

    @get:Rule(order = 1)
    var hiltRule = HiltAndroidRule(this)

    @BindValue
    @JvmField
    val lightningRepo: LightningRepo = mock()

    @BindValue
    @JvmField
    val walletRepo: WalletRepo = mock()

    @BindValue
    @JvmField
    val ldkNodeEventBus: LdkNodeEventBus = mock()

    @BindValue
    @JvmField
    val settingsStore: SettingsStore = mock()

    @BindValue
    @JvmField
    val coreService: CoreService = mock()

    @BindValue
    @JvmField
    val activityRepo: ActivityRepo = mock()

    @BindValue
    @JvmField
    val currencyRepo: CurrencyRepo = mock()

    private val eventsFlow = MutableSharedFlow<Event>()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        runBlocking {
            hiltRule.inject()
            whenever(ldkNodeEventBus.events).thenReturn(eventsFlow)
            whenever(settingsStore.data).thenReturn(flowOf(SettingsData(showNotificationDetails = true)))
            whenever(lightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(
                Result.success(Unit)
            )
            whenever(lightningRepo.stop()).thenReturn(Result.success(Unit))

            // Mock CurrencyRepo to return a ConvertedAmount
            whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenReturn(
                Result.success(
                    ConvertedAmount(
                        value = BigDecimal("0.10"),
                        formatted = "0.10",
                        symbol = "$",
                        currency = "USD",
                        flag = "🇺🇸",
                        sats = 100L
                    )
                )
            )

            // Mock ActivityRepo for onchain
            whenever(activityRepo.shouldShowPaymentReceived(any(), any())).thenReturn(true)

            // Grant permissions for notifications
            val app = context as Application
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

            // Reset App.currentActivity to simulate background state
            App.currentActivity = CurrentActivity()
        }
    }

    @After
    fun tearDown() {
        NewTransactionSheetDetails.clear(context)
        App.currentActivity = null
    }

    @Test
    fun `payment received in background shows notification`() = test {
        val controller = Robolectric.buildService(LightningNodeService::class.java)
        controller.create().startCommand(0, 0)

        val event = Event.PaymentReceived(
            paymentId = "payment_id",
            paymentHash = "test_hash",
            amountMsat = 100000u,
            customRecords = emptyList()
        )

        eventsFlow.emit(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val paymentNotification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == context.getString(R.string.notification_received_title)
        }
        assertNotNull("Payment notification should be present", paymentNotification)

        val details = NewTransactionSheetDetails.load(context)
        assertNotNull(details)
        assertEquals("test_hash", details?.paymentHashOrTxId)
        assertEquals(100L, details?.sats)
    }

    @Test
    fun `payment received in foreground does nothing`() = test {
        // Simulate foreground by setting App.currentActivity.value via lifecycle callback
        val mockActivity: Activity = mock()
        App.currentActivity?.onActivityStarted(mockActivity)

        val controller = Robolectric.buildService(LightningNodeService::class.java)
        controller.create().startCommand(0, 0)

        val event = Event.PaymentReceived(
            paymentId = "payment_id_fg",
            paymentHash = "test_hash_fg",
            amountMsat = 200000u,
            customRecords = emptyList()
        )

        eventsFlow.emit(event)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val paymentNotification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == context.getString(R.string.notification_received_title)
        }

        assertNull("Payment notification should NOT be present in foreground", paymentNotification)

        val details = NewTransactionSheetDetails.load(context)
        assertNull(details)
    }

    @Test
    fun `notification body contains formatted amount with fiat`() = test {
        val controller = Robolectric.buildService(LightningNodeService::class.java)
        controller.create().startCommand(0, 0)

        val event = Event.PaymentReceived(
            paymentId = "payment_id",
            paymentHash = "test_hash",
            amountMsat = 100000u,
            customRecords = emptyList()
        )

        eventsFlow.emit(event)
        testScheduler.advanceUntilIdle()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadows = Shadows.shadowOf(notificationManager)

        val paymentNotification = shadows.allNotifications.find {
            it.extras.getString(Notification.EXTRA_TITLE) == context.getString(R.string.notification_received_title)
        }
        assertNotNull("Payment notification should be present", paymentNotification)

        val body = paymentNotification?.extras?.getString(Notification.EXTRA_TEXT)
        assertNotNull("Notification body should not be null", body)
        assertTrue("Notification body should contain fiat amount", body!!.contains("$"))
        assertTrue("Notification body should contain bitcoin symbol", body.contains("₿"))
    }
}
