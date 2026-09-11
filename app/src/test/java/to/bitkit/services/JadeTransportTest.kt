package to.bitkit.services

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.synonym.bitkitcore.JadeTransportErrorCode
import com.synonym.bitkitcore.JadeTransportKind
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JadeTransportTest {

    private val context = mock<Context>()
    private val usbManager = mock<UsbManager>()

    @Before
    fun setUp() {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("to.bitkit.dev")
        whenever(context.getSystemService(Context.USB_SERVICE)).thenReturn(usbManager)
        whenever(usbManager.deviceList).thenReturn(hashMapOf())
    }

    @Test
    fun `quiet usb open does not request permission`() {
        val device = cp210xDevice()
        whenever(usbManager.deviceList).thenReturn(hashMapOf(USB_PATH to device))
        whenever(usbManager.hasPermission(device)).thenReturn(false)
        val sut = createSut()

        val result = runBlocking {
            sut.withUsbPermissionRequestsEnabled(false) {
                sut.openDevice(USB_PATH)
            }
        }

        assertFalse(result.success)
        assertEquals("USB permission missing for '$USB_PATH'", result.error)
        assertEquals(JadeTransportErrorCode.PERMISSION_DENIED, result.errorCode)
        verify(usbManager, never()).requestPermission(eq(device), any<PendingIntent>())
    }

    @Test
    fun `scanDevices can skip the bluetooth scan`() {
        val sut = createSut()

        val result = runBlocking {
            sut.withBluetoothScanningEnabled(false) {
                sut.scanDevices(3_000u)
            }
        }

        assertTrue(result.isEmpty())
        verify(context, never()).getSystemService(Context.BLUETOOTH_SERVICE)
    }

    @Test
    fun `scanDevices lists only jade usb devices as serial devices`() {
        val jade = cp210xDevice()
        val jadePlus = cdcDevice(deviceName = "/dev/bus/usb/001/003")
        val trezor = usbDevice(vendorId = 0x1209, productId = 0x53C1, deviceName = "/dev/bus/usb/001/004")
        whenever(usbManager.deviceList).thenReturn(
            hashMapOf(
                "/dev/bus/usb/001/002" to jade,
                "/dev/bus/usb/001/003" to jadePlus,
                "/dev/bus/usb/001/004" to trezor
            ),
        )
        val sut = createSut()

        val result = runBlocking {
            sut.withBluetoothScanningEnabled(false) {
                sut.scanDevices(3_000u)
            }
        }

        assertEquals(2, result.size)
        assertTrue(result.all { it.transport == JadeTransportKind.SERIAL })
        assertEquals(setOf("/dev/bus/usb/001/002", "/dev/bus/usb/001/003"), result.map { it.path }.toSet())
    }

    @Test
    fun `selectUsbDriver picks cdc data and control interfaces`() {
        val selection = assertNotNull(selectUsbDriver(cdcDevice()))

        assertEquals(UsbDriverKind.CDC_ACM, selection.kind)
        assertEquals(UsbConstants.USB_CLASS_CDC_DATA, selection.dataInterface.interfaceClass)
        assertEquals(UsbConstants.USB_CLASS_COMM, selection.controlInterface?.interfaceClass)
        assertEquals(UsbConstants.USB_DIR_IN, selection.readEndpoint.direction)
        assertEquals(UsbConstants.USB_DIR_OUT, selection.writeEndpoint.direction)
    }

    @Test
    fun `selectUsbDriver picks the cp210x interface`() {
        val selection = assertNotNull(selectUsbDriver(cp210xDevice()))

        assertEquals(UsbDriverKind.CP210X, selection.kind)
        assertNull(selection.controlInterface)
    }

    @Test
    fun `selectUsbDriver rejects an unsupported device`() {
        assertNull(selectUsbDriver(usbDevice(vendorId = 0x1209, productId = 0x53C1)))
    }

    @Test
    fun `opening a cp210x sends the serial setup sequence in order`() {
        val device = cp210xDevice()
        val connection = openable(device)
        val sut = createSut()

        val result = sut.openDevice(USB_PATH)

        assertTrue(result.success, result.error)
        inOrder(connection) {
            verify(connection).claimInterface(any(), eq(true))
            verify(connection).controlTransfer(eq(0x41), eq(0x00), eq(0x0001), eq(0), isNull(), eq(0), any())
            verify(connection).controlTransfer(
                eq(0x41),
                eq(0x1E),
                eq(0),
                eq(0),
                eq(byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00)),
                eq(4),
                any(),
            )
            verify(connection).controlTransfer(eq(0x41), eq(0x03), eq(0x0800), eq(0), isNull(), eq(0), any())
            verify(connection).controlTransfer(eq(0x41), eq(0x07), eq(0x0303), eq(0), isNull(), eq(0), any())
            verify(connection).controlTransfer(eq(0x41), eq(0x12), eq(0x000F), eq(0), isNull(), eq(0), any())
        }
    }

    @Test
    fun `opening a cdc device claims both interfaces and sets the line state`() {
        val device = cdcDevice()
        val connection = openable(device)
        val sut = createSut()

        val result = sut.openDevice(USB_PATH)

        assertTrue(result.success, result.error)
        verify(connection).claimInterface(device.getInterface(0), true)
        verify(connection).claimInterface(device.getInterface(1), true)
        verify(connection).controlTransfer(
            eq(0x21),
            eq(0x20),
            eq(0),
            eq(0),
            eq(byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)),
            eq(7),
            any(),
        )
        verify(connection).controlTransfer(eq(0x21), eq(0x22), eq(0x0003), eq(0), isNull(), eq(0), any())
    }

    @Test
    fun `open fails and closes the connection when the interface cannot be claimed`() {
        val device = cp210xDevice()
        val connection = openable(device)
        whenever(connection.claimInterface(any(), any())).thenReturn(false)
        val sut = createSut()

        val result = sut.openDevice(USB_PATH)

        assertFalse(result.success)
        verify(connection).close()
    }

    @Test
    fun `chunk sizes follow the transport`() {
        val sut = createSut()

        assertEquals(509u, sut.getChunkSize(USB_PATH))
        assertEquals(20u, JadeTransport.chunkSizeForMtu(23))
        assertEquals(244u, JadeTransport.chunkSizeForMtu(247))
        assertEquals(509u, JadeTransport.chunkSizeForMtu(517))
        assertEquals(1u, JadeTransport.chunkSizeForMtu(0))
        assertEquals(20u, sut.getChunkSize("ble:AA:BB"))
    }

    @Test
    fun `operations on a device that is not open report not connected`() {
        val sut = createSut()

        assertEquals(JadeTransportErrorCode.NOT_CONNECTED, sut.readChunk(USB_PATH, 250u).errorCode)
        assertEquals(JadeTransportErrorCode.NOT_CONNECTED, sut.writeChunk(USB_PATH, byteArrayOf(1)).errorCode)
        assertTrue(sut.closeDevice(USB_PATH).success)
    }

    @Test
    fun `a read timeout is reported as an empty successful read`() {
        val device = cp210xDevice()
        val connection = openable(device)
        whenever(connection.bulkTransfer(any(), any<ByteArray>(), any(), any())).thenReturn(-1)
        val sut = createSut()
        sut.openDevice(USB_PATH)

        val result = sut.readChunk(USB_PATH, 250u)

        assertTrue(result.success)
        assertTrue(result.data.isEmpty())
        assertNull(result.errorCode)
    }

    @Test
    fun `a read returns only the bytes received into a max packet buffer`() {
        val device = cp210xDevice()
        val connection = openable(device)
        whenever(connection.bulkTransfer(any(), any<ByteArray>(), any(), any())).thenAnswer {
            val buffer = it.getArgument<ByteArray>(1)
            assertEquals(64, buffer.size)
            buffer[0] = 7
            buffer[1] = 8
            2
        }
        val sut = createSut()
        sut.openDevice(USB_PATH)

        val result = sut.readChunk(USB_PATH, 250u)

        assertTrue(result.success)
        assertContentEquals(byteArrayOf(7, 8), result.data)
    }

    @Test
    fun `a read after unplugging reports a disconnect`() {
        val device = cp210xDevice()
        val connection = openable(device)
        whenever(connection.bulkTransfer(any(), any<ByteArray>(), any(), any())).thenReturn(-1)
        val sut = createSut()
        sut.openDevice(USB_PATH)
        whenever(usbManager.deviceList).thenReturn(hashMapOf())

        val result = sut.readChunk(USB_PATH, 250u)

        assertFalse(result.success)
        assertEquals(JadeTransportErrorCode.DISCONNECTED, result.errorCode)
    }

    @Test
    fun `a short write reports a timeout`() {
        val device = cp210xDevice()
        val connection = openable(device)
        whenever(connection.bulkTransfer(any(), any<ByteArray>(), any(), any())).thenReturn(3)
        val sut = createSut()
        sut.openDevice(USB_PATH)

        val result = sut.writeChunk(USB_PATH, ByteArray(10))

        assertFalse(result.success)
        assertEquals(JadeTransportErrorCode.TIMEOUT, result.errorCode)
    }

    @Test
    fun `closing clears the modem lines and releases the interface once`() {
        val device = cp210xDevice()
        val connection = openable(device)
        val sut = createSut()
        sut.openDevice(USB_PATH)

        assertTrue(sut.closeDevice(USB_PATH).success)
        assertTrue(sut.closeDevice(USB_PATH).success)

        verify(connection).controlTransfer(eq(0x41), eq(0x07), eq(0x0300), eq(0), isNull(), eq(0), any())
        verify(connection).releaseInterface(device.getInterface(0))
        verify(connection).close()
    }

    private fun openable(device: UsbDevice): UsbDeviceConnection {
        val connection = mock<UsbDeviceConnection>()
        whenever(usbManager.deviceList).thenReturn(hashMapOf(USB_PATH to device))
        whenever(usbManager.hasPermission(device)).thenReturn(true)
        whenever(usbManager.openDevice(device)).thenReturn(connection)
        whenever(connection.claimInterface(any(), any())).thenReturn(true)
        whenever(connection.controlTransfer(any(), any(), any(), any(), anyOrNull(), any(), any())).thenReturn(0)
        return connection
    }

    private fun createSut() = JadeTransport(context = context)

    private fun usbDevice(
        vendorId: Int,
        productId: Int,
        interfaces: List<UsbInterface> = emptyList(),
        deviceName: String = USB_PATH,
    ): UsbDevice =
        mock {
            on { this.vendorId }.thenReturn(vendorId)
            on { this.productId }.thenReturn(productId)
            on { this.deviceName }.thenReturn(deviceName)
            on { interfaceCount }.thenReturn(interfaces.size)
            interfaces.forEachIndexed { index, usbInterface ->
                on { getInterface(index) }.thenReturn(usbInterface)
            }
        }

    private fun cp210xDevice(): UsbDevice = usbDevice(
        vendorId = 0x10C4,
        productId = 0xEA60,
        interfaces = listOf(usbInterface(id = 0, interfaceClass = UsbConstants.USB_CLASS_VENDOR_SPEC, bulk = true)),
    )

    private fun cdcDevice(deviceName: String = USB_PATH): UsbDevice = usbDevice(
        vendorId = 0x303A,
        productId = 0x4001,
        interfaces = listOf(
            usbInterface(id = 0, interfaceClass = UsbConstants.USB_CLASS_COMM, bulk = false),
            usbInterface(id = 1, interfaceClass = UsbConstants.USB_CLASS_CDC_DATA, bulk = true),
        ),
        deviceName = deviceName,
    )

    private fun usbInterface(id: Int, interfaceClass: Int, bulk: Boolean): UsbInterface {
        val endpoints = if (bulk) {
            listOf(
                endpoint(UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_BULK),
                endpoint(UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK),
            )
        } else {
            listOf(endpoint(UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_INT))
        }
        return mock {
            on { this.id }.thenReturn(id)
            on { this.interfaceClass }.thenReturn(interfaceClass)
            on { endpointCount }.thenReturn(endpoints.size)
            endpoints.forEachIndexed { index, endpoint ->
                on { getEndpoint(index) }.thenReturn(endpoint)
            }
        }
    }

    private fun endpoint(direction: Int, type: Int): UsbEndpoint = mock {
        on { this.direction }.thenReturn(direction)
        on { this.type }.thenReturn(type)
        on { maxPacketSize }.thenReturn(64)
    }

    private companion object {
        const val USB_PATH = "/dev/bus/usb/001/002"
    }
}
