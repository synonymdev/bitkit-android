package to.bitkit.ui.screens.wallets.receive

import app.cash.turbine.test
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.wallets.receive.EditInvoiceVM.EditInvoiceScreenEffects
import kotlin.test.assertEquals

class EditInvoiceVMTest : BaseUnitTest() {

    private lateinit var sut: EditInvoiceVM
    private val walletRepo: WalletRepo = mock()

    @Before
    fun setUp() {
        sut = EditInvoiceVM(walletRepo)
    }

    @Test
    fun `onClickContinue should emit NavigateAddLiquidity when shouldRequestAdditionalLiquidity returns true`() = test {
        // Given
        whenever(walletRepo.shouldRequestAdditionalLiquidity()).thenReturn(Result.success(true))

        // When & Then
        sut.editInvoiceEffect.test {
            sut.onClickContinue()

            assertEquals(EditInvoiceScreenEffects.NavigateAddLiquidity, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(walletRepo).shouldRequestAdditionalLiquidity()
    }

    @Test
    fun `onClickContinue should emit UpdateInvoice when shouldRequestAdditionalLiquidity returns false`() = test {
        // Given
        whenever(walletRepo.shouldRequestAdditionalLiquidity()).thenReturn(Result.success(false))

        // When & Then
        sut.editInvoiceEffect.test {
            sut.onClickContinue()

            assertEquals(EditInvoiceScreenEffects.UpdateInvoice, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(walletRepo).shouldRequestAdditionalLiquidity()
    }
    @Test
    fun `onClickContinue should emit UpdateInvoice when shouldRequestAdditionalLiquidity fails`() = test {
        // Given
        whenever(walletRepo.shouldRequestAdditionalLiquidity()).thenReturn(Result.failure<Boolean>(Exception("Error")))

        // When & Then
        sut.editInvoiceEffect.test {
            sut.onClickContinue()

            assertEquals(EditInvoiceScreenEffects.UpdateInvoice, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(walletRepo).shouldRequestAdditionalLiquidity()
    }
}
