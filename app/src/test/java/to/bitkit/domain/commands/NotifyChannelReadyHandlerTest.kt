package to.bitkit.domain.commands

import android.content.Context
import com.synonym.bitkitcore.IcJitEntry
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.createChannelDetails
import to.bitkit.ext.mock
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.test.BaseUnitTest
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotifyChannelReadyHandlerTest : BaseUnitTest() {

    private val context: Context = mock()
    private val lightningRepo: LightningRepo = mock()
    private val blocktankRepo: BlocktankRepo = mock()
    private val activityRepo: ActivityRepo = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val settingsStore: SettingsStore = mock()

    private lateinit var sut: NotifyChannelReadyHandler

    @Before
    fun setUp() {
        whenever(context.getString(R.string.notification__received__title)).thenReturn("Payment Received")
        whenever(context.getString(any(), any())).thenReturn("Received amount")
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenReturn(
            Result.success(
                ConvertedAmount(
                    value = BigDecimal("0.10"),
                    formatted = "0.10",
                    symbol = "$",
                    currency = "USD",
                    flag = "\uD83C\uDDFA\uD83C\uDDF8",
                    sats = 100L
                )
            )
        )

        sut = NotifyChannelReadyHandler(
            ioDispatcher = testDispatcher,
            lightningRepo = lightningRepo,
            blocktankRepo = blocktankRepo,
            activityRepo = activityRepo,
            receivedNotificationContent = ReceivedNotificationContent(
                context = context,
                currencyRepo = currencyRepo,
                settingsStore = settingsStore,
            ),
        )
    }

    @Test
    fun `returns Skip when channel is not found`() = test {
        val event = mock<Event.ChannelReady> {
            on { channelId } doReturn "unknown-channel"
        }
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())

        val result = sut(NotifyChannelReady.Command(event = event))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() is NotifyChannelReady.Result.Skip)
        verify(activityRepo, never()).insertActivityFromCjit(any(), any())
    }

    @Test
    fun `returns Skip when channel has no cjit entry`() = test {
        val event = mock<Event.ChannelReady> {
            on { channelId } doReturn "channel-1"
        }
        val channel = createChannelDetails().copy(channelId = "channel-1")
        whenever(lightningRepo.getChannels()).thenReturn(listOf(channel))
        whenever(blocktankRepo.getCjitEntry(channel)).thenReturn(null)

        val result = sut(NotifyChannelReady.Command(event = event))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() is NotifyChannelReady.Result.Skip)
        verify(activityRepo, never()).insertActivityFromCjit(any(), any())
    }

    @Test
    fun `returns ShowSheet for cjit channel`() = test {
        val event = mock<Event.ChannelReady> {
            on { channelId } doReturn "channel-1"
        }
        val channel = createChannelDetails().copy(
            channelId = "channel-1",
            outboundCapacityMsat = 3000_000u,
            unspendablePunishmentReserve = 263u,
        )
        val cjitEntry = IcJitEntry.mock()
        whenever(lightningRepo.getChannels()).thenReturn(listOf(channel))
        whenever(blocktankRepo.getCjitEntry(channel)).thenReturn(cjitEntry)
        whenever(activityRepo.insertActivityFromCjit(any(), any())).thenReturn(Result.success(true))

        val result = sut(NotifyChannelReady.Command(event = event))

        assertTrue(result.isSuccess)
        val showSheet = result.getOrThrow()
        assertTrue(showSheet is NotifyChannelReady.Result.ShowSheet)
        assertEquals(NewTransactionSheetType.LIGHTNING, showSheet.sheet.type)
        assertEquals(NewTransactionSheetDirection.RECEIVED, showSheet.sheet.direction)
        assertEquals(3263L, showSheet.sheet.sats)
        verify(activityRepo).insertActivityFromCjit(cjitEntry, channel)
    }

    @Test
    fun `returns ShowNotification when includeNotification is true`() = test {
        val event = mock<Event.ChannelReady> {
            on { channelId } doReturn "channel-1"
        }
        val channel = createChannelDetails().copy(
            channelId = "channel-1",
            outboundCapacityMsat = 3000_000u,
            unspendablePunishmentReserve = 263u,
        )
        val cjitEntry = IcJitEntry.mock()
        whenever(lightningRepo.getChannels()).thenReturn(listOf(channel))
        whenever(blocktankRepo.getCjitEntry(channel)).thenReturn(cjitEntry)
        whenever(activityRepo.insertActivityFromCjit(any(), any())).thenReturn(Result.success(true))

        val result = sut(NotifyChannelReady.Command(event = event, includeNotification = true))

        assertTrue(result.isSuccess)
        val showNotification = result.getOrThrow()
        assertTrue(showNotification is NotifyChannelReady.Result.ShowNotification)
        assertEquals(NewTransactionSheetType.LIGHTNING, showNotification.sheet.type)
        assertEquals(3263L, showNotification.sheet.sats)
        assertNotNull(showNotification.notification)
        assertEquals("Payment Received", showNotification.notification.title)
        verify(activityRepo).insertActivityFromCjit(cjitEntry, channel)
    }

    @Test
    fun `returns Duplicate when activity already exists`() = test {
        val event = mock<Event.ChannelReady> {
            on { channelId } doReturn "channel-1"
        }
        val channel = createChannelDetails().copy(
            channelId = "channel-1",
            outboundCapacityMsat = 3000_000u,
        )
        val cjitEntry = IcJitEntry.mock()
        whenever(lightningRepo.getChannels()).thenReturn(listOf(channel))
        whenever(blocktankRepo.getCjitEntry(channel)).thenReturn(cjitEntry)
        whenever(activityRepo.insertActivityFromCjit(any(), any())).thenReturn(Result.success(false))

        val result = sut(NotifyChannelReady.Command(event = event, includeNotification = true))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() is NotifyChannelReady.Result.Duplicate)
        verify(activityRepo).insertActivityFromCjit(cjitEntry, channel)
    }

    @Test
    fun `returns Duplicate when activity insert fails`() = test {
        val event = mock<Event.ChannelReady> {
            on { channelId } doReturn "channel-1"
        }
        val channel = createChannelDetails().copy(
            channelId = "channel-1",
            outboundCapacityMsat = 3000_000u,
        )
        val cjitEntry = IcJitEntry.mock()
        whenever(lightningRepo.getChannels()).thenReturn(listOf(channel))
        whenever(blocktankRepo.getCjitEntry(channel)).thenReturn(cjitEntry)
        whenever(activityRepo.insertActivityFromCjit(any(), any()))
            .thenReturn(Result.failure(RuntimeException("UNIQUE constraint failed")))

        val result = sut(NotifyChannelReady.Command(event = event, includeNotification = true))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() is NotifyChannelReady.Result.Duplicate)
    }

    @Test
    fun `returns Skip when channels list is null`() = test {
        val event = mock<Event.ChannelReady> {
            on { channelId } doReturn "channel-1"
        }
        whenever(lightningRepo.getChannels()).thenReturn(null)

        val result = sut(NotifyChannelReady.Command(event = event))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() is NotifyChannelReady.Result.Skip)
    }
}
