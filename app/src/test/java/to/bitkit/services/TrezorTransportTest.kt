package to.bitkit.services

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class TrezorTransportTest {
    private companion object {
        const val KNOWN_DEVICE_ID = "ble:AA:BB:CC:DD:EE:FF"
        const val UNKNOWN_DEVICE_ID = "ble:11:22:33:44:55:66"
        const val CREDENTIAL = "{\"key\":\"value\"}"
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock<Context>()
    private val usbManager = mock<UsbManager>()
    private val bridgeTransport = mock<TrezorBridgeTransport>()

    @Test
    fun `quiet usb open does not request permission`() {
        val path = "/dev/bus/usb/001/002"
        val device = mock<UsbDevice>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("to.bitkit.dev")
        whenever(context.getSystemService(Context.USB_SERVICE)).thenReturn(usbManager)
        whenever(usbManager.deviceList).thenReturn(hashMapOf(path to device))
        whenever(usbManager.hasPermission(device)).thenReturn(false)
        whenever(bridgeTransport.isBridgeDevice(path)).thenReturn(false)
        val sut = createSut()

        val result = runBlocking {
            sut.withUsbPermissionRequestsEnabled(false) {
                sut.openDevice(path)
            }
        }

        assertFalse(result.success)
        assertEquals("USB permission missing for '$path'", result.error)
        verify(usbManager, never()).requestPermission(eq(device), any<PendingIntent>())
    }

    @Test
    fun `enumerateDevices can skip bluetooth scan`() {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("to.bitkit.dev")
        whenever(context.getSystemService(Context.USB_SERVICE)).thenReturn(usbManager)
        whenever(usbManager.deviceList).thenReturn(hashMapOf())
        whenever(bridgeTransport.enumerateDevices()).thenReturn(emptyList())
        val sut = createSut()

        val result = runBlocking {
            sut.withBluetoothScanningEnabled(false) {
                sut.enumerateDevices()
            }
        }

        assertTrue(result.isEmpty())
        verify(context, never()).getSystemService(Context.BLUETOOTH_SERVICE)
    }

    @Test
    fun `each pairing callback emits a distinct request id`() {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("to.bitkit.dev")
        val sut = createSut()
        val results = Collections.synchronizedList(mutableListOf<String>())

        val firstCallback = thread { results += sut.getPairingCode() }
        val firstRequestId = awaitPairingRequestId(sut)
        sut.submitPairingCode("123456")
        firstCallback.join()

        val secondCallback = thread { results += sut.getPairingCode() }
        val secondRequestId = awaitPairingRequestId(sut, excluding = firstRequestId)
        sut.submitPairingCode("654321")
        secondCallback.join()

        assertNotEquals(firstRequestId, secondRequestId)
        assertEquals(listOf("123456", "654321"), results)
    }

    @Test
    fun `a refused pairing callback is answered at once and never shown`() {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("to.bitkit.dev")
        val sut = createSut()

        sut.refusePairingCodeRequests()
        val refused = sut.getPairingCode()

        assertEquals("", refused)
        assertFalse(sut.needsPairingCode.value)
        assertNull(sut.pairingCodeRequestId.value)

        sut.allowPairingCodeRequests()
        var code: String? = null
        val callback = thread { code = sut.getPairingCode() }
        awaitPairingRequestId(sut)
        sut.submitPairingCode("123456")
        callback.join()
        assertEquals("123456", code)
    }

    @Test
    fun `clearing all credentials also removes those of unknown devices`() {
        val editor = stubLegacyPrefs(deleteSucceeds = true)
        val sut = createSut()
        assertTrue(sut.saveThpCredential(KNOWN_DEVICE_ID, CREDENTIAL))
        assertTrue(sut.saveThpCredential(UNKNOWN_DEVICE_ID, CREDENTIAL))

        assertTrue(sut.clearAllDeviceCredentials().isSuccess)

        assertNull(sut.loadThpCredential(KNOWN_DEVICE_ID))
        assertNull(sut.loadThpCredential(UNKNOWN_DEVICE_ID))
        assertEquals(emptyList(), File(temporaryFolder.root, "trezor-thp-credentials").list()?.toList())
        verify(editor).clear()
    }

    @Test
    fun `clearing all credentials fails when a credential file cannot be deleted`() {
        stubLegacyPrefs(deleteSucceeds = true)
        val sut = createSut()
        assertTrue(sut.saveThpCredential(UNKNOWN_DEVICE_ID, CREDENTIAL))
        val credentialDir = File(temporaryFolder.root, "trezor-thp-credentials")
        // A read-only directory keeps its entries
        assertTrue(credentialDir.setWritable(false))

        val result = try {
            sut.clearAllDeviceCredentials()
        } finally {
            credentialDir.setWritable(true)
        }

        assertTrue(result.isFailure)
        assertEquals(CREDENTIAL, sut.loadThpCredential(UNKNOWN_DEVICE_ID))
    }

    @Test
    fun `clearing all credentials fails when the legacy preferences are not cleared`() {
        stubLegacyPrefs(deleteSucceeds = false)
        val sut = createSut()
        assertTrue(sut.saveThpCredential(UNKNOWN_DEVICE_ID, CREDENTIAL))

        val result = sut.clearAllDeviceCredentials()

        assertTrue(result.isFailure)
        assertNull(sut.loadThpCredential(UNKNOWN_DEVICE_ID))
    }

    private fun stubLegacyPrefs(deleteSucceeds: Boolean): SharedPreferences.Editor {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("to.bitkit.dev")
        whenever(context.filesDir).thenReturn(temporaryFolder.root)
        val prefs = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        whenever(context.getSharedPreferences(eq("trezor_thp_credentials"), any())).thenReturn(prefs)
        whenever(prefs.all).thenReturn(emptyMap<String, Any>())
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.clear()).thenReturn(editor)
        whenever(editor.commit()).thenReturn(true)
        whenever(context.deleteSharedPreferences("trezor_thp_credentials")).thenReturn(deleteSucceeds)
        return editor
    }

    @Test
    fun `bluetooth off releases every pending operation`() {
        val connectionLatch = CountDownLatch(1)
        val writeLatch = CountDownLatch(1)
        val disconnectLatch = CountDownLatch(1)

        releasePendingBleOperations(
            connectionLatch = connectionLatch,
            writeLatch = writeLatch,
            disconnectLatch = disconnectLatch,
        )

        assertEquals(0L, connectionLatch.count)
        assertEquals(0L, writeLatch.count)
        assertEquals(0L, disconnectLatch.count)
    }

    private fun awaitPairingRequestId(
        sut: TrezorTransport,
        excluding: Long? = null,
    ): Long {
        repeat(100) {
            sut.pairingCodeRequestId.value?.takeIf { it != excluding }?.let { return it }
            Thread.sleep(10)
        }
        fail("Pairing code request was not emitted")
    }

    private fun createSut() = TrezorTransport(
        context = context,
        bridgeTransport = bridgeTransport,
        clock = Clock.System,
    )
}
