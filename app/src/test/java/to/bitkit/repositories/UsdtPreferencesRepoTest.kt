package to.bitkit.repositories

import com.synonym.bitkitcore.UsdtDeposit
import com.synonym.bitkitcore.UsdtDepositPage
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.UsdtWallet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.UsdtPreference
import to.bitkit.services.UsdtService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsdtPreferencesRepoTest : BaseUnitTest() {
    private val settings = MutableStateFlow(SettingsData())
    private val wallet = MutableStateFlow(UsdtWalletState())
    private val settingsStore: SettingsStore = mock { on { data } doReturn settings }
    private val usdt: UsdtRepo = mock { on { state } doReturn wallet }
    private val repo get() = UsdtPreferencesRepo(settingsStore, usdt, testDispatcher)

    @Before
    fun setUp() = test {
        whenever(settingsStore.update(any())).thenAnswer {
            settings.value = it.getArgument<(SettingsData) -> SettingsData>(0)(settings.value)
            Unit
        }
        whenever(usdt.refresh(history = false, force = true)).thenReturn(Result.success(Unit))
    }

    @Test
    fun `missing preference remains undecided and explicit choices round trip`() {
        assertEquals(UsdtPreference.UNDECIDED, Json.decodeFromString<SettingsData>("{}").usdtPreference)
        UsdtPreference.entries.forEach { preference ->
            val settings = SettingsData(usdtPreference = preference)
            assertEquals(preference, Json.decodeFromString<SettingsData>(Json.encodeToString(settings)).usdtPreference)
        }
    }

    @Test
    fun `explicit enable and decline persist separately from payment options`() = test {
        repo.setEnabled(true).getOrThrow()
        assertEquals(UsdtPreference.ENABLED, settings.value.usdtPreference)
        settings.value = SettingsData()
        repo.setEnabled(false).getOrThrow()
        assertEquals(UsdtPreference.DISABLED, settings.value.usdtPreference)
        assertTrue(settings.value.publicPaykitUsdtEnabled)
        assertFalse(repo.enabled.first())
    }

    @Test
    fun `funds or unavailable balance prevent disabling an enabled account`() = test {
        settings.value = SettingsData(usdtPreference = UsdtPreference.ENABLED)
        for (balance in listOf(null, 1uL)) {
            wallet.value = UsdtWalletState(balance = balance)
            assertTrue(repo.setEnabled(false).exceptionOrNull() is UsdtDisableError)
            assertEquals(UsdtPreference.ENABLED, settings.value.usdtPreference)
        }
    }

    @Test
    fun `refresh failure preserves the enabled preference`() = test {
        settings.value = SettingsData(usdtPreference = UsdtPreference.ENABLED)
        wallet.value = UsdtWalletState(balance = 0u)
        whenever(usdt.refresh(history = false, force = true)).thenReturn(Result.failure(AppError("offline")))
        assertTrue(repo.setEnabled(false).isFailure)
        assertEquals(UsdtPreference.ENABLED, settings.value.usdtPreference)
    }

    @Test
    fun `unsettled outgoing transfers protect visibility and prevent disabling`() {
        val unresolved = setOf(
            UsdtTransferStatus.PENDING,
            UsdtTransferStatus.BRIDGING,
            UsdtTransferStatus.BRIDGE_FAILED,
            UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION,
        )
        UsdtTransferStatus.entries.forEach { status ->
            val transfer: UsdtTransfer = mock { on { this.status } doReturn status }
            val wallet = UsdtWalletState(balance = 0u, transfers = persistentListOf(transfer))
            assertEquals(status in unresolved, wallet.hasFundsOrPendingTransfers, status.name)
            assertEquals(status !in unresolved, wallet.canDisable, status.name)
        }
    }

    @Test
    fun `recovered history stays visible until an empty balance is confirmed`() = test {
        val transfer: UsdtTransfer = mock { on { status } doReturn UsdtTransferStatus.CONFIRMED }
        wallet.value = UsdtWalletState(transfers = persistentListOf(transfer))
        assertTrue(repo.visible.first())
        assertTrue(repo.enabled.first())
        assertFalse(repo.canDisable.first())
        wallet.value = wallet.value.copy(balance = 0u)
        assertFalse(repo.visible.first())
    }

    @Test
    fun `default off never publishes the USDT receiving option`() {
        assertFalse(PublicPaykitRepo.isUsdtPaymentOptionEnabled(SettingsData(), UsdtWalletState()))
        assertFalse(
            PublicPaykitRepo.isUsdtPaymentOptionEnabled(
                SettingsData(usdtPreference = UsdtPreference.DISABLED),
                UsdtWalletState()
            )
        )
    }

    @Test
    fun `restored funds or pending transfers keep a declined wallet enabled without changing consent`() = test {
        settings.value = SettingsData(usdtPreference = UsdtPreference.DISABLED)
        val pending: UsdtTransfer = mock { on { status } doReturn UsdtTransferStatus.PENDING }
        for (state in listOf(
            UsdtWalletState(balance = 1u),
            UsdtWalletState(balance = 0u, transfers = persistentListOf(pending)),
        )) {
            wallet.value = state
            assertTrue(repo.enabled.first())
            assertFalse(repo.canDisable.first())
            assertTrue(repo.setEnabled(false).isFailure)
            assertTrue(PublicPaykitRepo.isUsdtPaymentOptionEnabled(settings.value, state))
            assertEquals(UsdtPreference.DISABLED, settings.value.usdtPreference)
        }
        wallet.value = UsdtWalletState(balance = 0u)
        assertFalse(repo.enabled.first())
        assertTrue(repo.canDisable.first())
    }

    @Test
    fun `disable check refreshes a previously pending deposit after it settles`() = test {
        val service: UsdtService = mock()
        val coreWallet: UsdtWallet = mock()
        whenever(service.wallet()).thenReturn(coreWallet)
        whenever(coreWallet.receiveAddress()).thenReturn("wallet-address")
        whenever(coreWallet.receiveUri()).thenReturn("wallet-uri")
        whenever(coreWallet.balance()).thenReturn(0u)
        whenever(coreWallet.history()).thenReturn(emptyList())
        whenever(coreWallet.refreshTransfers()).thenReturn(emptyList())
        val usdt = UsdtRepo(service, testDispatcher)
        usdt.refresh(history = false).getOrThrow()
        val pending: UsdtDeposit = mock {
            on { id } doReturn "pending-deposit"
            on { status } doReturn "refunding"
        }
        usdt.updateDeposits(listOf(pending))
        assertFalse(usdt.state.value.canDisable)
        whenever(pending.status).thenReturn("refunded")
        val page: UsdtDepositPage = mock {
            on { deposits } doReturn listOf(pending)
            on { nextOffset } doReturn null
        }
        whenever(service.depositHistory(0u)).thenReturn(page)
        val preferences = UsdtPreferencesRepo(settingsStore, usdt, testDispatcher)
        preferences.checkCanDisable().getOrThrow()
        assertTrue(usdt.state.value.canDisable)
        assertTrue(usdt.state.value.pendingDepositIds.isEmpty())
    }

    @Test
    fun `unused and settled deposit routes do not block disabling`() {
        for (status in listOf("waiting", "awaiting_deposit", "expired", "completed", "refunded")) {
            val deposit: UsdtDeposit = mock { on { this.status } doReturn status }
            assertFalse(deposit.needsSettlement, status)
        }
        for (status in listOf("confirming", "processing", "failed", "refund_requested", "refunding", "unknown")) {
            val deposit: UsdtDeposit = mock { on { this.status } doReturn status }
            assertTrue(deposit.needsSettlement, status)
        }
    }
}
