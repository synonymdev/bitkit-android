package to.bitkit.usecases

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.TransferType
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.MoveToSparkRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertTrue

class CloseChannelUseCaseTest : BaseUnitTest() {
    private val lightningRepo = mock<LightningRepo>()
    private val transferRepo = mock<TransferRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val moveToSparkRepo = mock<MoveToSparkRepo>()
    private val lightningState = MutableStateFlow(LightningState())
    private val channel = channel("channel_1", 50_000uL)
    private lateinit var sut: CloseChannelUseCase

    @Before
    fun setUp() {
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever { lightningRepo.closeChannel(any(), any(), anyOrNull()) }.thenReturn(Result.success(Unit))
        whenever {
            transferRepo.createTransfer(
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            )
        }.thenReturn(Result.success("transfer"))
        whenever { walletRepo.syncNodeAndWallet(anyOrNull()) }.thenReturn(Result.success(Unit))
        whenever { lightningRepo.listPaymentsOrNull() }.thenReturn(emptyList())
        whenever(moveToSparkRepo.moveHashes).thenReturn(flowOf(setOf("move")))
        sut = CloseChannelUseCase(testDispatcher, lightningRepo, transferRepo, walletRepo, moveToSparkRepo)
    }

    @Test
    fun `closing records the channel's transfer to Savings`() = test {
        assertTrue(sut(channel).isSuccess)

        verify(transferRepo).createTransfer(
            type = TransferType.COOP_CLOSE,
            amountSats = channel.amountOnClose.toLong(),
            channelId = "channel_1",
            fundingTxId = channel.fundingTxo?.txid,
        )
    }

    @Test
    fun `a failed close records no transfer`() = test {
        whenever(lightningRepo.closeChannel(any(), any(), anyOrNull())).thenReturn(Result.failure(Exception("peer")))

        assertTrue(sut(channel).isFailure)

        verify(transferRepo, never())
            .createTransfer(any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a move's close takes only the channels it emptied`() = test {
        val kept = channel("channel_2", 20_000uL)
        lightningState.value = LightningState(channels = persistentListOf(channel("channel_1", 0uL), kept))

        sut.closeEmptiedByMove(listOf(channel, kept), emptyList())

        verify(lightningRepo).closeChannel(eq(channel("channel_1", 0uL)), any(), anyOrNull())
        verify(lightningRepo, never()).closeChannel(eq(kept), any(), anyOrNull())
    }

    @Test
    fun `a move's close keeps a channel open whose limit only a payment in flight lowered`() = test {
        lightningState.value = LightningState(channels = persistentListOf(channel("channel_1", 0uL)))
        val inFlight = PaymentDetails(
            id = "hash",
            kind = PaymentKind.Bolt11(hash = "hash", preimage = null, secret = null, description = null, bolt11 = "ln"),
            amountMsat = 49_500_000uL,
            feePaidMsat = null,
            direction = PaymentDirection.OUTBOUND,
            status = PaymentStatus.PENDING,
            latestUpdateTimestamp = 0uL,
        )
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(inFlight))

        sut.closeEmptiedByMove(listOf(channel), emptyList())

        verify(lightningRepo, never()).closeChannel(any(), any(), anyOrNull())
    }

    @Test
    fun `a move's close keeps channels open while its payments cannot be read`() = test {
        lightningState.value = LightningState(channels = persistentListOf(channel("channel_1", 0uL)))
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(emptyList(), null)

        sut.closeEmptiedByMove(listOf(channel), emptyList())

        verify(lightningRepo, never()).closeChannel(any(), any(), anyOrNull())
    }

    @Test
    fun `a move's close keeps channels open when a payment changed while the channels were read`() = test {
        lightningState.value = LightningState(channels = persistentListOf(channel("channel_1", 0uL)))
        val failed = PaymentDetails(
            id = "late",
            kind = PaymentKind.Bolt11(hash = "late", preimage = null, secret = null, description = null, bolt11 = "ln"),
            amountMsat = 1_000_000uL,
            feePaidMsat = null,
            direction = PaymentDirection.OUTBOUND,
            status = PaymentStatus.FAILED,
            latestUpdateTimestamp = 1uL,
        )
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(emptyList(), listOf(failed))

        sut.closeEmptiedByMove(listOf(channel), emptyList())

        verify(lightningRepo, never()).closeChannel(any(), any(), anyOrNull())
    }

    @Test
    fun `a move's close keeps channels open after another send since the move started, not after the move's own`() =
        test {
            lightningState.value = LightningState(channels = persistentListOf(channel("channel_1", 0uL)))
            val other = send("other")
            whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(other))

            sut.closeEmptiedByMove(listOf(channel), emptyList())
            verify(lightningRepo, never()).closeChannel(any(), any(), anyOrNull())

            whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(send("move")))
            sut.closeEmptiedByMove(listOf(channel), emptyList())
            verify(lightningRepo).closeChannel(eq(channel("channel_1", 0uL)), any(), anyOrNull())
        }

    private fun send(id: String) = PaymentDetails(
        id = id,
        kind = PaymentKind.Bolt11(hash = id, preimage = null, secret = null, description = null, bolt11 = "ln"),
        amountMsat = 1_000_000uL,
        feePaidMsat = null,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = 1uL,
    )

    @Test
    fun `a move's close reads the channels from the node after a sync, not the stale state`() = test {
        lightningState.value = LightningState(channels = persistentListOf(channel))
        whenever(lightningRepo.syncState()).thenAnswer {
            lightningState.value = LightningState(channels = persistentListOf(channel("channel_1", 0uL)))
            Unit
        }

        sut.closeEmptiedByMove(listOf(channel), emptyList())

        inOrder(walletRepo, lightningRepo) {
            verify(walletRepo).syncNodeAndWallet(anyOrNull())
            verify(lightningRepo).syncState()
            verify(lightningRepo).closeChannel(eq(channel("channel_1", 0uL)), any(), anyOrNull())
        }
    }

    private fun channel(id: String, sendableSats: ULong) = createChannelDetails().copy(
        channelId = id,
        isUsable = true,
        isChannelReady = true,
        nextOutboundHtlcLimitMsat = sendableSats * 1000uL,
    )
}
