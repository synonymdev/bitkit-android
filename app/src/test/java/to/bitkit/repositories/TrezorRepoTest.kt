package to.bitkit.repositories

import android.content.Context
import android.content.SharedPreferences
import com.synonym.bitkitcore.CoinSelection
import com.synonym.bitkitcore.ComposeOutput
import com.synonym.bitkitcore.ComposeParams
import com.synonym.bitkitcore.TrezorAddressResponse
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorException
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorPublicKeyResponse
import com.synonym.bitkitcore.TrezorSignedMessageResponse
import com.synonym.bitkitcore.TrezorTransportType
import com.synonym.bitkitcore.TrezorTransportWriteResult
import com.synonym.bitkitcore.WalletSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.HwWalletStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.ext.isTrezorDeviceBusy
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.models.toCoreNetwork
import to.bitkit.services.TrezorService
import to.bitkit.services.TrezorTransport
import to.bitkit.services.TrezorUiHandler
import to.bitkit.services.TrezorWalletMode
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
@Suppress("LargeClass")
class TrezorRepoTest : BaseUnitTest() {

    companion object Fixtures {
        private const val DEVICE_ID = "device-123"
        private const val DEVICE_NAME = "Trezor Model T"
        private const val DEVICE_PATH = "/dev/trezor0"
        private const val DEVICE_LABEL = "My Trezor"
        private const val DEVICE_MODEL = "T"
        private const val TEST_MESSAGE = "Hello Trezor"
        private const val TEST_SIGNATURE = "signature123"
        private const val TEST_ADDRESS = "bc1qtest"
        private const val DEVICE_BUSY_MESSAGE = "Your Trezor is busy. Unlock it on the device, then try again."

        /** The address types the store keys account xpubs by. */
        private val ALL_ADDRESS_TYPE_KEYS = listOf("legacy", "nestedSegwit", "nativeSegwit", "taproot")
    }

    @get:Rule(order = 1)
    val tempFolder = TemporaryFolder()

    private val context = mock<Context>()
    private val trezorService = mock<TrezorService>()
    private val trezorTransport = mock<TrezorTransport>()
    private val trezorUiHandler = mock<TrezorUiHandler>()
    private val hwWalletStore = mock<HwWalletStore>()
    private val settingsStore = mock<SettingsStore>()
    private val prefs = mock<SharedPreferences>()
    private val prefsEditor = mock<SharedPreferences.Editor>()
    private val settingsData = MutableStateFlow(SettingsData())

    private lateinit var sut: TrezorRepo

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getString(any(), anyOrNull())).thenReturn(null)
        whenever(prefs.edit()).thenReturn(prefsEditor)
        whenever(prefsEditor.putString(any(), any())).thenReturn(prefsEditor)
        whenever(trezorTransport.needsPairingCode).thenReturn(MutableStateFlow(false))
        whenever(trezorTransport.externalDisconnect).thenReturn(MutableSharedFlow())
        whenever(trezorTransport.transportRestored).thenReturn(MutableSharedFlow())
        whenever(trezorTransport.hasUsbPermission(any())).thenReturn(true)
        whenever(trezorTransport.disconnectDevice(any())).thenReturn(
            TrezorTransportWriteResult(success = true, error = "", errorCode = null)
        )
        whenever(trezorUiHandler.needsPinEntry).thenReturn(MutableStateFlow(false))
        whenever(trezorUiHandler.currentSelection()).thenReturn(WalletSelection.Standard)
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever(context.filesDir).thenReturn(tempFolder.root)
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn("Could not connect to your Trezor.")
        whenever(context.getString(R.string.hardware__device_busy)).thenReturn(DEVICE_BUSY_MESSAGE)
        whenever { hwWalletStore.loadKnownDevices() }.thenReturn(emptyList())
        whenever { hwWalletStore.loadPendingNames() }.thenReturn(emptyMap())
        whenever { hwWalletStore.setPendingName(any(), anyOrNull()) }.thenReturn(Unit)
        stubAccountXpubFetch()
    }

    private fun createSut(): TrezorRepo = TrezorRepo(
        context = context,
        trezorService = trezorService,
        trezorTransport = trezorTransport,
        trezorUiHandler = trezorUiHandler,
        hwWalletStore = hwWalletStore,
        settingsStore = settingsStore,
        clock = Clock.System,
        ioDispatcher = testDispatcher,
    )

    @Suppress("LongParameterList")
    private fun mockDeviceInfo(
        id: String = DEVICE_ID,
        transportType: TrezorTransportType = TrezorTransportType.USB,
        name: String? = DEVICE_NAME,
        path: String = DEVICE_PATH,
        label: String? = DEVICE_LABEL,
        model: String? = DEVICE_MODEL,
        isBootloader: Boolean = false,
    ) = TrezorDeviceInfo(
        id = id,
        transportType = transportType,
        name = name,
        path = path,
        label = label,
        model = model,
        isBootloader = isBootloader,
    )

    private fun mockFeatures(
        label: String? = DEVICE_LABEL,
        model: String? = DEVICE_MODEL,
        pinProtection: Boolean? = null,
        unlocked: Boolean? = null,
        deviceId: String? = null,
    ): TrezorFeatures = mock {
        on { this.label }.thenReturn(label)
        on { this.model }.thenReturn(model)
        on { this.deviceId }.thenReturn(deviceId)
        on { this.pinProtection }.thenReturn(pinProtection)
        on { this.unlocked }.thenReturn(unlocked)
    }

    private fun mockPublicKeyResponse(
        xpub: String,
        path: String,
    ) = TrezorPublicKeyResponse(
        xpub = xpub,
        path = path,
        publicKey = "pubkey",
        chainCode = "chaincode",
        fingerprint = 0u,
        depth = 3u,
        rootFingerprint = 0u,
    )

    private fun stubAccountXpubFetch() {
        whenever {
            trezorService.getPublicKey(
                path = any(),
                coin = anyOrNull(),
                showOnTrezor = eq(false),
            )
        }.thenAnswer {
            mockPublicKeyResponse(
                xpub = "xpub-${it.getArgument<String>(0)}",
                path = it.getArgument(0),
            )
        }
    }

    @Suppress("LongParameterList")
    private fun mockKnownDevice(
        id: String = DEVICE_ID,
        name: String? = DEVICE_NAME,
        path: String = DEVICE_PATH,
        label: String? = DEVICE_LABEL,
        model: String? = DEVICE_MODEL,
        transportType: TransportType = TransportType.USB,
        xpubs: Map<String, String> = emptyMap(),
        customLabel: String? = null,
        walletId: String = "wallet-id",
        passphraseProtected: Boolean = false,
        trezorDeviceId: String? = null,
    ) = KnownDevice(
        id = id,
        name = name,
        path = path,
        transportType = transportType,
        label = label,
        model = model,
        lastConnectedAt = 123L,
        xpubs = xpubs,
        customLabel = customLabel,
        walletId = walletId,
        passphraseProtected = passphraseProtected,
        trezorDeviceId = trezorDeviceId,
    )

    // region initialize

    @Test
    fun `initialize should load known devices on success`() = test {
        val knownDevice = mockKnownDevice()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        sut = createSut()

        val result = sut.initialize()

        assertTrue(result.isSuccess)
        assertEquals(listOf(knownDevice), sut.state.value.knownDevices)
        assertNull(sut.state.value.error)
    }

    @Test
    fun `initialize leaves wallet id blank until xpubs are available`() = test {
        val knownDevice = mockKnownDevice(walletId = "")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        sut = createSut()

        val result = sut.initialize()

        assertTrue(result.isSuccess)
        verify(hwWalletStore, never()).saveKnownDevices(any())
        assertEquals("", sut.state.value.knownDevices.single().walletId)
    }

    @Test
    fun `initialize should reuse completed setup`() = test {
        sut = createSut()

        val firstResult = sut.initialize()
        val secondResult = sut.initialize()

        assertTrue(firstResult.isSuccess)
        assertTrue(secondResult.isSuccess)
        verify(trezorService, times(1)).initialize(anyOrNull())
    }

    @Test
    fun `initialize should set error on failure`() = test {
        whenever(trezorService.initialize(anyOrNull())).thenThrow(RuntimeException("init failed"))
        sut = createSut()

        val result = sut.initialize()

        assertTrue(result.isFailure)
        assertEquals("init failed", sut.state.value.error)
    }

    // endregion

    // region scan

    @Test
    fun `scan should return devices and update nearbyDevices state`() = test {
        val devices = listOf(mockDeviceInfo())
        whenever(trezorService.scan()).thenReturn(devices)
        sut = createSut()

        val result = sut.scan()

        assertTrue(result.isSuccess)
        assertEquals(devices, result.getOrNull())
        assertEquals(devices, sut.state.value.nearbyDevices)
        assertFalse(sut.state.value.isScanning)
    }

    @Test
    fun `scan should initialize Trezor before scanning`() = test {
        val devices = listOf(mockDeviceInfo())
        whenever(trezorService.scan()).thenReturn(devices)
        sut = createSut()

        val result = sut.scan()

        assertTrue(result.isSuccess)
        verify(trezorService).initialize(anyOrNull())
        verify(trezorService).scan()
    }

    @Test
    fun `scan should pass bluetooth flag to service`() = test {
        val devices = listOf(mockDeviceInfo())
        whenever(trezorService.scan(includeBluetooth = false)).thenReturn(devices)
        sut = createSut()

        val result = sut.scan(includeBluetooth = false)

        assertTrue(result.isSuccess)
        assertEquals(devices, result.getOrNull())
        verify(trezorService).scan(includeBluetooth = false)
    }

    @Test
    fun `scan should exclude known devices from nearbyDevices state`() = test {
        val knownDevice = mockKnownDevice()
        val known = mockDeviceInfo()
        val nearby = mockDeviceInfo(id = "device-456", path = "/dev/trezor1")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.scan()).thenReturn(listOf(known, nearby))
        sut = createSut()

        sut.initialize()
        val result = sut.scan()

        assertTrue(result.isSuccess)
        assertEquals(listOf(known, nearby), result.getOrNull())
        assertEquals(listOf(nearby), sut.state.value.nearbyDevices)
    }

    @Test
    fun `scan should set error on failure`() = test {
        whenever(trezorService.scan()).thenThrow(RuntimeException("scan failed"))
        sut = createSut()

        val result = sut.scan()

        assertTrue(result.isFailure)
        assertFalse(sut.state.value.isScanning)
        assertEquals("scan failed", sut.state.value.error)
    }

    // endregion

    // region connect

    @Test
    fun `transport restored auto-reconnects to a known device`() = test {
        val transportRestored = MutableSharedFlow<TransportType>()
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorTransport.transportRestored).thenReturn(transportRestored)
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any(), eq(false))).thenReturn(features)
        sut = createSut()

        transportRestored.emit(TransportType.USB)
        advanceUntilIdle()

        assertNotNull(sut.state.value.connected)
    }

    @Test
    fun `transport restored retries reconnect until the device is discoverable`() = test {
        val transportRestored = MutableSharedFlow<TransportType>()
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorTransport.transportRestored).thenReturn(transportRestored)
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        whenever(trezorService.isConnected()).thenReturn(false)
        // A device is usually not advertising yet right after the transport returns.
        whenever(trezorService.scan()).thenReturn(emptyList(), listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any(), eq(false))).thenReturn(features)
        sut = createSut()

        transportRestored.emit(TransportType.USB)
        advanceUntilIdle()

        assertNotNull(sut.state.value.connected)
        verify(trezorService, times(2)).scan()
    }

    @Test
    fun `transport restored stops auto-reconnect retries on device busy`() = test {
        val transportRestored = MutableSharedFlow<TransportType>()
        val device = mockDeviceInfo()
        whenever(trezorTransport.transportRestored).thenReturn(transportRestored)
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any(), eq(false)))
            .doAnswer { throw TrezorException.DeviceBusy() }
        sut = createSut()

        transportRestored.emit(TransportType.USB)
        advanceUntilIdle()

        assertNull(sut.state.value.connected)
        verify(trezorService, times(1)).connect(eq(DEVICE_ID), any(), eq(false))
    }

    @Test
    fun `reconnect prefers the transport that came back`() = test {
        val features = mockFeatures()
        val bleDevice = mockDeviceInfo(id = "ble-1", transportType = TrezorTransportType.BLUETOOTH, path = "ble-path")
        val usbDevice = mockDeviceInfo(id = "usb-1", transportType = TrezorTransportType.USB, path = "usb-path")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(
            listOf(
                mockKnownDevice(id = "ble-1", transportType = TransportType.BLUETOOTH),
                mockKnownDevice(id = "usb-1"),
            ),
        )
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(bleDevice, usbDevice))
        whenever(trezorService.connect(eq("usb-1"), any(), eq(false))).thenReturn(features)
        sut = createSut()

        sut.onTransportRestored(TransportType.USB)
        advanceUntilIdle()

        verify(trezorService).connect(eq("usb-1"), any(), eq(false))
        verify(trezorService, never()).connect(eq("ble-1"), any())
    }

    @Test
    fun `repeated transport restored triggers run a single reconnect`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any(), eq(false))).thenReturn(features)
        sut = createSut()

        repeat(3) { sut.onTransportRestored(TransportType.USB) }
        advanceUntilIdle()

        assertNotNull(sut.state.value.connected)
        verify(trezorService, times(1)).scan()
        verify(trezorService, times(1)).connect(eq(DEVICE_ID), any(), eq(false))
    }

    @Test
    fun `autoReconnect bails while device awaits pin entry`() = test {
        whenever(trezorUiHandler.needsPinEntry).thenReturn(MutableStateFlow(true))
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        sut = createSut()

        val result = sut.autoReconnect()

        assertTrue(result.isFailure)
        verify(trezorService, never()).disconnect()
        verify(trezorService, never()).scan()
    }

    @Test
    fun `transport restored skips reconnect while device awaits pairing code`() = test {
        val transportRestored = MutableSharedFlow<TransportType>()
        whenever(trezorTransport.transportRestored).thenReturn(transportRestored)
        whenever(trezorTransport.needsPairingCode).thenReturn(MutableStateFlow(true))
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        sut = createSut()

        transportRestored.emit(TransportType.USB)
        advanceUntilIdle()

        verify(trezorService, never()).disconnect()
        verify(trezorService, never()).scan()
    }

    @Test
    fun `bluetooth restore reconnects only after pairing request clears`() = test {
        val transportRestored = MutableSharedFlow<TransportType>()
        val needsPairingCode = MutableStateFlow(true)
        val knownDevice = mockKnownDevice()
        val device = mockDeviceInfo()
        val features = mockFeatures()
        whenever(trezorTransport.transportRestored).thenReturn(transportRestored)
        whenever(trezorTransport.needsPairingCode).thenReturn(needsPairingCode)
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any(), eq(false))).thenReturn(features)
        sut = createSut()

        transportRestored.emit(TransportType.BLUETOOTH)
        advanceUntilIdle()
        verify(trezorService, never()).scan()

        needsPairingCode.value = false
        transportRestored.emit(TransportType.BLUETOOTH)
        advanceUntilIdle()

        verify(trezorService).scan()
        verify(trezorService).connect(eq(DEVICE_ID), any(), eq(false))
    }

    @Test
    fun `onTransportRestored auto-reconnects to a known device`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any(), eq(false))).thenReturn(features)
        sut = createSut()

        sut.onTransportRestored(TransportType.USB)
        advanceUntilIdle()

        assertNotNull(sut.state.value.connected)
    }

    @Test
    fun `app foreground auto-reconnects to a known bluetooth device`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo(
            transportType = TrezorTransportType.BLUETOOTH,
            path = "ble-path",
        )
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(
            listOf(mockKnownDevice(transportType = TransportType.BLUETOOTH))
        )
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any(), eq(false))).thenReturn(features)
        sut = createSut()

        sut.onAppForegrounded()
        advanceUntilIdle()

        assertNotNull(sut.state.value.connected)
        verify(trezorService).connect(eq(DEVICE_ID), any(), eq(false))
    }

    @Test
    fun `app foreground skips reconnect without a known bluetooth device`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        sut = createSut()

        sut.onAppForegrounded()
        advanceUntilIdle()

        verify(trezorService, never()).scan()
    }

    @Test
    fun `warmUpKnownDevice connects to the requested bluetooth device`() = test {
        val bleDeviceId = "ble:57:21:A7:F9:DD:AD"
        val knownDevice = mockKnownDevice(
            id = bleDeviceId,
            path = bleDeviceId,
            transportType = TransportType.BLUETOOTH,
        )
        val device = mockDeviceInfo(
            id = bleDeviceId,
            path = bleDeviceId,
            transportType = TrezorTransportType.BLUETOOTH,
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(bleDeviceId), any())).thenReturn(features)
        sut = createSut()

        sut.initialize()
        sut.warmUpKnownDevice(bleDeviceId)
        advanceUntilIdle()

        assertEquals(bleDeviceId, sut.state.value.connectedDeviceId())
        verify(trezorService).connect(eq(bleDeviceId), any())
    }

    @Test
    fun `warmUpKnownDevice skips non-bluetooth devices`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        sut = createSut()

        sut.initialize()
        sut.warmUpKnownDevice(DEVICE_ID)
        advanceUntilIdle()

        verify(trezorService, never()).scan()
    }

    @Test
    fun `warmUpKnownDevice skips when device is already connected`() = test {
        val bleDeviceId = "ble:57:21:A7:F9:DD:AD"
        val knownDevice = mockKnownDevice(
            id = bleDeviceId,
            path = bleDeviceId,
            transportType = TransportType.BLUETOOTH,
        )
        val device = mockDeviceInfo(
            id = bleDeviceId,
            path = bleDeviceId,
            transportType = TrezorTransportType.BLUETOOTH,
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.isConnected()).thenReturn(false, true)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(bleDeviceId), any())).thenReturn(features)
        sut = createSut()
        sut.initialize()
        sut.warmUpKnownDevice(bleDeviceId)
        advanceUntilIdle()

        sut.warmUpKnownDevice(bleDeviceId)
        advanceUntilIdle()

        verify(trezorService, times(1)).scan()
    }

    @Test
    fun `onTransportRestored skips usb device without permission`() = test {
        val device = mockDeviceInfo()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorTransport.hasUsbPermission(DEVICE_PATH)).thenReturn(false)
        sut = createSut()

        sut.onTransportRestored(TransportType.USB)
        advanceUntilIdle()

        assertNull(sut.state.value.connected)
        verify(trezorService, never()).connect(eq(DEVICE_ID), any(), eq(false))
    }

    @Test
    fun `autoReconnect resets a stale session before scanning`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice()))
        // The core still reports a session although the transport dropped underneath it.
        whenever(trezorService.isConnected()).thenReturn(true)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any(), eq(false))).thenReturn(features)
        sut = createSut()
        sut.initialize()

        val result = sut.autoReconnect()

        assertTrue(result.isSuccess)
        verify(trezorService).disconnect()
        assertNotNull(sut.state.value.connected)
    }

    @Test
    fun `transport restored does not reconnect when a device is already connected`() = test {
        val transportRestored = MutableSharedFlow<TransportType>()
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorTransport.transportRestored).thenReturn(transportRestored)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        sut = createSut()
        sut.scan()
        sut.connect(DEVICE_ID)

        transportRestored.emit(TransportType.USB)
        advanceUntilIdle()

        verify(trezorService, times(1)).scan()
    }

    @Test
    fun `external disconnect clears the connected device while no screen observes it`() = test {
        val externalDisconnect = MutableSharedFlow<String>()
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorTransport.externalDisconnect).thenReturn(externalDisconnect)
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()
        sut.scan()
        sut.connect(DEVICE_ID)
        assertNotNull(sut.state.value.connected)

        externalDisconnect.emit(DEVICE_PATH)

        assertNull(sut.state.value.connected)
        assertEquals("Device disconnected", sut.state.value.error)
    }

    @Test
    fun `connect should return features and update connectedDevice state`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        // First scan to populate nearbyDevices
        sut.scan()

        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        assertEquals(features, result.getOrNull())
        assertEquals(features, sut.state.value.connectedDevice())
        assertEquals(DEVICE_ID, sut.state.value.connectedDeviceId())
        assertFalse(sut.state.value.isConnecting)
    }

    @Test
    fun `connect should persist connected device as known device`() = test {
        val features = mockFeatures(label = "Savings", model = "Safe 5")
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        val saved = captor.firstValue.single()
        assertEquals(DEVICE_ID, saved.id)
        assertEquals(TransportType.USB, saved.transportType)
        assertEquals("Savings", saved.label)
        assertEquals("Safe 5", saved.model)
        assertEquals("", saved.walletId)
    }

    @Test
    fun `connect reuses wallet id from same xpub wallet`() = test {
        val walletId = "hardware-wallet-id"
        val nativeSegwitPath = "m/84'/1'/0'"
        val previousDevice = mockKnownDevice(
            id = "ble-device",
            path = "ble:AA:BB",
            transportType = TransportType.BLUETOOTH,
            xpubs = mapOf("nativeSegwit" to "same-native-xpub"),
            walletId = walletId,
        )
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(previousDevice))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(
            trezorService.getPublicKey(
                path = any(),
                coin = anyOrNull(),
                showOnTrezor = eq(false),
            )
        ).thenAnswer {
            val path = it.getArgument<String>(0)
            if (path == nativeSegwitPath) {
                mockPublicKeyResponse(xpub = "same-native-xpub", path = nativeSegwitPath)
            } else {
                throw AppError("xpub failed")
            }
        }
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertEquals(setOf(walletId), captor.firstValue.map { it.walletId }.toSet())
    }

    @Test
    fun `connect adds a passphrase wallet next to the standard one on the same device`() = test {
        val standard = mockKnownDevice(
            xpubs = mapOf("nativeSegwit" to "standard-native-xpub"),
            customLabel = "Savings",
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(standard))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        whenever(trezorUiHandler.currentSelection()).thenReturn(WalletSelection.Hidden("secret"))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        val saved = captor.firstValue
        assertEquals(2, saved.size)
        assertEquals(standard, saved.first())
        val hidden = saved.last()
        assertEquals(DEVICE_ID, hidden.id)
        assertTrue(hidden.passphraseProtected)
        assertEquals("Savings", standard.customLabel)
        assertNull(hidden.customLabel)
        assertTrue(hidden.xpubs.values.none { it in standard.xpubs.values })
    }

    @Test
    fun `connect keeps the custom label when the wallet appears on a new transport`() = test {
        // A restarted bridge or a fresh usb handle gives the same wallet a new transport id; the
        // name the user set is the wallet's, and the tile prefers the connected entry, so losing it
        // here renames the wallet to the device's own name and finishing writes that over the rest.
        val sharedKey = "shared-native-xpub"
        val onOldTransport = mockKnownDevice(
            id = "old-transport",
            path = "bridge:1",
            xpubs = ALL_ADDRESS_TYPE_KEYS.associateWith { sharedKey },
            customLabel = "No Pass",
            walletId = "standard-wallet",
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(onOldTransport))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        whenever(
            trezorService.getPublicKey(path = any(), coin = anyOrNull(), showOnTrezor = eq(false))
        ).thenAnswer { mockPublicKeyResponse(xpub = sharedKey, path = it.getArgument(0)) }
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        val added = captor.firstValue.single { it.id == DEVICE_ID }
        assertEquals("No Pass", added.customLabel)
        assertEquals("standard-wallet", added.walletId)
    }

    @Test
    fun `connect adopts the pending name of a wallet paired again`() = test {
        // Restored from a backup, or kept when the wallet was removed: pairing takes the name over.
        val sharedKey = "shared-native-xpub"
        val unnamed = mockKnownDevice(
            id = DEVICE_ID,
            xpubs = ALL_ADDRESS_TYPE_KEYS.associateWith { sharedKey },
            customLabel = null,
            walletId = "standard-wallet",
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(unnamed))
        whenever { hwWalletStore.loadPendingNames() }.thenReturn(mapOf("standard-wallet" to "Cold Storage"))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        whenever(
            trezorService.getPublicKey(path = any(), coin = anyOrNull(), showOnTrezor = eq(false))
        ).thenAnswer { mockPublicKeyResponse(xpub = sharedKey, path = it.getArgument(0)) }
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertEquals("Cold Storage", captor.firstValue.single { it.id == DEVICE_ID }.customLabel)
        // Consumed, so clearing the name later cannot fall back to it again.
        verify(hwWalletStore).setPendingName("standard-wallet", null)
    }

    @Test
    fun `connect prefers the stored custom label over a pending name`() = test {
        val sharedKey = "shared-native-xpub"
        val stored = mockKnownDevice(
            id = DEVICE_ID,
            xpubs = ALL_ADDRESS_TYPE_KEYS.associateWith { sharedKey },
            customLabel = "Renamed Here",
            walletId = "standard-wallet",
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(stored))
        whenever { hwWalletStore.loadPendingNames() }.thenReturn(mapOf("standard-wallet" to "Cold Storage"))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        whenever(
            trezorService.getPublicKey(path = any(), coin = anyOrNull(), showOnTrezor = eq(false))
        ).thenAnswer { mockPublicKeyResponse(xpub = sharedKey, path = it.getArgument(0)) }
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertEquals("Renamed Here", captor.firstValue.single { it.id == DEVICE_ID }.customLabel)
        // The pending name lost, so it is stale: dropping it keeps a later rename from falling back to it.
        verify(hwWalletStore).setPendingName("standard-wallet", null)
    }

    @Test
    fun `connect leaves the pending name of another identity on the device alone`() = test {
        val sharedKey = "shared-native-xpub"
        val unnamed = mockKnownDevice(
            id = DEVICE_ID,
            xpubs = ALL_ADDRESS_TYPE_KEYS.associateWith { sharedKey },
            customLabel = null,
            walletId = "standard-wallet",
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(unnamed))
        // A passphrase wallet on the same device derives its own keys, so its name is its own.
        whenever { hwWalletStore.loadPendingNames() }.thenReturn(mapOf("hidden-wallet" to "Hidden Stash"))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        whenever(
            trezorService.getPublicKey(path = any(), coin = anyOrNull(), showOnTrezor = eq(false))
        ).thenAnswer { mockPublicKeyResponse(xpub = sharedKey, path = it.getArgument(0)) }
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertNull(captor.firstValue.single { it.id == DEVICE_ID }.customLabel)
        verify(hwWalletStore, never()).setPendingName(any(), anyOrNull())
    }

    @Test
    fun `connect supersedes entries of a seed the device no longer carries`() = test {
        // A wiped and restored device reports a new device id and different keys, so nothing would
        // ever match those entries again; they would linger as wallets that can never sign.
        val stale = mockKnownDevice(
            xpubs = mapOf("nativeSegwit" to "old-seed-xpub"),
            trezorDeviceId = "old-device-id",
        )
        val features = mockFeatures(deviceId = "new-device-id")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(stale))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        val saved = captor.firstValue.single()
        assertEquals("new-device-id", saved.trezorDeviceId)
        assertTrue(saved.xpubs.values.none { it == "old-seed-xpub" })
    }

    @Test
    fun `connect keeps another identity of the same device id`() = test {
        // Same device, same seed, different passphrase: both entries must survive.
        val standard = mockKnownDevice(
            xpubs = mapOf("nativeSegwit" to "standard-native-xpub"),
            trezorDeviceId = "same-device-id",
        )
        val features = mockFeatures(deviceId = "same-device-id")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(standard))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        whenever(trezorUiHandler.currentSelection()).thenReturn(WalletSelection.Hidden("secret"))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertEquals(2, captor.firstValue.size)
        assertEquals(standard, captor.firstValue.first())
    }

    @Test
    fun `connect clears a passphrase flag the standard wallet should never have had`() = test {
        // Marked hidden it would demand a passphrase that opens a different wallet, so the standard
        // wallet could never be signed with again; opening it must be able to correct that.
        val misflagged = mockKnownDevice(
            xpubs = mapOf("nativeSegwit" to "xpub-m/84'/1'/0'"),
            passphraseProtected = true,
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(misflagged))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertFalse(captor.firstValue.single().passphraseProtected)
    }

    @Test
    fun `connect keeps the passphrase flag when the device asked on its own screen`() = test {
        // On-device entry does not say which wallet was opened, so it must not downgrade a wallet
        // already known to be hidden.
        val hidden = mockKnownDevice(
            xpubs = mapOf("nativeSegwit" to "xpub-m/84'/1'/0'"),
            passphraseProtected = true,
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(hidden))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        whenever(trezorUiHandler.currentSelection()).thenReturn(WalletSelection.OnDevice)
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertTrue(captor.firstValue.single().passphraseProtected)
    }

    @Test
    fun `connect keeps the standard wallet unprotected when its keys are re-read`() = test {
        val standard = mockKnownDevice(xpubs = mapOf("nativeSegwit" to "xpub-m/84'/1'/0'"))
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(standard))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        val saved = captor.firstValue.single()
        assertFalse(saved.passphraseProtected)
    }

    @Test
    fun `connect preserves stored xpubs when account xpub refresh is partial`() = test {
        // Re-reading an account of the same wallet yields the same key; a different one would
        // be another identity on the device, not a refresh of this one.
        val previousXpubs = mapOf(
            "nativeSegwit" to "native-xpub",
            "taproot" to "old-taproot-xpub",
        )
        val nativeSegwitPath = "m/84'/1'/0'"
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(mockKnownDevice(xpubs = previousXpubs)))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(
            trezorService.getPublicKey(
                path = any(),
                coin = anyOrNull(),
                showOnTrezor = eq(false),
            )
        ).thenAnswer {
            val path = it.getArgument<String>(0)
            if (path == nativeSegwitPath) {
                mockPublicKeyResponse(xpub = "native-xpub", path = nativeSegwitPath)
            } else {
                throw AppError("xpub failed")
            }
        }
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertEquals(
            mapOf(
                "nativeSegwit" to "native-xpub",
                "taproot" to "old-taproot-xpub",
            ),
            captor.firstValue.single().xpubs,
        )
    }

    @Test
    fun `connect preserves stored custom label over stale state label`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(hwWalletStore.loadKnownDevices())
            .thenReturn(listOf(mockKnownDevice()))
            .thenReturn(listOf(mockKnownDevice(customLabel = "Cold Storage")))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertEquals("Cold Storage", captor.lastValue.single().customLabel)
    }

    @Test
    fun `connect should retry once for retryable THP errors`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any()))
            .thenThrow(RuntimeException("thp timeout"))
            .thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        assertEquals(features, result.getOrNull())
        verify(trezorService, times(2)).connect(eq(DEVICE_ID), any())
    }

    @Test
    fun `connect should disconnect stale session after retryable THP failures`() = test {
        whenever(trezorService.connect(eq(DEVICE_ID), any()))
            .thenThrow(RuntimeException("thp timeout"))
            .thenThrow(RuntimeException("session timeout"))
        sut = createSut()

        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isFailure)
        assertNull(sut.state.value.connected)
        verify(trezorService, times(2)).connect(eq(DEVICE_ID), any())
        verify(trezorService).disconnect()
    }

    @Test
    fun `connect should not retry non-retryable errors`() = test {
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenThrow(RuntimeException("bad pin"))
        sut = createSut()

        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isFailure)
        verify(trezorService, times(1)).connect(eq(DEVICE_ID), any())
    }

    @Test
    fun `connect should fail fast for device busy errors`() = test {
        whenever(trezorService.connect(eq(DEVICE_ID), any()))
            .doAnswer { throw TrezorException.DeviceBusy() }
        sut = createSut()

        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isFailure)
        assertEquals(DEVICE_BUSY_MESSAGE, sut.state.value.error)
        verify(trezorService, times(1)).connect(eq(DEVICE_ID), any())
    }

    @Test
    fun `connect maps device busy failures to unlock prompt`() = test {
        whenever(trezorService.connect(eq(DEVICE_ID), any()))
            .doAnswer { throw TrezorException.DeviceBusy() }
        sut = createSut()

        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isFailure)
        assertEquals(DEVICE_BUSY_MESSAGE, sut.state.value.error)
    }

    @Test
    fun `connect retries account xpub fetch on device busy`() = test {
        val nativeSegwitPath = "m/84'/1'/0'"
        val features = mockFeatures()
        val device = mockDeviceInfo()
        var nativeSegwitAttempts = 0
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(
            trezorService.getPublicKey(
                path = any(),
                coin = anyOrNull(),
                showOnTrezor = eq(false),
            )
        ).thenAnswer {
            val path = it.getArgument<String>(0)
            if (path == nativeSegwitPath) {
                nativeSegwitAttempts++
                if (nativeSegwitAttempts == 1) {
                    throw TrezorException.DeviceBusy()
                }
                mockPublicKeyResponse(xpub = "native-xpub", path = nativeSegwitPath)
            } else {
                throw AppError("unsupported")
            }
        }
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        assertEquals(2, nativeSegwitAttempts)
    }

    @Test
    fun `connect fails when account xpub reads exhaust device busy retries`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(
            trezorService.getPublicKey(
                path = any(),
                coin = anyOrNull(),
                showOnTrezor = eq(false),
            )
        ).thenAnswer { throw TrezorException.DeviceBusy() }
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isFailure)
        assertEquals(DEVICE_BUSY_MESSAGE, sut.state.value.error)
        assertNull(sut.state.value.connectedDevice())
        verify(hwWalletStore, never()).saveKnownDevices(any())
    }

    @Test
    fun `connect should retry once for typed timeout errors`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any()))
            .doAnswer { throw TrezorException.Timeout() }
            .thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        verify(trezorService, times(2)).connect(eq(DEVICE_ID), any())
    }

    @Test
    fun `connect blocks partial save when typed timeout exhausts xpub retries`() = test {
        val nativeSegwitPath = "m/84'/1'/0'"
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(
            trezorService.getPublicKey(
                path = any(),
                coin = anyOrNull(),
                showOnTrezor = eq(false),
            )
        ).thenAnswer {
            if (it.getArgument<String>(0) == nativeSegwitPath) {
                mockPublicKeyResponse(xpub = "native-xpub", path = nativeSegwitPath)
            } else {
                throw TrezorException.Timeout()
            }
        }
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isFailure)
        assertNull(sut.state.value.connectedDevice())
        verify(hwWalletStore, never()).saveKnownDevices(any())
    }

    @Test
    fun `connect preserves cause when xpub reads exhaust non-busy transient retries`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(
            trezorService.getPublicKey(
                path = any(),
                coin = anyOrNull(),
                showOnTrezor = eq(false),
            )
        ).thenAnswer { throw AppError("DeviceDisconnected") }
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isFailure)
        assertEquals("DeviceDisconnected", sut.state.value.error)
        assertNull(sut.state.value.connectedDevice())
        verify(hwWalletStore, never()).saveKnownDevices(any())
    }

    @Test
    fun `connect rethrows cancellation from xpub retry delay`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(
            trezorService.getPublicKey(
                path = any(),
                coin = anyOrNull(),
                showOnTrezor = eq(false),
            )
        ).thenAnswer { throw CancellationException("cancelled") }
        sut = createSut()

        sut.scan()
        assertFailsWith<CancellationException> {
            sut.connect(DEVICE_ID)
        }
    }

    @Test
    fun `connect should set error on failure`() = test {
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenThrow(RuntimeException("connect failed"))
        sut = createSut()

        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isFailure)
        assertFalse(sut.state.value.isConnecting)
        assertEquals("connect failed", sut.state.value.error)
    }

    // endregion

    // region disconnect

    @Test
    fun `disconnect should clear connectedDevice state`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.scan()
        sut.connect(DEVICE_ID)
        assertEquals(features, sut.state.value.connectedDevice())

        val result = sut.disconnect()

        assertTrue(result.isSuccess)
        assertNull(sut.state.value.connectedDevice())
        assertNull(sut.state.value.connectedDeviceId())
        assertNull(sut.state.value.lastAddress)
        assertNull(sut.state.value.lastPublicKey)
    }

    @Test
    fun `disconnect should clear connectedDevice state on service failure`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        val addressResponse = mock<TrezorAddressResponse>()
        val publicKeyResponse = mock<TrezorPublicKeyResponse>()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.isConnected()).thenReturn(true)
        whenever(
            trezorService.getAddress(
                path = any(),
                coin = any(),
                showOnTrezor = any(),
                scriptType = anyOrNull(),
            )
        ).thenReturn(addressResponse)
        whenever(
            trezorService.getPublicKey(
                path = any(),
                coin = any(),
                showOnTrezor = any(),
            )
        ).thenReturn(publicKeyResponse)
        sut = createSut()

        sut.scan()
        sut.connect(DEVICE_ID)
        sut.getAddress()
        sut.getPublicKey()
        whenever(trezorService.disconnect()).thenThrow(RuntimeException("disconnect failed"))

        val result = sut.disconnect()

        assertTrue(result.isFailure)
        assertNull(sut.state.value.connectedDevice())
        assertNull(sut.state.value.connectedDeviceId())
        assertNull(sut.state.value.lastAddress)
        assertNull(sut.state.value.lastPublicKey)
        assertEquals("disconnect failed", sut.state.value.error)
    }

    // endregion

    // region resetState

    @Test
    fun `resetState clears known devices and credentials`() = test {
        val knownDevice = mockKnownDevice()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        sut = createSut()

        sut.initialize()
        sut.resetState()

        assertTrue(sut.state.value.knownDevices.isEmpty())
        assertTrue(sut.state.value.nearbyDevices.isEmpty())
        assertNull(sut.state.value.connectedDevice())
        verify(trezorTransport).clearDeviceCredential(DEVICE_ID)
        verify(trezorService).clearCredentials(DEVICE_ID)
        verify(hwWalletStore).reset()
    }

    @Test
    fun `resetState disconnects connected transport session`() = test {
        val knownDevice = mockKnownDevice()
        val device = mockDeviceInfo()
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        sut = createSut()

        sut.initialize()
        sut.scan()
        sut.connect(DEVICE_ID)
        sut.resetState()

        verify(trezorService).disconnect()
        verify(trezorTransport).disconnectDevice(DEVICE_PATH)
        assertNull(sut.state.value.connectedDevice())
    }

    @Test
    fun `resetState clears initialized setup gate`() = test {
        val devices = listOf(mockDeviceInfo())
        whenever(trezorService.scan()).thenReturn(devices)
        sut = createSut()

        sut.initialize()
        sut.resetState()
        val result = sut.scan()

        assertTrue(result.isSuccess)
        verify(trezorService, times(2)).initialize(anyOrNull())
    }

    // endregion

    // region getAddress

    @Test
    fun `getAddress should return address and update lastAddress`() = test {
        val addressResponse = mock<TrezorAddressResponse>()
        whenever(trezorService.isConnected()).thenReturn(true)
        whenever(
            trezorService.getAddress(
                path = any(),
                coin = any(),
                showOnTrezor = any(),
                scriptType = anyOrNull(),
            )
        ).thenReturn(addressResponse)
        sut = createSut()

        val result = sut.getAddress()

        assertTrue(result.isSuccess)
        assertEquals(addressResponse, result.getOrNull())
        assertEquals(addressResponse, sut.state.value.lastAddress)
        assertNull(sut.state.value.error)
    }

    @Test
    fun `getAddress should set error on failure`() = test {
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(emptyList())
        sut = createSut()

        val result = sut.getAddress()

        assertTrue(result.isFailure)
        assertNotNull(sut.state.value.error)
    }

    // endregion

    // region getPublicKey

    @Test
    fun `getPublicKey should return public key and update lastPublicKey`() = test {
        val publicKeyResponse = mock<TrezorPublicKeyResponse>()
        whenever(trezorService.isConnected()).thenReturn(true)
        whenever(
            trezorService.getPublicKey(
                path = any(),
                coin = any(),
                showOnTrezor = any(),
            )
        ).thenReturn(publicKeyResponse)
        sut = createSut()

        val result = sut.getPublicKey()

        assertTrue(result.isSuccess)
        assertEquals(publicKeyResponse, result.getOrNull())
        assertEquals(publicKeyResponse, sut.state.value.lastPublicKey)
        assertNull(sut.state.value.error)
    }

    // endregion

    // region signMessage

    @Test
    fun `signMessage should return signed message on success`() = test {
        val signedResponse = mock<TrezorSignedMessageResponse> {
            on { signature }.thenReturn(TEST_SIGNATURE)
            on { address }.thenReturn(TEST_ADDRESS)
        }
        whenever(trezorService.isConnected()).thenReturn(true)
        whenever(
            trezorService.signMessage(
                path = any(),
                message = any(),
                coin = any(),
            )
        ).thenReturn(signedResponse)
        sut = createSut()

        val result = sut.signMessage(message = TEST_MESSAGE)

        assertTrue(result.isSuccess)
        assertEquals(signedResponse, result.getOrNull())
        assertNull(sut.state.value.error)
    }

    // endregion

    // region verifyMessage

    @Test
    fun `verifyMessage should return true for valid signature`() = test {
        whenever(trezorService.isConnected()).thenReturn(true)
        whenever(
            trezorService.verifyMessage(
                address = any(),
                signature = any(),
                message = any(),
                coin = any(),
            )
        ).thenReturn(true)
        sut = createSut()

        val result = sut.verifyMessage(
            address = TEST_ADDRESS,
            signature = TEST_SIGNATURE,
            message = TEST_MESSAGE,
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!)
        assertNull(sut.state.value.error)
    }

    // endregion

    // region composeTransaction

    @Test
    fun `composeTransaction should use configured electrum server`() = test {
        val electrumServer = "ssl://custom.example:50002"
        settingsData.value = SettingsData(electrumServer = electrumServer)
        whenever(trezorService.isConnected()).thenReturn(true)
        whenever(trezorService.getDeviceFingerprint()).thenReturn("fingerprint")
        whenever(trezorService.composeTransaction(any())).thenReturn(emptyList())
        sut = createSut()

        val result = sut.composeTransaction(
            extendedKey = "vpub",
            outputs = listOf(ComposeOutput.Payment(address = TEST_ADDRESS, amountSats = 100uL)),
            feeRates = listOf(1f),
            network = Env.network.toCoreNetwork(),
            accountType = null,
            coinSelection = CoinSelection.BRANCH_AND_BOUND,
        )

        val params = argumentCaptor<ComposeParams>()
        assertTrue(result.isSuccess)
        verify(trezorService).composeTransaction(params.capture())
        assertEquals(electrumServer, params.firstValue.wallet.electrumUrl)
    }

    // endregion

    // region hasKnownDevices

    @Test
    fun `hasKnownDevices should return false when no known devices`() {
        sut = createSut()

        assertFalse(sut.hasKnownDevices())
    }

    @Test
    fun `hasKnownDevice should match stored device path`() = test {
        val knownDevice = mockKnownDevice(path = "/dev/bus/usb/001/002")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        sut = createSut()

        assertTrue(sut.hasKnownDevice("/dev/bus/usb/001/002"))
    }

    // endregion

    // region autoReconnect

    @Test
    fun `autoReconnect should fail when no known devices exist`() = test {
        sut = createSut()

        val result = sut.autoReconnect()

        assertTrue(result.isFailure)
        assertEquals("No known devices", result.exceptionOrNull()?.message)
    }

    @Test
    fun `autoReconnect should scan and connect known nearby device`() = test {
        val knownDevice = mockKnownDevice()
        val device = mockDeviceInfo()
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any(), eq(false))).thenReturn(features)
        whenever(trezorService.isConnected()).thenReturn(false)
        sut = createSut()

        sut.initialize()
        val result = sut.autoReconnect()

        assertTrue(result.isSuccess)
        assertEquals(features, result.getOrNull())
        assertEquals(DEVICE_ID, sut.state.value.connectedDeviceId())
        assertFalse(sut.state.value.isAutoReconnecting)
    }

    // endregion

    // region connectKnownDevice

    @Test
    fun `connectKnownDevice should connect exact known device match`() = test {
        val knownDevice = mockKnownDevice()
        val device = mockDeviceInfo()
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        sut = createSut()

        sut.initialize()
        val result = sut.connectKnownDevice(DEVICE_ID)

        assertTrue(result.isSuccess)
        assertEquals(features, result.getOrNull())
        assertEquals(DEVICE_ID, sut.state.value.connectedDeviceId())
    }

    @Test
    fun `connectKnownDevice should close stale session when forced`() = test {
        val knownDevice = mockKnownDevice()
        val device = mockDeviceInfo()
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        sut = createSut()

        sut.initialize()
        val result = sut.connectKnownDevice(DEVICE_ID, forceSession = true)

        assertTrue(result.isSuccess)
        verify(trezorService).disconnect()
        assertEquals(DEVICE_ID, sut.state.value.connectedDeviceId())
    }

    @Test
    fun `connectKnownDevice should use stored bluetooth device when scan misses active connection`() = test {
        val bleDeviceId = "ble:57:21:A7:F9:DD:AD"
        val knownDevice = mockKnownDevice(
            id = bleDeviceId,
            path = bleDeviceId,
            transportType = TransportType.BLUETOOTH,
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.scan()).thenReturn(emptyList())
        whenever(trezorService.connect(eq(bleDeviceId), any())).thenReturn(features)
        sut = createSut()

        sut.initialize()
        val result = sut.connectKnownDevice(bleDeviceId)

        assertTrue(result.isSuccess)
        assertEquals(features, result.getOrNull())
        assertEquals(bleDeviceId, sut.state.value.connectedDeviceId())
        verify(trezorService).connect(eq(bleDeviceId), any())
        verify(trezorService).scan()
    }

    @Test
    fun `connectKnownDevice should rethrow cancellation and clear connecting state`() = test {
        val cancellation = CancellationException("cancelled")
        whenever(trezorService.scan()).thenAnswer { throw cancellation }
        sut = createSut()

        sut.initialize()
        val thrown = assertFailsWith<CancellationException> {
            sut.connectKnownDevice(DEVICE_ID)
        }

        assertEquals(cancellation.message, thrown.message)
        assertFalse(sut.state.value.isConnecting)
        assertNull(sut.state.value.error)
    }

    @Test
    fun `disconnectStaleSession should not disconnect unrelated connected device`() = test {
        val otherDeviceId = "device-other"
        val knownOther = mockKnownDevice(id = otherDeviceId, path = "/other")
        val otherDevice = mockDeviceInfo(id = otherDeviceId, path = "/other")
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownOther))
        whenever(trezorService.scan()).thenReturn(listOf(otherDevice))
        whenever(trezorService.connect(eq(otherDeviceId), any())).thenReturn(features)
        sut = createSut()

        sut.initialize()
        assertTrue(sut.connectKnownDevice(otherDeviceId).isSuccess)
        assertEquals(otherDeviceId, sut.state.value.connectedDeviceId())

        val result = sut.disconnectStaleSession(DEVICE_ID)

        assertTrue(result.isSuccess)
        assertEquals(otherDeviceId, sut.state.value.connectedDeviceId())
        verify(trezorService, never()).disconnect()
    }

    @Test
    fun `connectKnownDevice failure should not disconnect unrelated connected device`() = test {
        val otherDeviceId = "device-other"
        val knownOther = mockKnownDevice(id = otherDeviceId, path = "/other")
        val knownTarget = mockKnownDevice()
        val otherDevice = mockDeviceInfo(id = otherDeviceId, path = "/other")
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownTarget, knownOther))
        whenever(trezorService.scan()).thenReturn(listOf(otherDevice))
        whenever(trezorService.connect(eq(otherDeviceId), any())).thenReturn(features)
        sut = createSut()

        sut.initialize()
        assertTrue(sut.connectKnownDevice(otherDeviceId).isSuccess)

        whenever(trezorService.scan()).thenReturn(emptyList())
        val result = sut.connectKnownDevice(DEVICE_ID)

        assertTrue(result.isFailure)
        assertEquals(otherDeviceId, sut.state.value.connectedDeviceId())
        verify(trezorService, never()).disconnect()
    }

    @Test
    fun `ensureConnected returns current selected device without reconnecting`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.scan()
        sut.connect(DEVICE_ID)
        whenever(trezorService.isConnected()).thenReturn(true)

        val result = sut.ensureConnected(DEVICE_ID)

        assertTrue(result.isSuccess)
        assertEquals(features, result.getOrNull())
        verify(trezorService, times(1)).scan()
        verify(trezorService, times(1)).connect(eq(DEVICE_ID), any())
        verify(trezorService, never()).disconnect()
    }

    @Test
    fun `ensureConnected returns device busy when current device is locked`() = test {
        val features = mockFeatures(pinProtection = true, unlocked = false)
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.scan()
        sut.connect(DEVICE_ID)
        whenever(trezorService.isConnected()).thenReturn(true)
        whenever(trezorService.refreshFeatures()).thenReturn(features)

        val result = sut.ensureConnected(DEVICE_ID)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.isTrezorDeviceBusy() == true)
        verify(trezorService, times(1)).connect(eq(DEVICE_ID), any())
    }

    @Test
    fun `ensureConnected refreshes cached locked features after device unlock`() = test {
        val lockedFeatures = mockFeatures(pinProtection = true, unlocked = false)
        val unlockedFeatures = mockFeatures(pinProtection = true, unlocked = true)
        val device = mockDeviceInfo()
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(lockedFeatures)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.scan()
        sut.connect(DEVICE_ID)
        whenever(trezorService.isConnected()).thenReturn(true)
        whenever(trezorService.refreshFeatures()).thenReturn(unlockedFeatures)

        val result = sut.ensureConnected(DEVICE_ID)

        assertEquals(unlockedFeatures, result.getOrNull())
        assertEquals(unlockedFeatures, sut.state.value.connected?.features)
        verify(trezorService).refreshFeatures()
        verify(trezorService, times(1)).connect(eq(DEVICE_ID), any())
    }

    @Test
    fun `ensureConnected retries bluetooth reconnect until scan finds the device`() = test {
        val bleDeviceId = "ble:57:21:A7:F9:DD:AD"
        val knownDevice = mockKnownDevice(
            id = bleDeviceId,
            path = bleDeviceId,
            transportType = TransportType.BLUETOOTH,
        )
        val device = mockDeviceInfo(
            id = bleDeviceId,
            path = bleDeviceId,
            transportType = TrezorTransportType.BLUETOOTH,
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(emptyList(), emptyList(), listOf(device))
        whenever(trezorService.connect(eq(bleDeviceId), any())).thenReturn(features)
        sut = createSut()

        sut.initialize()
        val result = sut.ensureConnected(bleDeviceId)

        assertTrue(result.isSuccess)
        assertEquals(features, result.getOrNull())
        verify(trezorService, times(3)).scan()
        verify(trezorService).connect(eq(bleDeviceId), any())
        verify(trezorService, never()).connect(eq(bleDeviceId), any(), eq(false))
    }

    @Test
    fun `ensureConnected stops bluetooth retry after user cancellation`() = test {
        val bleDeviceId = "ble:57:21:A7:F9:DD:AD"
        val knownDevice = mockKnownDevice(
            id = bleDeviceId,
            path = bleDeviceId,
            transportType = TransportType.BLUETOOTH,
        )
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(emptyList())
        whenever(trezorService.connect(eq(bleDeviceId), any())).doAnswer { throw TrezorException.UserCancelled() }
        sut = createSut()

        sut.initialize()
        val result = sut.ensureConnected(bleDeviceId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TrezorException.UserCancelled)
        verify(trezorService, times(1)).connect(eq(bleDeviceId), any())
    }

    @Test
    fun `ensureConnected stops bluetooth retry on device busy`() = test {
        val bleDeviceId = "ble:57:21:A7:F9:DD:AD"
        val knownDevice = mockKnownDevice(
            id = bleDeviceId,
            path = bleDeviceId,
            transportType = TransportType.BLUETOOTH,
        )
        val device = mockDeviceInfo(
            id = bleDeviceId,
            path = bleDeviceId,
            transportType = TrezorTransportType.BLUETOOTH,
        )
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(bleDeviceId), any())).doAnswer { throw TrezorException.DeviceBusy() }
        sut = createSut()

        sut.initialize()
        val result = sut.ensureConnected(bleDeviceId)

        assertTrue(result.isFailure)
        assertEquals(true, result.exceptionOrNull()?.isTrezorDeviceBusy())
        verify(trezorService, times(1)).connect(eq(bleDeviceId), any())
    }

    // endregion

    // region clearError

    @Test
    fun `clearError should set error to null`() = test {
        whenever(trezorService.scan()).thenThrow(RuntimeException("some error"))
        sut = createSut()

        sut.scan()
        assertNotNull(sut.state.value.error)

        sut.clearError()

        assertNull(sut.state.value.error)
    }

    // endregion

    // region listDevices

    @Test
    fun `listDevices should return devices and update nearbyDevices state`() = test {
        val devices = listOf(mockDeviceInfo())
        whenever(trezorService.listDevices()).thenReturn(devices)
        sut = createSut()

        val result = sut.listDevices()

        assertTrue(result.isSuccess)
        assertEquals(devices, result.getOrNull())
        assertEquals(devices, sut.state.value.nearbyDevices)
    }

    // endregion

    // region ensureConnected

    @Test
    fun `getAddress should reconnect known device before reading address`() = test {
        val knownDevice = mockKnownDevice()
        val device = mockDeviceInfo()
        val features = mockFeatures()
        val addressResponse = mock<TrezorAddressResponse>()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(
            trezorService.getAddress(
                path = any(),
                coin = any(),
                showOnTrezor = any(),
                scriptType = anyOrNull(),
            )
        ).thenReturn(addressResponse)
        sut = createSut()

        sut.initialize()
        val result = sut.getAddress()

        assertTrue(result.isSuccess)
        assertEquals(addressResponse, result.getOrNull())
        assertEquals(DEVICE_ID, sut.state.value.connectedDeviceId())
        verify(trezorService).scan()
        verify(trezorService).connect(eq(DEVICE_ID), any())
    }

    // endregion

    // region forgetDevice

    @Test
    fun `forgetDevice should remove known device when disconnect cleanup fails`() = test {
        val knownDevice = mockKnownDevice()
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.initialize()
        sut.scan()
        sut.connect(DEVICE_ID)
        whenever(trezorService.disconnect()).thenThrow(RuntimeException("disconnect failed"))

        val result = sut.forgetDevice(DEVICE_ID)

        assertTrue(result.isSuccess)
        assertTrue(sut.state.value.knownDevices.isEmpty())
        assertNull(sut.state.value.connectedDevice())
        assertNull(sut.state.value.connectedDeviceId())
        assertNull(sut.state.value.error)
        verify(trezorTransport).clearDeviceCredential(DEVICE_ID)
        verify(trezorService).clearCredentials(DEVICE_ID)
        verify(hwWalletStore).saveKnownDevices(emptyList())
    }

    @Test
    fun `forgetDevice should fail when credential cleanup fails`() = test {
        val knownDevice = mockKnownDevice()
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.initialize()
        sut.scan()
        sut.connect(DEVICE_ID)
        whenever(trezorService.clearCredentials(DEVICE_ID)).thenThrow(RuntimeException("clear failed"))

        val result = sut.forgetDevice(DEVICE_ID)

        assertTrue(result.isFailure)
        assertTrue(sut.state.value.knownDevices.isEmpty())
        assertNull(sut.state.value.connectedDevice())
        assertNull(sut.state.value.connectedDeviceId())
        assertEquals("clear failed", result.exceptionOrNull()?.message)
        assertEquals("clear failed", sut.state.value.error)
        verify(trezorTransport).clearDeviceCredential(DEVICE_ID)
        verify(trezorService).clearCredentials(DEVICE_ID)
        verify(hwWalletStore).saveKnownDevices(emptyList())
    }

    @Test
    fun `forgetDevice should preserve devices that are only in the store`() = test {
        val knownDevice = mockKnownDevice()
        val otherDevice = mockKnownDevice(id = "other-device", path = "/dev/trezor1")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice, otherDevice))
        sut = createSut()

        val result = sut.forgetDevice(DEVICE_ID)

        assertTrue(result.isSuccess)
        assertEquals(listOf(otherDevice), sut.state.value.knownDevices)
        verify(trezorTransport).clearDeviceCredential(DEVICE_ID)
        verify(trezorService).clearCredentials(DEVICE_ID)
        verify(hwWalletStore).saveKnownDevices(listOf(otherDevice))
    }

    @Test
    fun `forgetDevice removes an identity from every transport it was paired over`() = test {
        // Removal walks the entries of one wallet, so each call has to take the whole identity out:
        // a lagging store read or a connect landing mid-removal would otherwise write a sibling
        // entry back and leave the wallet watched.
        val sharedXpubs = mapOf("nativeSegwit" to "shared-native-xpub")
        val overBluetooth = mockKnownDevice(id = "ble1", path = "ble:AA:BB", xpubs = sharedXpubs)
        val overUsb = mockKnownDevice(id = "usb1", path = "/dev/trezor1", xpubs = sharedXpubs)
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(overBluetooth, overUsb))
        sut = createSut()

        val result = sut.forgetDevice("usb1", walletKey = walletKeyOf(sharedXpubs))

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertEquals(emptyList(), captor.lastValue)
        assertTrue(sut.state.value.knownDevices.isEmpty())
    }

    @Test
    fun `forgetDevice keeps the device paired while another identity remains`() = test {
        val standardXpubs = mapOf("nativeSegwit" to "standard-native-xpub")
        val hiddenXpubs = mapOf("nativeSegwit" to "hidden-native-xpub")
        val standard = mockKnownDevice(xpubs = standardXpubs)
        val hidden = mockKnownDevice(xpubs = hiddenXpubs, passphraseProtected = true)
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(standard, hidden))
        sut = createSut()

        val result = sut.forgetDevice(DEVICE_ID, walletKey = walletKeyOf(hiddenXpubs))

        assertTrue(result.isSuccess)
        assertEquals(listOf(standard), sut.state.value.knownDevices)
        verify(hwWalletStore).saveKnownDevices(listOf(standard))
        verify(trezorTransport, never()).clearDeviceCredential(any())
        verify(trezorService, never()).clearCredentials(any())
    }

    @Test
    fun `connectWithWalletMode opens a passphrase session when none is live`() = test {
        // Reopening a hidden wallet happens exactly when its session is gone, so requiring a live
        // one would make the passphrase prompt unable to ever succeed.
        val features = mockFeatures()
        val knownDevice = mockKnownDevice(xpubs = mapOf("nativeSegwit" to "hidden-native-xpub"))
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        sut = createSut()
        sut.initialize()
        assertNull(sut.state.value.connectedDeviceId())

        val result = sut.connectWithWalletMode(DEVICE_ID, TrezorWalletMode.PASSPHRASE_HOST, "secret")

        assertTrue(result.isSuccess, "err=${result.exceptionOrNull()}")
        verify(trezorUiHandler).setWalletMode(TrezorWalletMode.PASSPHRASE_HOST, "secret")
        assertEquals(DEVICE_ID, sut.state.value.connectedDeviceId())
    }

    @Test
    fun `setWalletMode still requires a live session to switch`() = test {
        sut = createSut()

        val result = sut.setWalletMode(TrezorWalletMode.PASSPHRASE_HOST, "secret")

        assertTrue(result.isFailure)
        verify(trezorUiHandler, never()).setWalletMode(any(), any())
    }

    @Test
    fun `forgetDevice keeps the live session of an identity it is not forgetting`() = test {
        // The device holds one identity open at a time; forgetting a different wallet must not
        // close the session the user is still transacting with.
        val keptKey = "kept-native-xpub"
        val kept = mockKnownDevice(
            xpubs = ALL_ADDRESS_TYPE_KEYS.associateWith { keptKey },
            walletId = "kept-wallet",
        )
        val forgottenXpubs = mapOf("nativeSegwit" to "forgotten-native-xpub")
        val forgotten = mockKnownDevice(
            xpubs = forgottenXpubs,
            walletId = "forgotten-wallet",
            passphraseProtected = true,
        )
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(forgotten, kept))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        whenever(
            trezorService.getPublicKey(path = any(), coin = anyOrNull(), showOnTrezor = eq(false))
        ).thenAnswer { mockPublicKeyResponse(xpub = keptKey, path = it.getArgument(0)) }
        sut = createSut()
        sut.scan()
        sut.connect(DEVICE_ID)
        assertEquals("kept-wallet", sut.state.value.connectedWalletId())

        val result = sut.forgetDevice(DEVICE_ID, walletKey = walletKeyOf(forgottenXpubs))

        assertTrue(result.isSuccess)
        assertEquals("kept-wallet", sut.state.value.connectedWalletId())
        verify(trezorService, never()).disconnect()
        verify(trezorTransport, never()).clearDeviceCredential(any())
    }

    @Test
    fun `forgetDevice closes the session when it belongs to the identity being forgotten`() = test {
        val forgottenKey = "forgotten-native-xpub"
        val forgottenXpubs = ALL_ADDRESS_TYPE_KEYS.associateWith { forgottenKey }
        val forgotten = mockKnownDevice(
            xpubs = forgottenXpubs,
            walletId = "forgotten-wallet",
            passphraseProtected = true,
        )
        val kept = mockKnownDevice(xpubs = mapOf("nativeSegwit" to "kept-native-xpub"), walletId = "kept-wallet")
        val features = mockFeatures()
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(forgotten, kept))
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(mockDeviceInfo()))
        whenever(
            trezorService.getPublicKey(path = any(), coin = anyOrNull(), showOnTrezor = eq(false))
        ).thenAnswer { mockPublicKeyResponse(xpub = forgottenKey, path = it.getArgument(0)) }
        sut = createSut()
        sut.scan()
        sut.connect(DEVICE_ID)
        assertEquals("forgotten-wallet", sut.state.value.connectedWalletId())

        val result = sut.forgetDevice(DEVICE_ID, walletKey = walletKeyOf(forgottenXpubs))

        assertTrue(result.isSuccess)
        assertNull(sut.state.value.connectedDevice())
        verify(trezorService).disconnect()
    }

    @Test
    fun `forgetDevice keeps the stored label of the identity left behind`() = test {
        // Labels are written straight to the store, so the cached device list can be out of date;
        // rewriting from it would drop the name the user gave the wallet that stays paired.
        val removedXpubs = mapOf("nativeSegwit" to "removed-native-xpub")
        val keptXpubs = mapOf("nativeSegwit" to "kept-native-xpub")
        val removed = mockKnownDevice(xpubs = removedXpubs, passphraseProtected = true)
        val keptWhenCached = mockKnownDevice(xpubs = keptXpubs, passphraseProtected = true)
        val keptWhenStored = keptWhenCached.copy(customLabel = "Pass B")
        whenever(hwWalletStore.loadKnownDevices())
            .thenReturn(listOf(removed, keptWhenCached))
            .thenReturn(listOf(removed, keptWhenStored))
        sut = createSut()
        sut.initialize()

        val result = sut.forgetDevice(DEVICE_ID, walletKey = walletKeyOf(removedXpubs))

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture())
        assertEquals(listOf(keptWhenStored), captor.lastValue)
    }

    @Test
    fun `forgetDevice clears credentials once the last identity is gone`() = test {
        val standardXpubs = mapOf("nativeSegwit" to "standard-native-xpub")
        val standard = mockKnownDevice(xpubs = standardXpubs)
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(standard))
        sut = createSut()

        val result = sut.forgetDevice(DEVICE_ID, walletKey = walletKeyOf(standardXpubs))

        assertTrue(result.isSuccess)
        verify(hwWalletStore).saveKnownDevices(emptyList())
        verify(trezorTransport).clearDeviceCredential(DEVICE_ID)
        verify(trezorService).clearCredentials(DEVICE_ID)
    }

    private fun walletKeyOf(xpubs: Map<String, String>) = xpubs.values.sorted().joinToString()

    // endregion

    // region initial state

    @Test
    fun `initial state should have default values`() {
        sut = createSut()

        val state = sut.state.value
        assertFalse(state.isScanning)
        assertFalse(state.isConnecting)
        assertFalse(state.isAutoReconnecting)
        assertTrue(state.knownDevices.isEmpty())
        assertTrue(state.nearbyDevices.isEmpty())
        assertNull(state.connectedDevice())
        assertNull(state.connectedDeviceId())
        assertNull(state.lastAddress)
        assertNull(state.lastPublicKey)
        assertNull(state.error)
    }

    // endregion
}
