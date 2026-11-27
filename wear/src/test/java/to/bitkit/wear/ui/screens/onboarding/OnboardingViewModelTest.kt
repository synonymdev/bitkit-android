package to.bitkit.wear.ui.screens.onboarding

import app.cash.turbine.test
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertEquals

class OnboardingViewModelTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()
    private val sut = OnboardingViewModel(walletRepo)

    @Test
    fun `coming on screen without a wallet stays on onboarding`() = test {
        whenever(walletRepo.hasWallet()).thenReturn(false)

        sut.effects.test {
            sut.onResume()

            expectNoEvents()
        }
    }

    @Test
    fun `coming on screen with a wallet stored leaves for it`() = test {
        whenever(walletRepo.hasWallet()).thenReturn(true)

        sut.effects.test {
            sut.onResume()

            assertEquals(OnboardingEffect.WalletFound, awaitItem())
        }
    }
}
