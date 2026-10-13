package to.bitkit.repositories

import com.synonym.bitkitcore.UsdtDeposit
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
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
        assertFalse(repo.enabled.first())
        wallet.value = wallet.value.copy(balance = 0u)
        assertFalse(repo.visible.first())
    }

    @Test
    fun `default off never publishes the USDT receiving option`() {
        assertFalse(PublicPaykitRepo.isUsdtPaymentOptionEnabled(SettingsData()))
        assertFalse(PublicPaykitRepo.isUsdtPaymentOptionEnabled(SettingsData(usdtPreference = UsdtPreference.DISABLED)))
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
