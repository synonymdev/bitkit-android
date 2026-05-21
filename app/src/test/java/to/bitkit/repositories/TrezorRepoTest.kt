package to.bitkit.repositories

import android.content.Context
import android.content.SharedPreferences
import com.synonym.bitkitcore.TrezorAddressResponse
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorPublicKeyResponse
import com.synonym.bitkitcore.TrezorSignedMessageResponse
import com.synonym.bitkitcore.TrezorTransportType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.TrezorStore
import to.bitkit.env.Env
import to.bitkit.services.TrezorService
import to.bitkit.services.TrezorTransport
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    private val trezorStore = mock<TrezorStore>()
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
        whenever(context.filesDir).thenReturn(tempFolder.root)
        whenever { trezorStore.loadKnownDevices() }.thenReturn(emptyList())
    }

    private fun createSut(): TrezorRepo = TrezorRepo(
        context = context,
        trezorService = trezorService,
        trezorTransport = trezorTransport,
        trezorStore = trezorStore,
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

    private fun mockKnownDevice(
        id: String = DEVICE_ID,
        name: String? = DEVICE_NAME,
        path: String = DEVICE_PATH,
        label: String? = DEVICE_LABEL,
        model: String? = DEVICE_MODEL,
    ) = KnownDevice(
        id = id,
        name = name,
        path = path,
        transportType = KnownDeviceTransportType.USB,
        label = label,
        model = model,
        lastConnectedAt = 123L,
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
    fun `scan should exclude known devices from nearbyDevices state`() = test {
        val knownDevice = mockKnownDevice()
        val known = mockDeviceInfo()
        val nearby = mockDeviceInfo(id = "device-456", path = "/dev/trezor1")
        whenever(trezorStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
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
    fun `connect should return features and update connectedDevice state`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(DEVICE_ID)).thenReturn(features)
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
        whenever(trezorService.connect(DEVICE_ID)).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(trezorStore).saveKnownDevices(captor.capture())
        val saved = captor.firstValue.single()
        assertEquals(DEVICE_ID, saved.id)
        assertEquals(KnownDeviceTransportType.USB, saved.transportType)
        assertEquals("Savings", saved.label)
        assertEquals("Safe 5", saved.model)
    }

    @Test
    fun `connect should retry once for retryable THP errors`() = test {
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorService.connect(DEVICE_ID))
            .thenThrow(RuntimeException("thp timeout"))
            .thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.scan()
        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isSuccess)
        assertEquals(features, result.getOrNull())
        verify(trezorService, times(2)).connect(DEVICE_ID)
    }

    @Test
    fun `connect should not retry non-retryable errors`() = test {
        whenever(trezorService.connect(DEVICE_ID)).thenThrow(RuntimeException("bad pin"))
        sut = createSut()

        val result = sut.connect(DEVICE_ID)

        assertTrue(result.isFailure)
        verify(trezorService, times(1)).connect(DEVICE_ID)
    }

    @Test
    fun `connect should set error on failure`() = test {
        whenever(trezorService.connect(DEVICE_ID)).thenThrow(RuntimeException("connect failed"))
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
        whenever(trezorService.connect(DEVICE_ID)).thenReturn(features)
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
        whenever(trezorService.connect(DEVICE_ID)).thenReturn(features)
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
        whenever(trezorStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(DEVICE_ID)).thenReturn(features)
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
        whenever(trezorStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(DEVICE_ID)).thenReturn(features)
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
        whenever(trezorStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.isConnected()).thenReturn(false)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        whenever(trezorService.connect(DEVICE_ID)).thenReturn(features)
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
        verify(trezorService).connect(DEVICE_ID)
    }

    // endregion

    // region forgetDevice

    @Test
    fun `forgetDevice should remove known device when service cleanup fails`() = test {
        val knownDevice = mockKnownDevice()
        val features = mockFeatures()
        val device = mockDeviceInfo()
        whenever(trezorStore.loadKnownDevices()).thenReturn(listOf(knownDevice))
        whenever(trezorService.connect(DEVICE_ID)).thenReturn(features)
        whenever(trezorService.scan()).thenReturn(listOf(device))
        sut = createSut()

        sut.initialize()
        sut.scan()
        sut.connect(DEVICE_ID)
        whenever(trezorService.disconnect()).thenThrow(RuntimeException("disconnect failed"))
        whenever(trezorService.clearCredentials(DEVICE_ID)).thenThrow(RuntimeException("clear failed"))

        val result = sut.forgetDevice(DEVICE_ID)

        assertTrue(result.isFailure)
        assertTrue(sut.state.value.knownDevices.isEmpty())
        assertNull(sut.state.value.connectedDevice)
        assertNull(sut.state.value.connectedDeviceId)
        assertEquals("disconnect failed", sut.state.value.error)
        verify(trezorTransport).clearDeviceCredential(DEVICE_ID)
        verify(trezorService).clearCredentials(DEVICE_ID)
        verify(trezorStore).saveKnownDevices(emptyList())
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
