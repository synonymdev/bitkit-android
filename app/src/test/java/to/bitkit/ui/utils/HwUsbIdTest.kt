package to.bitkit.ui.utils

import android.hardware.usb.UsbDevice
import org.junit.Test
import org.mockito.kotlin.mock
import to.bitkit.models.HwWalletVendor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HwUsbIdTest {

    @Test
    fun `jade usb ids resolve to the blockstream vendor`() {
        assertEquals(HwWalletVendor.BLOCKSTREAM, usbDevice(0x10C4, 0xEA60).hwVendorOrNull())
        assertEquals(HwWalletVendor.BLOCKSTREAM, usbDevice(0x303A, 0x4001).hwVendorOrNull())
        assertEquals(HwWalletVendor.BLOCKSTREAM, usbDevice(0x303A, 0x1001).hwVendorOrNull())
    }

    @Test
    fun `trezor usb ids resolve to the trezor vendor and flag the bootloader`() {
        assertEquals(HwWalletVendor.TREZOR, usbDevice(0x1209, 0x53C1).hwVendorOrNull())
        assertEquals(HwWalletVendor.TREZOR, usbDevice(0x534C, 0x0001).hwVendorOrNull())
        assertTrue(usbDevice(0x1209, 0x53C0).isHwBootloader())
        assertFalse(usbDevice(0x1209, 0x53C1).isHwBootloader())
    }

    @Test
    fun `unknown usb ids resolve to no vendor`() {
        assertNull(usbDevice(0x1A86, 0x7523).hwVendorOrNull())
        assertFalse(usbDevice(0x1A86, 0x7523).isHwBootloader())
    }

    private fun usbDevice(vendorId: Int, productId: Int): UsbDevice = mock {
        on { this.vendorId }.thenReturn(vendorId)
        on { this.productId }.thenReturn(productId)
    }
}
