package to.bitkit.ui.sheets.hardware

import android.content.Context
import app.cash.turbine.test
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorTransportType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.HwWallet
import to.bitkit.models.TransportType
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.TrezorState
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HwConnectViewModelTest : BaseUnitTest() {

    private val hwWalletRepo = mock<HwWalletRepo>()
    private val context = mock<Context>()
    private val needsPairingCode = MutableStateFlow(false)
    private val wallets = MutableStateFlow<ImmutableList<HwWallet>>(persistentListOf())
    private val deviceState = MutableStateFlow(TrezorState())

    private lateinit var sut: HwConnectViewModel

    @Before
    fun setUp() {
        whenever(hwWalletRepo.needsPairingCode).thenReturn(needsPairingCode)
        whenever(hwWalletRepo.wallets).thenReturn(wallets)
        whenever(hwWalletRepo.deviceState).thenReturn(deviceState)
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn(CONNECT_ERROR)
        whenever(context.getString(R.string.hardware__search_error)).thenReturn(SEARCH_ERROR)
        sut = HwConnectViewModel(
            hwWalletRepo = hwWalletRepo,
            context = context,
        )
    }

    @Test
    fun `onIntroContinue searches then advances to found with the first discovered device`() = test {
        deviceState.value = TrezorState(nearbyDevices = persistentListOf(deviceInfo("dev1", model = "Safe 3")))
        whenever(hwWalletRepo.scan(includeBluetooth = true)).thenReturn(Result.success(emptyList()))

        sut.effects.test {
            sut.onIntroContinue()
            assertEquals(HwConnectEffect.NavigateToSearching, awaitItem())
            assertEquals(HwConnectEffect.NavigateToFound("dev1", "Trezor Safe 3"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo).scan(includeBluetooth = true)
        assertEquals("dev1", sut.uiState.value.foundDeviceId)
        assertEquals("Trezor Safe 3", sut.uiState.value.deviceModel)
    }

    @Test
    fun `onIntroContinue can search without bluetooth`() = test {
        deviceState.value = TrezorState(nearbyDevices = persistentListOf(deviceInfo("usb1", model = "Safe 5")))
        whenever(hwWalletRepo.scan(includeBluetooth = false)).thenReturn(Result.success(emptyList()))

        sut.effects.test {
            sut.onIntroContinue(includeBluetooth = false)
            assertEquals(HwConnectEffect.NavigateToSearching, awaitItem())
            assertEquals(HwConnectEffect.NavigateToFound("usb1", "Trezor Safe 5"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo).scan(includeBluetooth = false)
        assertEquals("usb1", sut.uiState.value.foundDeviceId)
    }

    @Test
    fun `onIntroContinue surfaces search failures while searching`() = test {
        whenever(hwWalletRepo.scan(includeBluetooth = true)).thenReturn(Result.failure(AppError("scan failed")))

        sut.effects.test {
            sut.onIntroContinue()
            assertEquals(HwConnectEffect.NavigateToSearching, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        runCurrent()
        verify(hwWalletRepo).scan(includeBluetooth = true)
        assertTrue(sut.uiState.value.isSearching)
        assertEquals(SEARCH_ERROR, sut.uiState.value.errorMessage)
        sut.resetState()
    }

    @Test
    fun `onConnectClick scans usb before connecting a device seeded by route`() = test {
        val connectedFeatures = features(model = "Safe 3")
        whenever(hwWalletRepo.scan(includeBluetooth = false)).thenReturn(Result.success(emptyList()))
        whenever(hwWalletRepo.connect("usb1")).thenReturn(Result.success(connectedFeatures))
        sut.onFoundRoute(deviceId = "usb1", deviceModel = "Trezor")

        sut.effects.test {
            sut.onConnectClick()
            assertEquals(HwConnectEffect.NavigateToPaired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo).scan(includeBluetooth = false)
        verify(hwWalletRepo).connect("usb1")
    }

    @Test
    fun `onConnectClick connects the found device and advances to paired`() = test {
        givenDeviceFound()
        val connectedFeatures = features(model = "Safe 3")
        whenever(hwWalletRepo.connect("dev1")).thenReturn(Result.success(connectedFeatures))

        sut.effects.test {
            sut.onConnectClick()
            assertEquals(HwConnectEffect.NavigateToPaired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo).connect("dev1")
        assertEquals("dev1", sut.uiState.value.pairedDeviceId)
        assertEquals("Trezor Safe 3", sut.uiState.value.labelInput)
    }

    @Test
    fun `onConnectClick surfaces connect failures and allows retry`() = test {
        givenDeviceFound()
        runCurrent()
        whenever(hwWalletRepo.connect("dev1")).thenReturn(Result.failure(AppError("connect failed")))

        sut.onConnectClick()
        runCurrent()

        verify(hwWalletRepo).connect("dev1")
        assertFalse(sut.uiState.value.isConnecting)
        assertEquals(CONNECT_ERROR, sut.uiState.value.errorMessage)
        assertEquals("dev1", sut.uiState.value.foundDeviceId)
    }

    @Test
    fun `pairing code request surfaces the inline pair code step`() = test {
        sut.effects.test {
            needsPairingCode.value = true
            assertEquals(HwConnectEffect.NavigateToPairCode, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connected wallet updates the balance shown on the paired step`() = test {
        givenDeviceFound()
        val connectedFeatures = features(model = "Safe 3")
        whenever(hwWalletRepo.connect("dev1")).thenReturn(Result.success(connectedFeatures))
        sut.onConnectClick()

        wallets.value = persistentListOf(hwWallet("dev1", name = "Trezor Safe 3", balance = 10_562_411uL))

        assertEquals(10_562_411uL, sut.uiState.value.balanceSats)
        assertEquals("Trezor Safe 3", sut.uiState.value.deviceName)
    }

    @Test
    fun `onLabelChange caps the label input`() {
        sut.onLabelChange("a".repeat(51))

        assertEquals("a".repeat(50), sut.uiState.value.labelInput)
    }

    @Test
    fun `onFinishClick persists the edited label and dismisses`() = test {
        givenDeviceFound()
        val connectedFeatures = features(model = "Safe 3")
        whenever(hwWalletRepo.connect("dev1")).thenReturn(Result.success(connectedFeatures))
        sut.onConnectClick()
        sut.onLabelChange("My Cold Wallet")
        whenever(hwWalletRepo.setDeviceLabel("dev1", "My Cold Wallet")).thenReturn(Result.success(Unit))

        sut.effects.test {
            sut.onFinishClick()
            assertEquals(HwConnectEffect.Dismiss, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo).setDeviceLabel("dev1", "My Cold Wallet")
    }

    private suspend fun givenDeviceFound() {
        deviceState.value = TrezorState(nearbyDevices = persistentListOf(deviceInfo("dev1", model = "Safe 3")))
        whenever(hwWalletRepo.scan(includeBluetooth = true)).thenReturn(Result.success(emptyList()))
        sut.onIntroContinue()
    }

    private fun deviceInfo(id: String, model: String?) = TrezorDeviceInfo(
        id = id,
        transportType = TrezorTransportType.BLUETOOTH,
        name = null,
        path = "ble:$id",
        label = null,
        model = model,
        isBootloader = false,
    )

    private fun features(model: String?): TrezorFeatures {
        val features = mock<TrezorFeatures>()
        whenever(features.label).thenReturn(null)
        whenever(features.model).thenReturn(model)
        return features
    }

    private fun hwWallet(id: String, name: String, balance: ULong) = HwWallet(
        id = id,
        name = name,
        model = null,
        transportType = TransportType.BLUETOOTH,
        isConnected = true,
        balanceSats = balance,
        activities = persistentListOf(),
        deviceIds = persistentSetOf(id),
    )

    private companion object {
        const val CONNECT_ERROR = "Could not connect"
        const val SEARCH_ERROR = "Could not search"
    }
}
