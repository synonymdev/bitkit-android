package to.bitkit.ui.settings.advanced

import android.content.Context
import app.cash.turbine.test
import com.synonym.bitkitcore.AddressType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddressTypePreferenceViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val settingsStore: SettingsStore = mock()
    private val lightningRepo: LightningRepo = mock()
    private val walletRepo: WalletRepo = mock()

    private lateinit var sut: AddressTypePreferenceViewModel

    private val settingsUpdated = "Settings updated"
    private val timeoutTitle = "Timeout"
    private val timeoutDesc = "Operation timed out"
    private val errorTitle = "Error"
    private val disabledHasBalance = "Address type has balance"
    private val disabledVerifyFailed = "Failed to verify balance"
    private val disabledNativeRequired = "Native SegWit or Taproot required"
    private val disabledCurrentlySelected = "Currently selected"

    @Before
    fun setUp() {
        runBlocking {
            whenever(context.getString(R.string.settings__addr_type__settings_updated)).thenReturn(settingsUpdated)
            whenever(context.getString(R.string.settings__addr_type__timeout)).thenReturn(timeoutTitle)
            whenever(context.getString(R.string.settings__addr_type__timeout_desc)).thenReturn(timeoutDesc)
            whenever(context.getString(R.string.common__error)).thenReturn(errorTitle)
            whenever(context.getString(R.string.settings__addr_type__disabled_has_balance))
                .thenReturn(disabledHasBalance)
            whenever(
                context.getString(R.string.settings__addr_type__disabled_verify_failed)
            ).thenReturn(disabledVerifyFailed)
            whenever(
                context.getString(R.string.settings__addr_type__disabled_native_required)
            ).thenReturn(disabledNativeRequired)
            whenever(
                context.getString(R.string.settings__addr_type__disabled_currently_selected)
            ).thenReturn(disabledCurrentlySelected)
            whenever(settingsStore.data).thenReturn(
                flowOf(
                    SettingsData(
                        selectedAddressType = "nativeSegwit",
                        addressTypesToMonitor = listOf("nativeSegwit"),
                        isDevModeEnabled = true,
                    )
                )
            )
        }
    }

    private fun createSut(): AddressTypePreferenceViewModel =
        AddressTypePreferenceViewModel(
            context = context,
            bgDispatcher = testDispatcher,
            settingsStore = settingsStore,
            lightningRepo = lightningRepo,
            walletRepo = walletRepo,
        )

    @Test
    fun `loadState populates uiState from settings`() = test {
        sut = createSut()

        sut.uiState.test {
            val state = awaitItem()
            assertEquals(AddressType.P2WPKH, state.selectedAddressType)
            assertEquals(setOf("nativeSegwit"), state.monitoredTypes)
            assertTrue(state.showMonitoredTypes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setMonitoring success sends success toast`() = test {
        runBlocking {
            wheneverBlocking { lightningRepo.setMonitoring(AddressType.P2TR, true) }
                .thenReturn(Result.success(Unit))
        }
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit"),
                    isDevModeEnabled = true,
                )
            )
        )
        sut = createSut()
        advanceUntilIdle()

        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        sut.setMonitoring(AddressType.P2TR, true)
        advanceUntilIdle()

        assertTrue(toasts.isNotEmpty())
        assertEquals(Toast.ToastType.SUCCESS, toasts.first().type)
        assertEquals(settingsUpdated, toasts.first().title)
        collectJob.cancel()
    }

    @Test
    fun `setMonitoring failure sends error toast with mapped message`() = test {
        runBlocking {
            wheneverBlocking { lightningRepo.setMonitoring(AddressType.P2TR, false) }
                .thenReturn(Result.failure(Exception("Cannot disable monitoring: address type has balance")))
        }
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit", "taproot"),
                    isDevModeEnabled = true,
                )
            )
        )
        sut = createSut()
        advanceUntilIdle()

        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        sut.setMonitoring(AddressType.P2TR, false)
        advanceUntilIdle()

        assertTrue(toasts.isNotEmpty())
        assertEquals(Toast.ToastType.WARNING, toasts.first().type)
        assertEquals(errorTitle, toasts.first().title)
        assertEquals(disabledHasBalance, toasts.first().description)
        collectJob.cancel()
    }

    @Test
    fun `setMonitoring failure with currently selected sends mapped error toast`() = test {
        runBlocking {
            wheneverBlocking { lightningRepo.setMonitoring(AddressType.P2TR, false) }
                .thenReturn(Result.failure(Exception("Cannot disable monitoring: address type is currently selected")))
        }
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "taproot",
                    addressTypesToMonitor = listOf("nativeSegwit", "taproot"),
                    isDevModeEnabled = true,
                )
            )
        )
        sut = createSut()
        advanceUntilIdle()

        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        sut.setMonitoring(AddressType.P2TR, false)
        advanceUntilIdle()

        assertTrue(toasts.isNotEmpty())
        assertEquals(disabledCurrentlySelected, toasts.first().description)
        collectJob.cancel()
    }

    @Test
    fun `updateAddressType success sends success toast`() = test {
        runBlocking {
            wheneverBlocking { lightningRepo.updateAddressType(any(), any()) }.thenReturn(Result.success(Unit))
            wheneverBlocking { walletRepo.refreshReceiveAddressAfterTypeChange() }.thenReturn(Result.success(Unit))
        }
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit"),
                    isDevModeEnabled = true,
                )
            )
        )
        sut = createSut()
        advanceUntilIdle()

        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        sut.updateAddressType(AddressType.P2TR)
        advanceUntilIdle()

        assertTrue(toasts.isNotEmpty())
        assertEquals(Toast.ToastType.SUCCESS, toasts.first().type)
        assertEquals(settingsUpdated, toasts.first().title)
        collectJob.cancel()
    }

    @Test
    fun `updateAddressType failure sends error toast`() = test {
        runBlocking {
            wheneverBlocking { lightningRepo.updateAddressType(any(), any()) }
                .thenReturn(Result.failure(Exception("Node restart failed")))
        }
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit"),
                    isDevModeEnabled = true,
                )
            )
        )
        sut = createSut()
        advanceUntilIdle()

        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        sut.updateAddressType(AddressType.P2TR)
        advanceUntilIdle()

        assertTrue(toasts.isNotEmpty())
        assertEquals(Toast.ToastType.WARNING, toasts.first().type)
        assertEquals(errorTitle, toasts.first().title)
        assertEquals("Node restart failed", toasts.first().description)
        collectJob.cancel()
    }
}
