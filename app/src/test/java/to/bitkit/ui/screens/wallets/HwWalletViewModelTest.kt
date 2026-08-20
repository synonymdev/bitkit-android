package to.bitkit.ui.screens.wallets

import android.content.Context
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.HwWallet
import to.bitkit.models.Toast
import to.bitkit.models.TransportType
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.HwWalletRepo.Companion.DEVICE_LABEL_MAX_LENGTH
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HwWalletViewModelTest : BaseUnitTest() {

    private val context: Context = mock()
    private val hwWalletRepo: HwWalletRepo = mock()

    private val wallet = HwWallet(
        id = "dev1",
        name = "Trezor Safe 3",
        model = "Safe 3",
        transportType = TransportType.USB,
        isConnected = true,
        balanceSats = 10_562_411uL,
        activities = persistentListOf(),
    )

    private val otherWallet = HwWallet(
        id = "dev2",
        name = "Trezor Safe 5",
        model = "Safe 5",
        transportType = TransportType.BLUETOOTH,
        isConnected = false,
        balanceSats = 2_735_180uL,
        activities = persistentListOf(),
    )

    private lateinit var wallets: MutableStateFlow<ImmutableList<HwWallet>>

    @Before
    fun setUp() {
        wallets = MutableStateFlow(listOf(wallet).toImmutableList())
        whenever(hwWalletRepo.wallets).thenReturn(wallets)
        whenever(hwWalletRepo.walletsLoaded).thenReturn(MutableStateFlow(true))
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        whenever(context.getString(R.string.hardware__remove_error)).thenReturn("Could not remove")
        whenever(context.getString(R.string.hardware__rename_error)).thenReturn("Could not rename")
    }

    private fun createSut() = HwWalletViewModel(context, hwWalletRepo)

    @Test
    fun `exposes the wallets from the repo`() = test {
        val sut = createSut()

        assertEquals(listOf(wallet), sut.wallets.value)
        assertEquals(true, sut.walletsLoaded.value)
    }

    @Test
    fun `onRemoveClick sets the pending device and onDismiss clears it`() = test {
        val sut = createSut()

        sut.onRemoveClick(wallet)
        assertEquals(wallet, sut.uiState.value.isPendingRemoval)

        sut.onDismissRemoveDialog()
        assertNull(sut.uiState.value.isPendingRemoval)
    }

    @Test
    fun `onRemoveClick stores the clicked device when multiple are paired`() = test {
        wallets.value = listOf(wallet, otherWallet).toImmutableList()
        val sut = createSut()

        sut.onRemoveClick(otherWallet)

        assertEquals(otherWallet, sut.uiState.value.isPendingRemoval)
    }

    @Test
    fun `removeDevice delegates to the repo and clears the pending device`() = test {
        whenever { hwWalletRepo.removeDevice("dev1", true) }.thenReturn(Result.success(Unit))
        val sut = createSut()
        sut.onRemoveClick(wallet)

        sut.removeDevice("dev1")
        advanceUntilIdle()

        verify(hwWalletRepo).removeDevice("dev1", true)
        assertNull(sut.uiState.value.isPendingRemoval)
    }

    @Test
    fun `onRemoveClick offers to keep the backup data`() = test {
        val sut = createSut()

        sut.onRemoveClick(wallet)

        assertEquals(true, sut.uiState.value.keepBackupDataOnRemoval)
    }

    @Test
    fun `onRemoveClick restores the default after a wallet was removed without keeping its data`() = test {
        whenever { hwWalletRepo.removeDevice(any(), any()) }.thenReturn(Result.success(Unit))
        val sut = createSut()
        sut.onRemoveClick(wallet)
        sut.onKeepBackupDataChange(false)
        sut.removeDevice("dev1")
        advanceUntilIdle()

        // Reopening starts from the default rather than from the last choice.
        sut.onRemoveClick(otherWallet)

        assertEquals(true, sut.uiState.value.keepBackupDataOnRemoval)
    }

    @Test
    fun `removeDevice forwards the choice to keep nothing`() = test {
        whenever { hwWalletRepo.removeDevice("dev1", false) }.thenReturn(Result.success(Unit))
        val sut = createSut()
        sut.onRemoveClick(wallet)
        sut.onKeepBackupDataChange(false)

        sut.removeDevice("dev1")
        advanceUntilIdle()

        verify(hwWalletRepo).removeDevice("dev1", false)
    }

    @Test
    fun `removeDevice sends an error toast on failure`() = test {
        whenever { hwWalletRepo.removeDevice(any(), any()) }.thenReturn(Result.failure(AppError("nope")))
        val sut = createSut()

        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        sut.removeDevice("dev1")
        advanceUntilIdle()

        assertEquals(Toast.ToastType.ERROR, toasts.single().type)
        collectJob.cancel()
    }

    @Test
    fun `onRenameClick opens the rename sheet with the current name`() = test {
        val sut = createSut()

        sut.onRenameClick(wallet)

        assertEquals(wallet, sut.uiState.value.isPendingRename)
        assertEquals(wallet.name, sut.uiState.value.labelInput)
        assertFalse(sut.uiState.value.isSavingLabel)
        assertFalse(sut.uiState.value.isRenameSaved)
    }

    @Test
    fun `onLabelChange caps the rename input`() = test {
        val sut = createSut()

        sut.onLabelChange("a".repeat(DEVICE_LABEL_MAX_LENGTH + 1))

        assertEquals("a".repeat(DEVICE_LABEL_MAX_LENGTH), sut.uiState.value.labelInput)
    }

    @Test
    fun `onDismissRenameSheet clears the rename state`() = test {
        val sut = createSut()
        sut.onRenameClick(wallet)

        sut.onDismissRenameSheet()

        assertNull(sut.uiState.value.isPendingRename)
        assertEquals("", sut.uiState.value.labelInput)
        assertFalse(sut.uiState.value.isSavingLabel)
        assertFalse(sut.uiState.value.isRenameSaved)
    }

    @Test
    fun `saveDeviceLabel delegates to the repo and marks the rename saved`() = test {
        whenever { hwWalletRepo.setDeviceLabel("dev1", "My Cold Wallet") }.thenReturn(Result.success(Unit))
        val sut = createSut()
        sut.onRenameClick(wallet)
        sut.onLabelChange("My Cold Wallet")

        sut.saveDeviceLabel()
        advanceUntilIdle()

        verify(hwWalletRepo).setDeviceLabel("dev1", "My Cold Wallet")
        assertEquals(wallet, sut.uiState.value.isPendingRename)
        assertEquals("My Cold Wallet", sut.uiState.value.labelInput)
        assertFalse(sut.uiState.value.isSavingLabel)
        assertEquals(true, sut.uiState.value.isRenameSaved)
    }

    @Test
    fun `saveDeviceLabel ignores stale success after a different rename sheet opens`() = test {
        lateinit var sut: HwWalletViewModel
        whenever { hwWalletRepo.setDeviceLabel("dev1", "My Cold Wallet") }.thenAnswer {
            sut.onDismissRenameSheet()
            sut.onRenameClick(otherWallet)
            Unit
        }
        sut = createSut()
        sut.onRenameClick(wallet)
        sut.onLabelChange("My Cold Wallet")

        sut.saveDeviceLabel()
        advanceUntilIdle()

        assertEquals(otherWallet, sut.uiState.value.isPendingRename)
        assertEquals(otherWallet.name, sut.uiState.value.labelInput)
        assertFalse(sut.uiState.value.isSavingLabel)
        assertFalse(sut.uiState.value.isRenameSaved)
    }

    @Test
    fun `saveDeviceLabel ignores blank labels`() = test {
        val sut = createSut()
        sut.onRenameClick(wallet)
        sut.onLabelChange("   ")

        sut.saveDeviceLabel()
        advanceUntilIdle()

        verify(hwWalletRepo, never()).setDeviceLabel("dev1", "   ")
        assertEquals(wallet, sut.uiState.value.isPendingRename)
    }

    @Test
    fun `saveDeviceLabel sends an error toast on failure`() = test {
        whenever { hwWalletRepo.setDeviceLabel("dev1", "My Cold Wallet") }.thenReturn(Result.failure(AppError("nope")))
        val sut = createSut()
        sut.onRenameClick(wallet)
        sut.onLabelChange("My Cold Wallet")

        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        sut.saveDeviceLabel()
        advanceUntilIdle()

        assertEquals(wallet, sut.uiState.value.isPendingRename)
        assertFalse(sut.uiState.value.isSavingLabel)
        assertEquals(Toast.ToastType.ERROR, toasts.single().type)
        collectJob.cancel()
    }
}
