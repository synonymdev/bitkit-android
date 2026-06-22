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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private fun createSut() = TrezorTransport(
        context = context,
        bridgeTransport = bridgeTransport,
    )
}
