package to.bitkit.ui

import app.cash.turbine.test
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.env.Env.SEED
import to.bitkit.services.LightningService
import to.bitkit.services.OnChainService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class WalletViewModelTest : BaseUnitTest() {
    private val onChainService: OnChainService = mock()
    private val lightningService: LightningService = mock()

    private lateinit var sut: WalletViewModel

    @Before
    fun setUp() {
        whenever(lightningService.nodeId).thenReturn("nodeId")
        whenever(lightningService.balances).thenReturn(mock())
        whenever(lightningService.balances?.totalLightningBalanceSats).thenReturn(1000u)
        wheneverBlocking { onChainService.getAddress() }.thenReturn("btcAddress")
        whenever(onChainService.balance).thenReturn(mock())
        whenever(onChainService.balance?.total).thenReturn(mock())
        whenever(onChainService.balance?.total?.toSat()).thenReturn(500u)

        sut = WalletViewModel(
            bgDispatcher = testDispatcher,
            onChainService = onChainService,
            lightningService = lightningService
        )
    }

    @Test
    fun `uiState should emit Content state after sync`() = test {
        val expectedUiState = MainUiState.Content(
            ldkNodeId = "nodeId",
            ldkBalance = "1000",
            btcAddress = "btcAddress",
            btcBalance = "500",
            mnemonic = SEED,
            peers = emptyList(),
            channels = emptyList()
        )

        sut.uiState.test {
            val initial = awaitItem()
            assertEquals(MainUiState.Loading, initial)

            val content = awaitItem()
            assertEquals(expectedUiState, content)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
