package to.bitkit.usecases

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ChannelDetails
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.SpendingBackend
import to.bitkit.models.BalanceState
import to.bitkit.repositories.BarkRepo
import to.bitkit.repositories.BarkState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CanSwitchSpendingBackendUseCaseTest : BaseUnitTest() {

    private val walletRepo: WalletRepo = mock()
    private val lightningRepo: LightningRepo = mock()
    private val barkRepo: BarkRepo = mock()
    private val transferRepo: TransferRepo = mock()

    private val balanceState = MutableStateFlow(BalanceState())
    private val barkState = MutableStateFlow(BarkState())

    private lateinit var sut: CanSwitchSpendingBackendUseCase

    @Before
    fun setUp() {
        whenever(walletRepo.balanceState).thenReturn(balanceState)
        whenever(barkRepo.barkState).thenReturn(barkState)
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        wheneverBlocking { barkRepo.hasPendingExits() }.thenReturn(Result.success(false))

        sut = CanSwitchSpendingBackendUseCase(
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            barkRepo = barkRepo,
            transferRepo = transferRepo,
        )
    }

    // region ldk -> bark

    @Test
    fun `allows switching to bark with no spending balance and no channels`() = test {
        assertEquals(SpendingBackendSwitchState.Allowed, sut(SpendingBackend.BARK))
    }

    @Test
    fun `blocks switching to bark while a spending balance exists`() = test {
        balanceState.value = BalanceState(totalLightningSats = 42_000uL)

        val result = sut(SpendingBackend.BARK)

        val blocked = assertIs<SpendingBackendSwitchState.Blocked.SpendingBalance>(result)
        assertEquals(42_000uL, blocked.sats)
    }

    @Test
    fun `blocks switching to bark while a channel is still open`() = test {
        whenever(lightningRepo.getChannels()).thenReturn(listOf(mock<ChannelDetails>()))

        assertEquals(SpendingBackendSwitchState.Blocked.OpenChannels, sut(SpendingBackend.BARK))
    }

    // endregion

    // region bark -> ldk

    @Test
    fun `allows switching back to ldk with an empty ark wallet`() = test {
        assertEquals(SpendingBackendSwitchState.Allowed, sut(SpendingBackend.LDK))
    }

    @Test
    fun `blocks switching back to ldk while vtxos are spendable`() = test {
        barkState.value = BarkState(spendableSats = 1_000uL)

        val blocked = assertIs<SpendingBackendSwitchState.Blocked.SpendingBalance>(sut(SpendingBackend.LDK))
        assertEquals(1_000uL, blocked.sats)
    }

    @Test
    fun `blocks switching back to ldk while a board is still confirming`() = test {
        barkState.value = BarkState(pendingBoardSats = 5_000uL)

        assertIs<SpendingBackendSwitchState.Blocked.SpendingBalance>(sut(SpendingBackend.LDK))
    }

    @Test
    fun `blocks switching back to ldk while a lightning receive is claimable`() = test {
        barkState.value = BarkState(claimableLightningReceiveSats = 250uL)

        assertIs<SpendingBackendSwitchState.Blocked.SpendingBalance>(sut(SpendingBackend.LDK))
    }

    @Test
    fun `blocks switching back to ldk while an exit is in progress`() = test {
        wheneverBlocking { barkRepo.hasPendingExits() }.thenReturn(Result.success(true))

        assertEquals(SpendingBackendSwitchState.Blocked.PendingExit, sut(SpendingBackend.LDK))
    }

    // endregion

    @Test
    fun `blocks either direction while a transfer is in flight`() = test {
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(mock())))

        assertEquals(SpendingBackendSwitchState.Blocked.PendingTransfer, sut(SpendingBackend.BARK))
        assertEquals(SpendingBackendSwitchState.Blocked.PendingTransfer, sut(SpendingBackend.LDK))
    }
}
