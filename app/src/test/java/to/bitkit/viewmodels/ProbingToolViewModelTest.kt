package to.bitkit.viewmodels

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.ProbeDispatch
import to.bitkit.repositories.ProbeOutcome
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProbingToolViewModelTest : BaseUnitTest() {
    private lateinit var sut: ProbingToolViewModel

    private val context = mock<Context>()
    private val coreService = mock<CoreService>()
    private val lightningRepo = mock<LightningRepo>()

    @Before
    fun setUp() {
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))

        sut = ProbingToolViewModel(
            context = context,
            bgDispatcher = testDispatcher,
            coreService = coreService,
            lightningRepo = lightningRepo,
        )
    }

    @Test
    fun `sendProbe uses node id from node URI`() = test {
        val nodeId = "021a7a31f03a9b49807eb18ef03046e264871a1d03cd4cb80d37265499d1b726b9"
        val nodeUri = "$nodeId@54.244.234.100:20319"
        val paymentId = "probe-payment-id"
        val paymentHash = "probe-payment-hash"

        whenever(lightningRepo.sendProbeForNode(nodeId, 42uL))
            .thenReturn(Result.success(ProbeDispatch(paymentIds = setOf(paymentId))))
        whenever(lightningRepo.waitForProbeOutcome(setOf(paymentId)))
            .thenReturn(Result.success(ProbeOutcome.Success(paymentId = paymentId, paymentHash = paymentHash)))

        sut.updateInvoice(nodeUri)
        sut.updateAmountSats("42")
        advanceUntilIdle()

        assertTrue(sut.uiState.value.isNodeId)

        sut.sendProbe()
        advanceUntilIdle()

        verifyBlocking(lightningRepo) { sendProbeForNode(nodeId, 42uL) }
    }
}
