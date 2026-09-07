package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.CompletedTransaction
import com.synonym.bitkitcore.JadeAccount
import com.synonym.bitkitcore.JadeAccountExport
import com.synonym.bitkitcore.JadeAddressVariant
import com.synonym.bitkitcore.JadeDeviceInfo
import com.synonym.bitkitcore.JadeException
import com.synonym.bitkitcore.JadeNetwork
import com.synonym.bitkitcore.JadeState
import com.synonym.bitkitcore.JadeTransportKind
import com.synonym.bitkitcore.JadeVersionInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.HwWalletStore
import to.bitkit.models.HwFundingAddressType
import to.bitkit.models.HwWalletVendor
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.services.JadeService
import to.bitkit.services.JadeTransport
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class JadeRepoTest : BaseUnitTest() {

    private val context = mock<Context>()
    private val jadeService = mock<JadeService>()
    private val jadeTransport = mock<JadeTransport>()
    private val hwWalletStore = mock<HwWalletStore>()
    private val externalDisconnect = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val transportRestored = MutableSharedFlow<TransportType>(extraBufferCapacity = 1)

    private val usbDevice = JadeDeviceInfo(
        path = USB_PATH,
        transport = JadeTransportKind.SERIAL,
        name = "Jade",
        serialNumber = null,
    )

    private val knownUsb = KnownDevice(
        id = "jade:serial:$EFUSE_MAC",
        name = "Jade",
        path = "/dev/bus/usb/001/002",
        transportType = TransportType.USB,
        label = null,
        model = "Jade",
        lastConnectedAt = 0L,
        xpubs = mapOf(HwFundingAddressType.NATIVE_SEGWIT.settingsKey to "zpubNS"),
        walletId = WALLET_ID,
        vendor = HwWalletVendor.BLOCKSTREAM,
        jadeDeviceId = EFUSE_MAC,
    )

    @Before
    fun setUp() {
        whenever(jadeTransport.externalDisconnect).thenReturn(externalDisconnect)
        whenever(jadeTransport.transportRestored).thenReturn(transportRestored)
        whenever(jadeTransport.hasUsbPermission(any())).thenReturn(true)
        whenever(jadeTransport.disconnectDevice(any())).thenReturn(mock())
        whenever(context.getString(any())).thenReturn("message")
        whenever { hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM) }.thenReturn(emptyList())
        whenever { hwWalletStore.loadPendingNames() }.thenReturn(emptyMap())
        whenever { jadeService.isConnected() }.thenReturn(false)
        whenever { jadeService.listDevices() }.thenReturn(emptyList())
        whenever { jadeService.getAccountExport(any(), any(), any()) }.thenReturn(accountExport())
        whenever { jadeService.refreshVersionInfo() }.thenReturn(versionInfo(JadeState.READY))
    }

    @Test
    fun `scan reuses the last device list while a session is open`() = test {
        whenever { jadeService.isConnected() }.thenReturn(true)
        whenever { jadeService.listDevices() }.thenReturn(listOf(usbDevice))
        val sut = createRepo()

        val result = sut.scan()

        assertEquals(listOf(usbDevice), result.getOrThrow())
        verify(jadeService, never()).scan(any(), any())
        assertEquals(listOf(usbDevice), sut.state.value.nearbyDevices)
    }

    @Test
    fun `connect unlocks a locked jade then reads its accounts and stores the entry`() = test {
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(usbDevice))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.LOCKED))
        val sut = createRepo()
        sut.scan()

        val result = sut.connect(USB_PATH)

        val connected = result.getOrThrow()
        verify(jadeService).unlock(JadeNetwork.REGTEST)
        verify(jadeService).getAccountExport(eq(JadeNetwork.REGTEST), eq(ALL_ACCOUNT_TYPES), any())
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture(), anyOrNull(), eq(HwWalletVendor.BLOCKSTREAM))
        val stored = captor.firstValue.single()
        assertEquals("jade:serial:$EFUSE_MAC", stored.id)
        assertEquals(HwWalletVendor.BLOCKSTREAM, stored.vendor)
        assertEquals(EFUSE_MAC, stored.jadeDeviceId)
        assertEquals(USB_PATH, stored.path)
        assertEquals("zpubNS", stored.xpubs[HwFundingAddressType.NATIVE_SEGWIT.settingsKey])
        assertEquals("Jade", stored.model)
        assertEquals(stored.id, connected.id)
        assertFalse(connected.isLocked)
        assertTrue(sut.state.value.nearbyDevices.isEmpty())
    }

    @Test
    fun `connect refuses a jade that has no wallet yet`() = test {
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(usbDevice))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.UNINIT))
        val sut = createRepo()
        sut.scan()

        val result = sut.connect(USB_PATH)

        assertTrue(result.exceptionOrNull() is HwDeviceUninitializedError)
        verify(jadeService).disconnect()
        verify(jadeService, never()).unlock(any())
        assertNull(sut.state.value.connected)
    }

    @Test
    fun `connect reads the accounts again without taproot on old firmware`() = test {
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(usbDevice))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.READY))
        whenever { jadeService.getAccountExport(eq(JadeNetwork.REGTEST), eq(ALL_ACCOUNT_TYPES), any()) }
            .thenAnswer { throw JadeException.UnsupportedFirmware(installed = "1.0.30", required = "1.0.34") }
        whenever { jadeService.getAccountExport(eq(JadeNetwork.REGTEST), eq(WITHOUT_TAPROOT), any()) }
            .thenReturn(accountExport())
        val sut = createRepo()
        sut.scan()

        val result = sut.connect(USB_PATH)

        assertTrue(result.isSuccess, "err=${result.exceptionOrNull()}")
        verify(
            jadeService
        ).getAccountExport(eq(JadeNetwork.REGTEST), eq(ALL_ACCOUNT_TYPES - AccountType.TAPROOT), any())
    }

    @Test
    fun `a replugged jade refreshes its stored entry instead of adding one`() = test {
        whenever { hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM) }.thenReturn(listOf(knownUsb))
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(usbDevice))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.READY))
        val sut = createRepo()
        sut.scan()

        sut.connect(USB_PATH).getOrThrow()

        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore).saveKnownDevices(captor.capture(), anyOrNull(), eq(HwWalletVendor.BLOCKSTREAM))
        val stored = captor.firstValue.single()
        assertEquals(knownUsb.id, stored.id)
        assertEquals(USB_PATH, stored.path)
        assertEquals(WALLET_ID, stored.walletId)
    }

    @Test
    fun `reconnecting a known jade rejects a different device`() = test {
        whenever { hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM) }.thenReturn(listOf(knownUsb))
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(usbDevice))
        whenever { jadeService.connect(any(), any(), any()) }
            .thenReturn(versionInfo(JadeState.READY, efuseMac = "other"))
        val sut = createRepo()

        val result = sut.connectKnownDevice(knownUsb.id)

        assertTrue(result.isFailure)
        verify(jadeService, atLeastOnce()).disconnect()
        verify(jadeService, never()).getAccountExport(any(), any(), any())
        assertNull(sut.state.value.connected)
    }

    @Test
    fun `a known bluetooth jade is recognised by name after its address changed`() = test {
        val knownBle = knownUsb.copy(
            id = "jade:bluetooth:$EFUSE_MAC",
            path = "ble:6B:7A:9B:16:C8:1C",
            transportType = TransportType.BLUETOOTH,
        )
        val readvertised = JadeDeviceInfo(
            path = "ble:56:C4:BF:B3:9E:75",
            transport = JadeTransportKind.BLUETOOTH,
            name = "Jade 8F6B64",
            serialNumber = null,
        )
        whenever { hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM) }.thenReturn(listOf(knownBle))
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(readvertised))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.READY))
        val sut = createRepo()

        val connected = sut.connectKnownDevice(knownBle.id).getOrThrow()

        assertEquals(readvertised.path, connected.path)
        verify(jadeService).connect(eq(JadeTransportKind.BLUETOOTH), eq(readvertised.path), any())
        val nearby = sut.scan().getOrThrow()
        assertEquals(listOf(readvertised), nearby)
        assertTrue(sut.state.value.nearbyDevices.isEmpty(), "a paired jade is not offered as new")
    }

    @Test
    fun `silent auto reconnect never asks for the pin`() = test {
        whenever { hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM) }.thenReturn(listOf(knownUsb))
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(usbDevice))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.LOCKED))
        val sut = createRepo()

        val result = sut.autoReconnect(preferredTransport = TransportType.USB)

        val connected = result.getOrThrow()
        assertTrue(connected.isLocked)
        assertEquals(WALLET_ID, connected.walletId)
        verify(jadeService, never()).unlock(any())
        verify(jadeService, never()).getAccountExport(any(), any(), any())
    }

    @Test
    fun `ensureConnected unlocks a locked session`() = test {
        whenever { hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM) }.thenReturn(listOf(knownUsb))
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(usbDevice))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.LOCKED))
        val sut = createRepo()
        sut.autoReconnect(preferredTransport = TransportType.USB).getOrThrow()
        whenever { jadeService.isConnected() }.thenReturn(true)

        val result = sut.ensureConnected(knownUsb.id)

        assertFalse(result.getOrThrow().isLocked)
        verify(jadeService).unlock(JadeNetwork.REGTEST)
        // The live session is reused: no second connect.
        verify(jadeService).connect(any(), any(), any())
    }

    @Test
    fun `a bluetooth link is released after the app stays in the background`() = test {
        val knownBle = knownUsb.copy(
            id = "jade:bluetooth:$EFUSE_MAC",
            path = "ble:56:C4:BF:B3:9E:75",
            transportType = TransportType.BLUETOOTH,
        )
        val bleDevice = JadeDeviceInfo(knownBle.path, JadeTransportKind.BLUETOOTH, "Jade 8F6B64", null)
        whenever { hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM) }.thenReturn(listOf(knownBle))
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(bleDevice))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.READY))
        val sut = createRepo()
        sut.connectKnownDevice(knownBle.id).getOrThrow()

        sut.onAppBackgrounded()
        advanceTimeBy(10.seconds)
        sut.onAppForegrounded()
        advanceTimeBy(60.seconds)
        verify(jadeService, never()).disconnect()

        sut.onAppBackgrounded()
        advanceTimeBy(31.seconds)

        verify(jadeService).disconnect()
        assertNull(sut.state.value.connected)
    }

    @Test
    fun `a usb link is kept while the app is in the background`() = test {
        whenever { hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM) }.thenReturn(listOf(knownUsb))
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(usbDevice))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.READY))
        val sut = createRepo()
        sut.connectKnownDevice(knownUsb.id).getOrThrow()

        sut.onAppBackgrounded()
        advanceTimeBy(60.seconds)

        verify(jadeService, never()).disconnect()
    }

    @Test
    fun `an external disconnect clears the session and tells core`() = test {
        whenever { hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM) }.thenReturn(listOf(knownUsb))
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(usbDevice))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.READY))
        val sut = createRepo()
        sut.connectKnownDevice(knownUsb.id).getOrThrow()

        externalDisconnect.emit(USB_PATH)
        advanceUntilIdle()

        assertNull(sut.state.value.connected)
        verify(jadeService).notifyDisconnected(USB_PATH)
    }

    @Test
    fun `forgetting the connected jade closes its session and drops the entry`() = test {
        whenever { hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM) }.thenReturn(listOf(knownUsb))
        whenever { jadeService.scan(any(), any()) }.thenReturn(listOf(usbDevice))
        whenever { jadeService.connect(any(), any(), any()) }.thenReturn(versionInfo(JadeState.READY))
        val sut = createRepo()
        sut.connectKnownDevice(knownUsb.id).getOrThrow()

        val result = sut.forgetDevice(knownUsb.id)

        assertTrue(result.isSuccess, "err=${result.exceptionOrNull()}")
        verify(jadeService).disconnect()
        val captor = argumentCaptor<List<KnownDevice>>()
        verify(hwWalletStore, atLeastOnce())
            .saveKnownDevices(captor.capture(), anyOrNull(), eq(HwWalletVendor.BLOCKSTREAM))
        assertTrue(captor.lastValue.isEmpty())
        assertNull(sut.state.value.connected)
    }

    @Test
    fun `signPsbt completes the signed psbt into a transaction`() = test {
        val completed = CompletedTransaction(serializedTx = "rawtx", txid = "txid")
        whenever { jadeService.signPsbt(JadeNetwork.REGTEST, "psbt") }.thenReturn("signed")
        whenever { jadeService.finalizePsbt("psbt", "signed") }.thenReturn(completed)
        val sut = createRepo()

        val result = sut.signPsbt("psbt")

        assertEquals(completed, result.getOrThrow())
    }

    @Test
    fun `verifyAddress asks the device for the native segwit variant`() = test {
        val sut = createRepo()

        val result = sut.verifyAddress(HwFundingAddressType.NATIVE_SEGWIT, "m/84'/1'/0'/0/0", "bcrt1q")

        assertTrue(result.isSuccess)
        verify(jadeService).verifyAddress(JadeNetwork.REGTEST, JadeAddressVariant.WPKH, "m/84'/1'/0'/0/0", "bcrt1q")
    }

    private fun createRepo() = JadeRepo(
        context = context,
        jadeService = jadeService,
        jadeTransport = jadeTransport,
        hwWalletStore = hwWalletStore,
        clock = Clock.System,
        ioDispatcher = testDispatcher,
    )

    private fun versionInfo(state: JadeState, efuseMac: String? = EFUSE_MAC) = JadeVersionInfo(
        jadeVersion = "1.0.41",
        jadeState = state,
        jadeNetworks = "ALL",
        jadeHasPin = true,
        boardType = "JADE_V1_1",
        jadeConfig = null,
        jadeFeatures = null,
        idfVersion = null,
        chipFeatures = null,
        efuseMac = efuseMac,
        batteryStatus = null,
        jadeOtaMaxChunk = null,
    )

    private fun accountExport() = JadeAccountExport(
        masterFingerprint = "deadbeef",
        accountIndex = 0u,
        accounts = listOf(
            JadeAccount(variant = JadeAddressVariant.WPKH, xpub = "zpubNS", derivationPath = "m/84'/1'/0'"),
        ),
    )

    private companion object {
        const val USB_PATH = "/dev/bus/usb/001/007"
        const val EFUSE_MAC = "246F288F6B64"
        const val WALLET_ID = "jade:wallet"
        val ALL_ACCOUNT_TYPES = listOf(
            AccountType.LEGACY,
            AccountType.WRAPPED_SEGWIT,
            AccountType.NATIVE_SEGWIT,
            AccountType.TAPROOT,
        )
        val WITHOUT_TAPROOT = ALL_ACCOUNT_TYPES - AccountType.TAPROOT
    }
}
