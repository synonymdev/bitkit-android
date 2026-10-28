package to.bitkit.ui.settings.lightning

import android.content.Context
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CloseConnectionViewModelTest : BaseUnitTest() {
    private val context = mock<Context>()
    private val lightningRepo = mock<LightningRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val transferRepo = mock<TransferRepo>()

    @Test
    fun `force close request and cancel never close a channel`() = test {
        val sut = CloseConnectionViewModel(context, lightningRepo, walletRepo, transferRepo)

        sut.requestForceClose()
        assertTrue(sut.uiState.value.showForceCloseConfirmation)
        sut.cancelForceClose()
        sut.confirmForceClose("channel")
        advanceUntilIdle()

        assertFalse(sut.uiState.value.showForceCloseConfirmation)
        verify(lightningRepo, never()).closeChannel(any(), any(), any())
    }

    @Test
    fun `confirmed force close does not exclude the selected primary channel`() = test {
        val channel = createChannelDetails()
        whenever(context.getString(any())).thenReturn("Closed")
        whenever(lightningRepo.lightningState).thenReturn(
            MutableStateFlow(LightningState(channels = persistentListOf(channel)))
        )
        whenever(lightningRepo.closeChannel(channel, force = true)).thenReturn(Result.success(Unit))
        whenever(transferRepo.createTransfer(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Result.success("transfer"))
        whenever(walletRepo.syncNodeAndWallet()).thenReturn(Result.success(Unit))
        val sut = CloseConnectionViewModel(context, lightningRepo, walletRepo, transferRepo)

        sut.requestForceClose()
        sut.confirmForceClose(channel.channelId)
        advanceUntilIdle()

        verify(lightningRepo).closeChannel(channel, force = true)
        verify(transferRepo).createTransfer(
            type = TransferType.FORCE_CLOSE,
            amountSats = channel.amountOnClose.toLong(),
            channelId = channel.channelId,
            fundingTxId = channel.fundingTxo?.txid,
        )
        assertTrue(sut.uiState.value.closeSuccess)
    }
}
