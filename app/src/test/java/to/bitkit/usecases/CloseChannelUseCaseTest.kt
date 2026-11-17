package to.bitkit.usecases

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.TransferType
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertTrue

class CloseChannelUseCaseTest : BaseUnitTest() {
    private val lightningRepo = mock<LightningRepo>()
    private val transferRepo = mock<TransferRepo>()
    private val walletRepo = mock<WalletRepo>()
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
        sut = CloseChannelUseCase(testDispatcher, lightningRepo, transferRepo, walletRepo)
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

        sut.closeEmptiedByMove(listOf(channel, kept))

        verify(lightningRepo).closeChannel(eq(channel("channel_1", 0uL)), any(), anyOrNull())
        verify(lightningRepo, never()).closeChannel(eq(kept), any(), anyOrNull())
    }

    private fun channel(id: String, sendableSats: ULong) = createChannelDetails().copy(
        channelId = id,
        isUsable = true,
        isChannelReady = true,
        nextOutboundHtlcLimitMsat = sendableSats * 1000uL,
    )
}
