package to.bitkit.fcm

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.synonym.bitkitcore.IcJitEntry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import to.bitkit.App
import to.bitkit.CurrentActivity
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.domain.commands.ReceivedNotificationContent
import to.bitkit.ext.createChannelDetails
import to.bitkit.ext.mock
import to.bitkit.ext.notificationManager
import to.bitkit.models.BlocktankNotificationType
import to.bitkit.models.NotificationDetails
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.NodeEventHandler
import to.bitkit.services.NodeServiceFgState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class WakeNodeWorkerTest : BaseUnitTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val workerParams = mock<WorkerParameters>()
    private val lightningRepo = mock<LightningRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val cacheStore = mock<CacheStore>()
    private val receivedNotificationContent = mock<ReceivedNotificationContent>()
    private val nodeServiceFgState = NodeServiceFgState()

    private val channelId = "channel-1"
    private val receivedTitle by lazy { context.getString(R.string.notification__received__title) }

    @Before
    fun setUp() {
        whenever(workerParams.inputData).thenReturn(
            workDataOf("type" to BlocktankNotificationType.cjitPaymentArrived.name),
        )

        val app = context as Application
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // Default: app killed (no foreground activity); nodeServiceFgState defaults to not-running
        App.currentActivity = CurrentActivity()
    }

    @After
    fun tearDown() {
        App.currentActivity = null
    }

    @Test
    fun `cjit channel ready delivers rich notification content with fiat`() = test {
        val body = $$"Received ₿ 48 064 ($30.79)"
        whenever(receivedNotificationContent.build(48_064L)).thenReturn(NotificationDetails(receivedTitle, body))
        val channel = cjitChannel(sats = 48_064)
        stubChannel(channel, cjitEntry = IcJitEntry.mock())
        stubStartFiring(channelReadyEvent())

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val notification = findNotificationByTitle(receivedTitle)
        assertNotNull(notification, "CJIT notification should be delivered when app is killed")
        assertEquals(body, notification?.extras?.getString(Notification.EXTRA_TEXT))
        verify(activityRepo).insertActivityFromCjit(any(), any())
    }

    @Test
    fun `non-cjit channel ready delivers no payment notification`() = test {
        val channel = cjitChannel(sats = 45_000)
        stubChannel(channel, cjitEntry = null)
        stubStartFiring(channelReadyEvent())

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(findNotificationByTitle(receivedTitle), "A non-CJIT channel must not show a payment notification")
        verify(activityRepo, never()).insertActivityFromCjit(any(), any())
        verify(cacheStore, never()).setBackgroundReceive(any())
    }

    @Test
    fun `cjit channel ready skips notification when foreground service is running`() = test {
        nodeServiceFgState.setForegroundServiceRunning(true)
        val channel = cjitChannel(sats = 48_064)
        stubChannel(channel, cjitEntry = IcJitEntry.mock())
        stubStartFiring(channelReadyEvent())

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(findNotificationByTitle(receivedTitle), "Deduped when the foreground service handles it")
        // Defers entirely: the in-process handler owns the insert, so the worker must not win the dedup race
        verify(activityRepo, never()).insertActivityFromCjit(any(), any())
        verify(cacheStore, never()).setBackgroundReceive(any())
    }

    @Test
    fun `cjit channel ready skips notification when activity already exists`() = test {
        val channel = cjitChannel(sats = 48_064)
        stubChannel(channel, cjitEntry = IcJitEntry.mock())
        whenever(activityRepo.insertActivityFromCjit(any(), any())).thenReturn(Result.success(false))
        stubStartFiring(channelReadyEvent())

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(findNotificationByTitle(receivedTitle), "A duplicate CJIT channel ready must not notify again")
        verify(cacheStore, never()).setBackgroundReceive(any())
    }

    @Test
    fun `cjit channel ready skips notification when app is in foreground`() = test {
        App.currentActivity?.onActivityStarted(mock<Activity>())
        val channel = cjitChannel(sats = 48_064)
        stubChannel(channel, cjitEntry = IcJitEntry.mock())
        stubStartFiring(channelReadyEvent())

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(findNotificationByTitle(receivedTitle), "Notification is deduped when the in-app UI handles it")
        // Defers entirely: the in-app handler owns the insert, so the worker must not win the dedup race
        verify(activityRepo, never()).insertActivityFromCjit(any(), any())
        verify(cacheStore, never()).setBackgroundReceive(any())
    }

    @Test
    fun `payment received delivers rich notification content with fiat`() = test {
        val title = context.getString(R.string.notification__received__title)
        val body = $$"Received ₿ 48 064 ($30.79)"
        whenever(receivedNotificationContent.build(48_064L)).thenReturn(NotificationDetails(title, body))
        whenever(workerParams.inputData).thenReturn(
            workDataOf("type" to BlocktankNotificationType.incomingHtlc.name),
        )
        whenever(lightningRepo.stop()).thenReturn(Result.success(Unit))
        stubStartFiring(paymentReceivedEvent(sats = 48_064))

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val notification = findNotificationByTitle(title)
        assertNotNull(notification, "Payment notification should be delivered when app is killed")
        assertEquals(body, notification?.extras?.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `payment received skips notification when foreground service is running`() = test {
        nodeServiceFgState.setForegroundServiceRunning(true)
        whenever(workerParams.inputData).thenReturn(
            workDataOf("type" to BlocktankNotificationType.incomingHtlc.name),
        )
        whenever(lightningRepo.stop()).thenReturn(Result.success(Unit))
        stubStartFiring(paymentReceivedEvent(sats = 48_064))

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(
            findNotificationByTitle(context.getString(R.string.notification__received__title)),
            "Notification is deduped when the foreground service handles it",
        )
        // Receive is still cached for the in-app UI to pick up
        verify(cacheStore).setBackgroundReceive(any())
    }

    private fun worker() = WakeNodeWorker(
        appContext = context,
        workerParams = workerParams,
        lightningRepo = lightningRepo,
        blocktankRepo = blocktankRepo,
        activityRepo = activityRepo,
        cacheStore = cacheStore,
        receivedNotificationContent = receivedNotificationContent,
        nodeServiceFgState = nodeServiceFgState,
    )

    private fun channelReadyEvent() = mock<Event.ChannelReady> {
        on { this.channelId } doReturn this@WakeNodeWorkerTest.channelId
    }

    private fun paymentReceivedEvent(sats: Long) = Event.PaymentReceived(
        paymentId = "payment-1",
        paymentHash = "hash-1",
        amountMsat = (sats * 1000).toULong(),
        customRecords = emptyList(),
    )

    private fun cjitChannel(sats: Long) = createChannelDetails().copy(
        channelId = channelId,
        outboundCapacityMsat = (sats * 1000).toULong(),
        unspendablePunishmentReserve = 0u,
    )

    private suspend fun stubChannel(channel: ChannelDetails, cjitEntry: IcJitEntry?) {
        whenever(lightningRepo.getChannels()).thenReturn(listOf(channel))
        whenever(blocktankRepo.getCjitEntry(channel)).thenReturn(cjitEntry)
        whenever(activityRepo.insertActivityFromCjit(any(), any())).thenReturn(Result.success(true))
        whenever(lightningRepo.stop()).thenReturn(Result.success(Unit))
    }

    private fun stubStartFiring(event: Event) {
        whenever {
            lightningRepo.start(
                any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any(),
            )
        }.doSuspendableAnswer {
            val handler = it.getArgument<NodeEventHandler?>(5)
            handler?.invoke(event)
            Result.success(Unit)
        }
    }

    private fun findNotificationByTitle(title: String): Notification? {
        val shadows = Shadows.shadowOf(context.notificationManager)
        return shadows.allNotifications.find { it.extras.getString(Notification.EXTRA_TITLE) == title }
    }
}
