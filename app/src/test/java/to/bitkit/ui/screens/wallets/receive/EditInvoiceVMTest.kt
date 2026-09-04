package to.bitkit.ui.screens.wallets.receive

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.models.ReceiveAdditionalLiquidityAction
import to.bitkit.models.ReceiveLiquiditySource
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.BlocktankState
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.wallets.receive.EditInvoiceVM.EditInvoiceScreenEffects
import kotlin.test.assertEquals

class EditInvoiceVMTest : BaseUnitTest() {

    private lateinit var sut: EditInvoiceVM
    private val walletRepo: WalletRepo = mock()
    private val blocktankRepo: BlocktankRepo = mock()

    @Before
    fun setUp() {
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState(minCjitSats = 5_000)))
        whenever(walletRepo.inboundLiquiditySats()).thenReturn(1_000u)
        sut = EditInvoiceVM(walletRepo, blocktankRepo)
    }

    @Test
    fun `onClickContinue should emit none for auto when amount exceeds inbound`() = test {
        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.AUTO,
                amountSats = 10_000u,
                isGeoBlocked = false,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(ReceiveAdditionalLiquidityAction.None),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onClickContinue should emit choose amount for spending below CJIT minimum`() = test {
        whenever(blocktankRepo.maxCjitAmountSats()).thenReturn(Result.success(100_000u))

        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.SPENDING,
                amountSats = 4_000u,
                isGeoBlocked = false,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(ReceiveAdditionalLiquidityAction.ChooseAmount),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onClickContinue should emit create CJIT for spending amount within limits`() = test {
        whenever(blocktankRepo.maxCjitAmountSats()).thenReturn(Result.success(100_000u))

        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.SPENDING,
                amountSats = 10_000u,
                isGeoBlocked = false,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(
                    ReceiveAdditionalLiquidityAction.CreateCjit(10_000u)
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onClickContinue should emit geo blocked without fetching CJIT limits`() = test {
        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.SPENDING,
                amountSats = 10_000u,
                isGeoBlocked = true,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(ReceiveAdditionalLiquidityAction.GeoBlocked),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
