package to.bitkit.wear.ui.screens.removed

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertEquals

class WalletRemovedViewModelTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()
    private val sut = WalletRemovedViewModel(walletRepo)

    @Test
    fun `confirming leaves the screen only once the acknowledgement is stored`() = test {
        val stored = CompletableDeferred<Unit>()
        whenever(walletRepo.acknowledgeRemoval()).doSuspendableAnswer {
            stored.await()
            Result.success(Unit)
        }

        sut.effects.test {
            sut.onConfirm()
            sut.onConfirm()
            expectNoEvents()

            stored.complete(Unit)
            assertEquals(WalletRemovedEffect.Acknowledged, awaitItem())
        }
        verify(walletRepo, times(1)).acknowledgeRemoval()
    }
}
