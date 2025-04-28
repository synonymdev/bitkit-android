package to.bitkit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.BalanceDetails
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.annotation.Config
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.TestApp
import to.bitkit.viewmodels.MainUiState
import to.bitkit.viewmodels.WalletViewModel
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class WalletViewModelTest : BaseUnitTest() {
    private var lightningRepo: LightningRepo = mock()
    private var walletRepo: WalletRepo = mock()

    private lateinit var sut: WalletViewModel

    private val balanceDetails = mock<BalanceDetails>()
    private val nodeLifecycleStateFlow = MutableStateFlow(NodeLifecycleState.Stopped)

    @Before
    fun setUp() {
        whenever(lightningRepo.getNodeId()).thenReturn("nodeId")
        whenever(lightningRepo.getBalances()).thenReturn(balanceDetails)
        whenever(lightningRepo.getBalances()?.totalLightningBalanceSats).thenReturn(1000u)
        whenever(lightningRepo.getBalances()?.totalOnchainBalanceSats).thenReturn(10_000u)
        wheneverBlocking { lightningRepo.newAddress() }.thenReturn(Result.success("onchainAddress"))


        // Node lifecycle state flow
        whenever(lightningRepo.nodeLifecycleState).thenReturn(nodeLifecycleStateFlow)

        // Database config flow
        wheneverBlocking{walletRepo.getDbConfig()}.thenReturn(flowOf(emptyList()))

        sut = WalletViewModel(
            bgDispatcher = testDispatcher,
            appContext = mock(),
            walletRepo = walletRepo,
            lightningRepo = lightningRepo
        )
    }

    @Test
    fun `start should emit Content uiState`() = test {
        setupExistingWalletMocks()
        val expectedUiState = MainUiState(
            nodeId = "nodeId",
            onchainAddress = "onchainAddress",
            peers = emptyList(),
            channels = emptyList(),
            balanceDetails = balanceDetails,
            bolt11 = "bolt11",
            bip21 = "bitcoin:onchainAddress",
            nodeLifecycleState = NodeLifecycleState.Starting,
            nodeStatus = null,
        )

        sut.start()

        sut.uiState.test {
            val content = awaitItem()
            assertEquals(expectedUiState, content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `start should register for notifications if token is not cached`() = test {
        setupExistingWalletMocks()
        whenever(lightningRepo.start(walletIndex = 0)).thenReturn(Result.success(Unit))

        sut.start()

        verify(walletRepo).registerForNotifications()
    }

    @Test
    fun `manualRegisterForNotifications should register device with FCM token`() = test {
        sut.manualRegisterForNotifications()

        verify(walletRepo).registerForNotifications()
    }

    private fun setupExistingWalletMocks() = test {
        whenever(walletRepo.walletExists()).thenReturn(true)
        sut.setWalletExistsState()
        whenever(walletRepo.walletExists()).thenReturn(true)
        whenever(walletRepo.getOnchainAddress()).thenReturn("onchainAddress")
        whenever(walletRepo.getBip21()).thenReturn("bitcoin:onchainAddress")
        whenever(walletRepo.getMnemonic()).thenReturn(Result.success("mnemonic"))
        whenever(walletRepo.getBolt11()).thenReturn("bolt11")
        whenever(lightningRepo.checkAddressUsage(anyString())).thenReturn(Result.success(true))
        whenever(lightningRepo.start(walletIndex = 0)).thenReturn(Result.success(Unit))
        whenever(lightningRepo.getPeers()).thenReturn(emptyList())
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(lightningRepo.getStatus()).thenReturn(null)
    }
}
