package to.bitkit.ui.sheets.hardware

import android.content.Context
import app.cash.turbine.test
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorException
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorTransportType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.HwWallet
import to.bitkit.models.TransportType
import to.bitkit.repositories.ConnectedTrezorDevice
import to.bitkit.repositories.HwPassphraseAlreadyAddedError
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.TrezorState
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HwConnectViewModelTest : BaseUnitTest() {

    private val hwWalletRepo = mock<HwWalletRepo>()
    private val context = mock<Context>()
    private val pairingCodeRequestId = MutableStateFlow<Long?>(null)
    private val wallets = MutableStateFlow<ImmutableList<HwWallet>>(persistentListOf())
    private val deviceState = MutableStateFlow(TrezorState())

    private lateinit var sut: HwConnectViewModel

    @Before
    fun setUp() {
        whenever(hwWalletRepo.pairingCodeRequestId).thenReturn(pairingCodeRequestId)
        whenever(hwWalletRepo.wallets).thenReturn(wallets)
        whenever(hwWalletRepo.deviceState).thenReturn(deviceState)
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn(CONNECT_ERROR)
        whenever(context.getString(R.string.hardware__search_error)).thenReturn(SEARCH_ERROR)
        whenever(context.getString(R.string.hardware__device_busy)).thenReturn(DEVICE_BUSY_MESSAGE)
        whenever(context.getString(R.string.common__error)).thenReturn(ERROR_TITLE)
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
    fun `onConnectClick scans usb before connecting a usb attach route path`() = test {
        val devicePath = "/dev/bus/usb/001/002"
        val connectedFeatures = features(model = "Safe 3")
        whenever(hwWalletRepo.scan(includeBluetooth = false)).thenReturn(Result.success(emptyList()))
        whenever(hwWalletRepo.connect(devicePath)).thenReturn(Result.success(connectedFeatures))
        sut.onFoundRoute(deviceId = devicePath, deviceModel = "Trezor")

        sut.effects.test {
            sut.onConnectClick()
            assertEquals(HwConnectEffect.NavigateToPaired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo).scan(includeBluetooth = false)
        verify(hwWalletRepo).connect(devicePath)
    }

    @Test
    fun `onConnectClick does not usb rescan before connecting a bluetooth route device`() = test {
        val connectedFeatures = features(model = "Safe 7")
        whenever(hwWalletRepo.connect("ble-device-id")).thenReturn(Result.success(connectedFeatures))
        sut.onFoundRoute(deviceId = "ble-device-id", deviceModel = "Trezor Safe 7")

        sut.effects.test {
            sut.onConnectClick()
            assertEquals(HwConnectEffect.NavigateToPaired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo).connect("ble-device-id")
        verify(hwWalletRepo, never()).scan(includeBluetooth = false)
    }

    @Test
    fun `onConnectClick uses scanned device id for usb route path`() = test {
        val path = "/dev/bus/usb/001/002"
        val connectedFeatures = features(model = "Safe 5")
        val usbDevice = deviceInfo(
            id = "core-usb-id",
            model = "Safe 5",
            transportType = TrezorTransportType.USB,
            path = path,
        )
        whenever(hwWalletRepo.scan(includeBluetooth = false)).thenReturn(Result.success(listOf(usbDevice)))
        whenever(hwWalletRepo.connect("core-usb-id")).thenReturn(Result.success(connectedFeatures))
        sut.onFoundRoute(deviceId = path, deviceModel = "Trezor")

        sut.effects.test {
            sut.onConnectClick()
            assertEquals(HwConnectEffect.NavigateToPaired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo).connect("core-usb-id")
        assertEquals("core-usb-id", sut.uiState.value.foundDeviceId)
    }

    @Test
    fun `onConnectClick returns to found when connect fails from pair code`() = test {
        whenever(hwWalletRepo.scan(includeBluetooth = false)).thenReturn(Result.success(emptyList()))
        whenever(hwWalletRepo.connect("usb1")).thenReturn(Result.failure(AppError("connect failed")))
        sut.onFoundRoute(deviceId = "usb1", deviceModel = "Trezor Safe 5")

        sut.effects.test {
            sut.onConnectClick()
            assertEquals(HwConnectEffect.NavigateToFound("usb1", "Trezor Safe 5"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertFalse(sut.uiState.value.isConnecting)
        assertEquals(CONNECT_ERROR, sut.uiState.value.errorMessage)
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
    fun `onConnectClick surfaces device busy unlock prompt`() = test {
        givenDeviceFound()
        runCurrent()
        whenever(hwWalletRepo.connect("dev1")).thenReturn(Result.failure(AppError(TrezorException.DeviceBusy())))

        sut.onConnectClick()
        runCurrent()

        assertEquals(DEVICE_BUSY_MESSAGE, sut.uiState.value.errorMessage)
    }

    @Test
    fun `pairing code request surfaces the inline pair code step`() = test {
        sut.effects.test {
            pairingCodeRequestId.value = 1L
            assertEquals(HwConnectEffect.NavigateToPairCode(1L), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `repeated pairing code request emits a new inline pair code step`() = test {
        sut.effects.test {
            pairingCodeRequestId.value = 1L
            assertEquals(HwConnectEffect.NavigateToPairCode(1L), awaitItem())
            pairingCodeRequestId.value = 2L
            assertEquals(HwConnectEffect.NavigateToPairCode(2L), awaitItem())
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
    fun `onFinishClick persists the edited label and finishes`() = test {
        givenDeviceFound()
        val connectedFeatures = features(model = "Safe 3")
        whenever(hwWalletRepo.connect("dev1")).thenReturn(Result.success(connectedFeatures))
        sut.onConnectClick()
        wallets.value = persistentListOf(hwWallet("dev1", name = "Trezor Safe 3", balance = 0uL))
        sut.onLabelChange("My Cold Wallet")
        whenever(hwWalletRepo.setDeviceLabel("wallet-dev1", "My Cold Wallet")).thenReturn(Result.success(Unit))

        sut.effects.test {
            sut.onFinishClick()
            assertEquals(HwConnectEffect.Finish, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo).setDeviceLabel("wallet-dev1", "My Cold Wallet")
    }

    @Test
    fun `offers an already paired device when discovery finds nothing new`() = test {
        // Discovery skips known devices, so a paired Trezor only reaches the paired step — where
        // its passphrase wallets are added — through this fallback.
        val paired = deviceInfo("dev1", model = "Safe 3")
        deviceState.value = TrezorState(nearbyDevices = persistentListOf())
        whenever(hwWalletRepo.scan(includeBluetooth = true)).thenReturn(Result.success(listOf(paired)))
        whenever { hwWalletRepo.hasKnownDevice("dev1") }.thenReturn(true)

        sut.effects.test {
            sut.onIntroContinue()
            assertEquals(HwConnectEffect.NavigateToSearching, awaitItem())
            assertEquals(HwConnectEffect.NavigateToFound("dev1", "Trezor Safe 3"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("dev1", sut.uiState.value.foundDeviceId)
    }

    @Test
    fun `keeps searching when the only device found is neither new nor paired`() = test {
        deviceState.value = TrezorState(nearbyDevices = persistentListOf())
        whenever(hwWalletRepo.scan(includeBluetooth = true))
            .thenReturn(Result.success(listOf(deviceInfo("other", model = "Safe 3"))))
        whenever { hwWalletRepo.hasKnownDevice("other") }.thenReturn(false)

        sut.effects.test {
            sut.onIntroContinue()
            assertEquals(HwConnectEffect.NavigateToSearching, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(sut.uiState.value.isSearching)
        sut.resetState()
    }

    @Test
    fun `onPassphraseSubmit watches the hidden wallet and advances to its paired step`() = test {
        givenPairedDevice()
        whenever(hwWalletRepo.connectWithPassphrase("dev1", "secret"))
            .thenReturn(Result.success("hidden-wallet"))
        sut.onPassphraseClick()
        sut.onPassphraseChange("secret")

        sut.effects.test {
            sut.onPassphraseSubmit()
            assertEquals(HwConnectEffect.NavigateToPassphrasePaired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("hidden-wallet", sut.uiState.value.pairedWalletId)
        assertEquals("", sut.uiState.value.passphraseInput)
        assertFalse(sut.uiState.value.isSubmittingPassphrase)
    }

    @Test
    fun `paired step follows the identity being paired on a device holding several wallets`() = test {
        givenPairedDevice()
        whenever(hwWalletRepo.connectWithPassphrase("dev1", "secret"))
            .thenReturn(Result.success("hidden-wallet"))
        sut.onPassphraseClick()
        sut.onPassphraseChange("secret")
        sut.onPassphraseSubmit()
        runCurrent()

        wallets.value = persistentListOf(
            hwWallet("dev1", name = "Trezor Safe 3", balance = 10uL),
            hwWallet("dev1", name = "Hidden Safe 3", balance = 40uL, walletId = "hidden-wallet"),
        )

        assertEquals("Hidden Safe 3", sut.uiState.value.deviceName)
        assertEquals(40uL, sut.uiState.value.balanceSats)
        assertEquals("Hidden Safe 3", sut.uiState.value.labelInput)
    }

    @Test
    fun `reconnecting a device with several wallets shows the session identity and its saved name`() = test {
        // Both identities share the transport id, so only the live session says which one was
        // opened, and the paired step must show the name that identity was saved under.
        val hidden = hwWallet("dev1", name = "Pass A", balance = 10_000uL, walletId = "hidden-wallet")
        val standard = hwWallet("dev1", name = "No Pass", balance = 27uL, walletId = "standard-wallet")
        wallets.value = persistentListOf(hidden, standard)
        deviceState.value = TrezorState(
            nearbyDevices = persistentListOf(deviceInfo("dev1", model = "Safe 3")),
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = "standard-wallet"),
        )
        val connectedFeatures = features(model = "Safe 3")
        whenever(hwWalletRepo.scan(includeBluetooth = true)).thenReturn(Result.success(emptyList()))
        whenever(hwWalletRepo.connect("dev1")).thenReturn(Result.success(connectedFeatures))
        sut.onIntroContinue()
        runCurrent()

        sut.onConnectClick()
        runCurrent()

        assertEquals("standard-wallet", sut.uiState.value.pairedWalletId)
        assertEquals("No Pass", sut.uiState.value.deviceName)
        assertEquals("No Pass", sut.uiState.value.labelInput)
    }

    @Test
    fun `finishing labels the session identity while the wallet list catches up`() = test {
        // The paired wallet has not reached the list yet, so the typed name would otherwise be
        // dropped and the flow closed instead of finished.
        val connectedFeatures = features(model = "Safe 3")
        deviceState.value = TrezorState(
            nearbyDevices = persistentListOf(deviceInfo("dev1", model = "Safe 3")),
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = "wallet-1"),
        )
        whenever(hwWalletRepo.scan(includeBluetooth = true)).thenReturn(Result.success(emptyList()))
        whenever(hwWalletRepo.connect("dev1")).thenReturn(Result.success(connectedFeatures))
        whenever(hwWalletRepo.setDeviceLabel("wallet-1", "My Trezor")).thenReturn(Result.success(Unit))
        sut.onIntroContinue()
        runCurrent()
        sut.onConnectClick()
        runCurrent()
        sut.onLabelChange("My Trezor")

        sut.effects.test {
            sut.onFinishClick()
            assertEquals(HwConnectEffect.Finish, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo).setDeviceLabel("wallet-1", "My Trezor")
    }

    @Test
    fun `finishing completes the flow even when no identity resolved`() = test {
        givenDeviceFound()
        val connectedFeatures = features(model = "Safe 3")
        whenever(hwWalletRepo.connect("dev1")).thenReturn(Result.success(connectedFeatures))
        sut.onConnectClick()
        runCurrent()

        sut.effects.test {
            sut.onFinishClick()
            assertEquals(HwConnectEffect.Finish, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(hwWalletRepo, never()).setDeviceLabel(any(), any())
    }

    @Test
    fun `persists the label of the wallet left behind when adding a passphrase wallet`() = test {
        // Each identity is named on its own paired step, so the standard wallet's name must be
        // kept when the user moves on to add a passphrase wallet instead of finishing.
        givenPairedDevice()
        wallets.value = persistentListOf(hwWallet("dev1", name = "Trezor Safe 3", balance = 10uL))
        whenever(hwWalletRepo.setDeviceLabel("wallet-dev1", "My Savings")).thenReturn(Result.success(Unit))
        sut.onLabelChange("My Savings")

        sut.onPassphraseClick()
        runCurrent()

        verify(hwWalletRepo).setDeviceLabel("wallet-dev1", "My Savings")
    }

    @Test
    fun `keeps the typed label when the new wallet is published afterwards`() = test {
        // The store publishes a newly watched identity asynchronously, so its emission can land
        // after the user has already named it; the entered name must survive and be persisted.
        givenPairedDevice()
        whenever(hwWalletRepo.connectWithPassphrase("dev1", "secret"))
            .thenReturn(Result.success("hidden-wallet"))
        whenever(hwWalletRepo.setDeviceLabel("hidden-wallet", "My Hidden")).thenReturn(Result.success(Unit))
        sut.onPassphraseClick()
        sut.onPassphraseChange("secret")
        sut.onPassphraseSubmit()
        runCurrent()

        sut.onLabelChange("My Hidden")
        wallets.value = persistentListOf(
            hwWallet("dev1", name = "Trezor Safe 3", balance = 0uL, walletId = "hidden-wallet"),
        )

        assertEquals("My Hidden", sut.uiState.value.labelInput)

        sut.onFinishClick()
        runCurrent()

        verify(hwWalletRepo).setDeviceLabel("hidden-wallet", "My Hidden")
    }

    @Test
    fun `onPassphraseSubmit keeps the passphrase out of state when the wallet is already watched`() = test {
        givenPairedDevice()
        whenever(context.getString(R.string.hardware__passphrase_duplicate)).thenReturn(DUPLICATE_ERROR)
        whenever(hwWalletRepo.connectWithPassphrase("dev1", "secret"))
            .thenReturn(Result.failure(HwPassphraseAlreadyAddedError()))
        sut.onPassphraseClick()
        sut.onPassphraseChange("secret")

        ToastEventBus.events.test {
            sut.onPassphraseSubmit()
            runCurrent()
            assertEquals(DUPLICATE_ERROR, awaitItem().description)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("", sut.uiState.value.passphraseInput)
        assertEquals(null, sut.uiState.value.pairedWalletId)
    }

    @Test
    fun `resetState drops the entered passphrase`() = test {
        givenPairedDevice()
        sut.onPassphraseClick()
        sut.onPassphraseChange("secret")

        sut.resetState()

        assertEquals("", sut.uiState.value.passphraseInput)
    }

    private suspend fun TestScope.givenPairedDevice() {
        givenDeviceFound()
        val connectedFeatures = features(model = "Safe 3")
        whenever(hwWalletRepo.connect("dev1")).thenReturn(Result.success(connectedFeatures))
        sut.onConnectClick()
        runCurrent()
    }

    private suspend fun givenDeviceFound() {
        deviceState.value = TrezorState(nearbyDevices = persistentListOf(deviceInfo("dev1", model = "Safe 3")))
        whenever(hwWalletRepo.scan(includeBluetooth = true)).thenReturn(Result.success(emptyList()))
        sut.onIntroContinue()
    }

    private fun deviceInfo(
        id: String,
        model: String?,
        transportType: TrezorTransportType = TrezorTransportType.BLUETOOTH,
        path: String = "ble:$id",
    ) = TrezorDeviceInfo(
        id = id,
        transportType = transportType,
        name = null,
        path = path,
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

    private fun hwWallet(
        deviceId: String,
        name: String,
        balance: ULong,
        walletId: String = "wallet-$deviceId",
    ) = HwWallet(
        id = walletId,
        name = name,
        model = null,
        transportType = TransportType.BLUETOOTH,
        isConnected = true,
        balanceSats = balance,
        activities = persistentListOf(),
        deviceIds = persistentSetOf(deviceId),
    )

    private companion object {
        const val CONNECT_ERROR = "Could not connect"
        const val DUPLICATE_ERROR = "Already watching this passphrase wallet"
        const val ERROR_TITLE = "Error"
        const val SEARCH_ERROR = "Could not search"
        const val DEVICE_BUSY_MESSAGE = "Your Trezor is busy. Unlock it on the device, then try again."
    }
}
