package to.bitkit.ui.settings.advanced

import android.content.Context
import app.cash.turbine.test
import com.synonym.bitkitcore.AddressType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PrivatePaykitRepo
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
    private val privatePaykitRepo: PrivatePaykitRepo = mock()

    private lateinit var sut: AddressTypePreferenceViewModel

    private val applyingChanges = "Applying changes…"
    private val addressTypeChanged = "Address Type Changed"
    private val nowUsing = "Now using {type} addresses."
    private val settingsUpdated = "Settings updated"
    private val errorTitle = "Error"
    private val disabledHasBalance = "Address type has balance"
    private val disabledVerifyFailed = "Failed to verify balance"
    private val disabledNativeRequired = "Native SegWit or Taproot required"
    private val disabledCurrentlySelected = "Currently selected"

    @Before
    fun setUp() {
        whenever(context.getString(R.string.settings__addr_type__applying)).thenReturn(applyingChanges)
        whenever(context.getString(R.string.settings__addr_type__changed)).thenReturn(addressTypeChanged)
        whenever(context.getString(R.string.settings__addr_type__now_using)).thenReturn(nowUsing)
        whenever(context.getString(R.string.settings__addr_type__settings_updated)).thenReturn(settingsUpdated)
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
        whenever { privatePaykitRepo.refreshKnownSavedContactEndpoints(any()) }
            .thenReturn(Result.success(Unit))
    }

    private fun createSut(): AddressTypePreferenceViewModel =
        AddressTypePreferenceViewModel(
            context = context,
            bgDispatcher = testDispatcher,
            settingsStore = settingsStore,
            lightningRepo = lightningRepo,
            walletRepo = walletRepo,
            privatePaykitRepo = privatePaykitRepo,
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
        whenever(lightningRepo.setMonitoring(AddressType.P2TR, true))
            .thenReturn(Result.success(Unit))
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

        assertTrue(toasts.size >= 2)
        assertEquals(Toast.ToastType.INFO, toasts.first().type)
        assertEquals(applyingChanges, toasts.first().title)
        assertEquals(Toast.ToastType.SUCCESS, toasts.last().type)
        assertEquals(settingsUpdated, toasts.last().title)
        collectJob.cancel()
    }

    @Test
    fun `setMonitoring failure sends error toast with mapped message`() = test {
        whenever(lightningRepo.setMonitoring(AddressType.P2TR, false))
            .thenReturn(Result.failure(Exception("Cannot disable monitoring: address type has balance")))
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
        assertEquals(Toast.ToastType.WARNING, toasts.last().type)
        assertEquals(errorTitle, toasts.last().title)
        assertEquals(disabledHasBalance, toasts.last().description)
        collectJob.cancel()
    }

    @Test
    fun `setMonitoring failure with currently selected sends mapped error toast`() = test {
        whenever(lightningRepo.setMonitoring(AddressType.P2TR, false))
            .thenReturn(Result.failure(Exception("Cannot disable monitoring: address type is currently selected")))
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
        assertEquals(Toast.ToastType.WARNING, toasts.last().type)
        assertEquals(errorTitle, toasts.last().title)
        assertEquals(disabledCurrentlySelected, toasts.last().description)
        collectJob.cancel()
    }

    @Test
    fun `updateAddressType success sends success toast`() = test {
        whenever(lightningRepo.updateAddressType(any(), any())).thenReturn(Result.success(Unit))
        whenever(walletRepo.refreshReceiveAddressAfterTypeChange()).thenReturn(Result.success(Unit))
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

        assertTrue(toasts.size >= 2)
        assertEquals(Toast.ToastType.INFO, toasts.first().type)
        assertEquals(applyingChanges, toasts.first().title)
        assertEquals(Toast.ToastType.SUCCESS, toasts.last().type)
        assertEquals(addressTypeChanged, toasts.last().title)
        assertEquals("Now using Taproot addresses.", toasts.last().description)
        verify(walletRepo).refreshReceiveAddressAfterTypeChange()
        collectJob.cancel()
    }

    @Test
    fun `updateAddressType failure sends error toast`() = test {
        whenever(lightningRepo.updateAddressType(any(), any()))
            .thenReturn(Result.failure(Exception("Update failed")))
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
        assertEquals(Toast.ToastType.WARNING, toasts.last().type)
        assertEquals(errorTitle, toasts.last().title)
        assertEquals("Update failed", toasts.last().description)
        verify(walletRepo, times(0)).refreshReceiveAddressAfterTypeChange()
        collectJob.cancel()
    }

    @Test
    fun `updateAddressType no-op when same type already selected`() = test {
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
        sut.updateAddressType(AddressType.P2WPKH) // same as selected
        advanceUntilIdle()

        assertTrue(toasts.isEmpty(), "No toast should be sent for no-op")
        verify(lightningRepo, never()).updateAddressType(any(), any())
        collectJob.cancel()
    }

    @Test
    fun `setMonitoring no-op when type already in desired state`() = test {
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
        sut.setMonitoring(AddressType.P2WPKH, true) // already monitored
        advanceUntilIdle()

        assertTrue(toasts.isEmpty(), "No toast should be sent for no-op")
        verify(lightningRepo, never()).setMonitoring(any(), any())
        collectJob.cancel()
    }
}
