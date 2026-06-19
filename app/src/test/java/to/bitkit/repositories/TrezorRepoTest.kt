package to.bitkit.repositories

import android.content.Context
import android.content.SharedPreferences
import com.synonym.bitkitcore.TrezorAddressResponse
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorPublicKeyResponse
import com.synonym.bitkitcore.TrezorSignedMessageResponse
import com.synonym.bitkitcore.TrezorTransportType
import com.synonym.bitkitcore.WalletSelection
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.HwWalletStore
import to.bitkit.env.Env
import to.bitkit.models.TransportType
import to.bitkit.services.TrezorService
import to.bitkit.services.TrezorTransport
import to.bitkit.services.TrezorUiHandler
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
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
    }

    @get:Rule(order = 1)
    val tempFolder = TemporaryFolder()

    private val context = mock<Context>()
    private val trezorService = mock<TrezorService>()
    private val trezorTransport = mock<TrezorTransport>()
    private val trezorUiHandler = mock<TrezorUiHandler>()
    private val hwWalletStore = mock<HwWalletStore>()
    private val prefs = mock<SharedPreferences>()
    private val prefsEditor = mock<SharedPreferences.Editor>()

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
        whenever(trezorUiHandler.needsPinEntry).thenReturn(MutableStateFlow(false))
        whenever(trezorUiHandler.currentSelection()).thenReturn(WalletSelection.Standard)
        whenever(context.filesDir).thenReturn(tempFolder.root)
        whenever { hwWalletStore.loadKnownDevices() }.thenReturn(emptyList())
    }

    private fun createSut(): TrezorRepo = TrezorRepo(
        context = context,
        trezorService = trezorService,
        trezorTransport = trezorTransport,
        trezorUiHandler = trezorUiHandler,
        hwWalletStore = hwWalletStore,
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
    ): TrezorFeatures = mock {
        on { this.label }.thenReturn(label)
        on { this.model }.thenReturn(model)
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

    @Suppress("LongParameterList")
    private fun mockKnownDevice(
        id: String = DEVICE_ID,
        name: String? = DEVICE_NAME,
        path: String = DEVICE_PATH,
        label: String? = DEVICE_LABEL,
        model: String? = DEVICE_MODEL,
        transportType: TransportType = TransportType.USB,
        xpubs: Map<String, String> = emptyMap(),
    ) = KnownDevice(
        id = id,
        name = name,
        path = path,
        transportType = transportType,
        label = label,
        model = model,
        lastConnectedAt = 123L,
        xpubs = xpubs,
    )

    // region initialize

    @Test
    fun `initialize should update state to initialized on success`() = test {
        sut = createSut()

        val result = sut.initialize()

        assertTrue(result.isSuccess)
        assertTrue(sut.state.value.isInitialized)
        assertNull(sut.state.value.error)
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
        assertFalse(sut.state.value.isInitialized)
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
        assertTrue(sut.state.value.isInitialized)
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
        assertEquals(features, sut.state.value.connectedDevice)
        assertEquals(DEVICE_ID, sut.state.value.connectedDeviceId)
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
    }

    @Test
    fun `connect preserves stored xpubs when account xpub refresh is partial`() = test {
        val previousXpubs = mapOf(
            "nativeSegwit" to "old-native-xpub",
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
                mockPublicKeyResponse(xpub = "new-native-xpub", path = nativeSegwitPath)
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
                "nativeSegwit" to "new-native-xpub",
                "taproot" to "old-taproot-xpub",
            ),
            captor.firstValue.single().xpubs,
        )
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
    fun `connect should not retry non-retryable errors`() = test {
        whenever(trezorService.connect(eq(DEVICE_ID), any())).thenThrow(RuntimeException("bad pin"))
        sut = createSut()

        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isFailure)
        verify(trezorService, times(1)).connect(eq(DEVICE_ID), any())
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
        assertEquals(features, sut.state.value.connectedDevice)

        val result = sut.disconnect()

        assertTrue(result.isSuccess)
        assertNull(sut.state.value.connectedDevice)
        assertNull(sut.state.value.connectedDeviceId)
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
        assertNull(sut.state.value.connectedDevice)
        assertNull(sut.state.value.connectedDeviceId)
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
        assertNull(sut.state.value.connectedDevice)
        verify(trezorTransport).clearDeviceCredential(DEVICE_ID)
        verify(trezorService).clearCredentials(DEVICE_ID)
        verify(hwWalletStore).reset()
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
        assertEquals(DEVICE_ID, sut.state.value.connectedDeviceId)
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
        assertEquals(DEVICE_ID, sut.state.value.connectedDeviceId)
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
        assertEquals(DEVICE_ID, sut.state.value.connectedDeviceId)
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
        assertNull(sut.state.value.connectedDevice)
        assertNull(sut.state.value.connectedDeviceId)
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
        assertNull(sut.state.value.connectedDevice)
        assertNull(sut.state.value.connectedDeviceId)
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

    // endregion

    // region initial state

    @Test
    fun `initial state should have default values`() {
        sut = createSut()

        val state = sut.state.value
        assertFalse(state.isInitialized)
        assertFalse(state.isScanning)
        assertFalse(state.isConnecting)
        assertFalse(state.isAutoReconnecting)
        assertTrue(state.knownDevices.isEmpty())
        assertTrue(state.nearbyDevices.isEmpty())
        assertNull(state.connectedDevice)
        assertNull(state.connectedDeviceId)
        assertNull(state.lastAddress)
        assertNull(state.lastPublicKey)
        assertNull(state.error)
    }

    // endregion
}
