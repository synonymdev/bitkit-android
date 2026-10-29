package to.bitkit.ui.screens.wallets.receive

import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.ReceiveAdditionalLiquidityAction
import to.bitkit.models.ReceiveLiquiditySource
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.wallets.receive.EditInvoiceVM.EditInvoiceScreenEffects
import kotlin.test.assertEquals

class EditInvoiceVMTest : BaseUnitTest() {

    private lateinit var sut: EditInvoiceVM
    private val walletRepo: WalletRepo = mock()

    @Before
    fun setUp() = runBlocking {
        whenever(walletRepo.inboundLiquiditySats()).thenReturn(1_000u)
        sut = EditInvoiceVM(walletRepo, mock())
    }

    @Test
    fun `onClickContinue should emit none for auto when amount exceeds inbound`() = test {
        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.AUTO,
                amountSats = 10_000u,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(ReceiveAdditionalLiquidityAction.None),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        verify(walletRepo, times(1)).inboundLiquiditySats()
    }

    @Test
    fun `onClickContinue should emit transfer unavailable for spending above inbound`() = test {
        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.SPENDING,
                amountSats = 10_000u,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(
                    ReceiveAdditionalLiquidityAction.TransferUnavailable
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onClickContinue should emit none for spending within inbound`() = test {
        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.SPENDING,
                amountSats = 500u,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(ReceiveAdditionalLiquidityAction.None),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
