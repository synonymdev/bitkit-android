package to.bitkit.ui.screens.wallets.receive

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.DirectFundingPaymentDetails
import org.lightningdevkit.ldknode.DirectFundingRequestDetails
import org.lightningdevkit.ldknode.PeerDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class DirectFundingReceiveViewModelTest : BaseUnitTest() {
    private val lightningRepo = mock<LightningRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val settings = MutableStateFlow(SettingsData(primaryNodeUri = "primary"))
    private val lightning = MutableStateFlow(
        LightningState(
            nodeLifecycleState = NodeLifecycleState.Running,
            peers = persistentListOf(PeerDetails("primary", "host:9735", true, true)),
        ),
    )
    private val details = DirectFundingRequestDetails("signed", "id", "hash", "receiver", 20_000uL, 1_600_000uL)
    private val clock = object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(1_000_000 + testDispatcher.scheduler.currentTime)
    }
    private lateinit var sut: DirectFundingReceiveViewModel

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(settings)
        whenever(lightningRepo.lightningState).thenReturn(lightning)
        whenever { lightningRepo.createDirectFundingRequest(20_000uL) }.thenReturn(Result.success(details))
        whenever { lightningRepo.listDirectFundingPayments() }.thenReturn(Result.success(emptyList()))
        whenever { walletRepo.retainDirectFundingReceive(any(), any(), any(), any()) }.thenReturn(Result.success(Unit))
        whenever { walletRepo.invalidateDirectFundingReceive(any()) }.thenReturn(Result.success(Unit))
        sut = DirectFundingReceiveViewModel(lightningRepo, walletRepo, settingsStore, clock)
    }

    @After
    fun clear() = sut.clear()

    @Test
    fun `closing the sheet hides but does not revoke a shared request`() = test {
        sut.update(20_000uL, "address", "invoice")
        runCurrent()
        assertEquals("signed", sut.request.value?.request)
        sut.clear()
        runCurrent()
        assertNull(sut.request.value)
        verify(walletRepo, never()).invalidateDirectFundingReceive(any())
    }

    @Test
    fun `amount change below protocol minimum hides and revokes previous request`() = test {
        sut.update(20_000uL, "address", "invoice")
        runCurrent()
        sut.update(3_000uL, "address", "new-invoice")
        runCurrent()
        assertNull(sut.request.value)
        verify(walletRepo).invalidateDirectFundingReceive("id")
        verify(lightningRepo, never()).createDirectFundingRequest(3_000uL)
    }

    @Test
    fun `disconnect hides an advertised request immediately`() = test {
        sut.update(20_000uL, "address", "invoice")
        runCurrent()
        lightning.value = lightning.value.copy(peers = persistentListOf())
        runCurrent()
        assertNull(sut.request.value)
    }

    @Test
    fun `expiry hides the request even while a native status query is blocked`() = test {
        sut.update(20_000uL, "address", "invoice")
        runCurrent()
        val blocked = CompletableDeferred<Result<List<DirectFundingPaymentDetails>>>()
        whenever(lightningRepo.listDirectFundingPayments()).doSuspendableAnswer { blocked.await() }
        advanceTimeBy(600_000)
        runCurrent()
        assertNull(sut.request.value)
        blocked.complete(Result.success(emptyList()))
        runCurrent()
        assertNull(sut.request.value)
    }
}
