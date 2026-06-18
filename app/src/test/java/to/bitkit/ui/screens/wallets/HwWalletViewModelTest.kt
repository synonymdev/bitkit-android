package to.bitkit.ui.screens.wallets

import android.content.Context
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.HwWallet
import to.bitkit.models.Toast
import to.bitkit.models.TransportType
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private lateinit var wallets: MutableStateFlow<kotlinx.collections.immutable.ImmutableList<HwWallet>>
    private lateinit var walletsLoaded: MutableStateFlow<Boolean>

    @Before
    fun setUp() {
        wallets = MutableStateFlow(listOf(wallet).toImmutableList())
        walletsLoaded = MutableStateFlow(true)
        whenever(hwWalletRepo.wallets).thenReturn(wallets)
        whenever(hwWalletRepo.walletsLoaded).thenReturn(walletsLoaded)
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        whenever(context.getString(R.string.hardware__remove_error)).thenReturn("Could not remove")
    }

    private fun createSut() = HwWalletViewModel(context, hwWalletRepo)

    @Test
    fun `exposes the wallets from the repo`() = test {
        val sut = createSut()

        assertEquals(listOf(wallet), sut.wallets.value)
        assertEquals(true, sut.walletsLoaded.value)
    }

    @Test
    fun `onRemoveClick shows the dialog and onDismiss hides it`() = test {
        val sut = createSut()

        sut.onRemoveClick()
        assertTrue(sut.uiState.value.showRemoveDialog)

        sut.onDismissRemoveDialog()
        assertFalse(sut.uiState.value.showRemoveDialog)
    }

    @Test
    fun `removeDevice delegates to the repo and hides the dialog`() = test {
        whenever { hwWalletRepo.removeDevice("dev1") }.thenReturn(Result.success(Unit))
        val sut = createSut()
        sut.onRemoveClick()

        sut.removeDevice("dev1")
        advanceUntilIdle()

        verify(hwWalletRepo).removeDevice("dev1")
        assertFalse(sut.uiState.value.showRemoveDialog)
    }

    @Test
    fun `removeDevice sends an error toast on failure`() = test {
        whenever { hwWalletRepo.removeDevice("dev1") }.thenReturn(Result.failure(AppError("nope")))
        val sut = createSut()

        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        sut.removeDevice("dev1")
        advanceUntilIdle()

        assertEquals(Toast.ToastType.ERROR, toasts.single().type)
        collectJob.cancel()
    }
}
