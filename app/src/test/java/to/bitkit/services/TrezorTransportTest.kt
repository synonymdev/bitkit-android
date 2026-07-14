package to.bitkit.services

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class TrezorTransportTest {

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
