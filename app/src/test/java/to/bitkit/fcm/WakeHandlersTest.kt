package to.bitkit.fcm

import com.synonym.bitkitcore.WakeAckOutcome
import com.synonym.bitkitcore.WakeEnvelope
import dagger.Lazy
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.repositories.PubkySwapWakeUnclaimedException
import to.bitkit.repositories.PubkySwapsRouteRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class WakeHandlersTest : BaseUnitTest() {
    private val pubkySwapsRouteRepo = mock<PubkySwapsRouteRepo>()

    private val sut = WakeHandlers(
        alertHandler = mock(),
        swapClaimableHandler = SwapClaimableWakeHandler(Lazy { pubkySwapsRouteRepo }),
    )

    @Test
    fun `a swap claimable wake whose claims went out is handled`() = test {
        whenever(pubkySwapsRouteRepo.claimAfterWake()).thenReturn(Result.success(Unit))

        assertEquals(WakeAckOutcome.HANDLED, sut.handle(wake(WakeHandlers.SWAP_CLAIMABLE_TOPIC)))
    }

    @Test
    fun `a swap claimable wake after which no claim went out failed`() = test {
        whenever(pubkySwapsRouteRepo.claimAfterWake())
            .thenReturn(Result.failure(PubkySwapWakeUnclaimedException(listOf("swap"))))

        assertEquals(WakeAckOutcome.FAILED, sut.handle(wake(WakeHandlers.SWAP_CLAIMABLE_TOPIC)))
    }

    @Test
    fun `a wake of a topic the app does not handle gets no handler`() = test {
        assertEquals(WakeAckOutcome.NO_HANDLER, sut.handle(wake("paykit.sync")))
    }

    private fun wake(topic: String) = WakeEnvelope(
        version = 1u,
        id = "wake-1",
        topic = topic,
        urgency = "background",
        deadline = null,
        payloadJson = null,
        createdAt = null,
        fallback = false,
    )
}
