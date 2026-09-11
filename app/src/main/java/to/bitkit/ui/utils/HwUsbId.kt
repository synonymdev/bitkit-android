package to.bitkit.ui.utils

import android.hardware.usb.UsbDevice
import to.bitkit.models.HwWalletVendor

/** USB identities of the hardware wallets Bitkit recognises, mirrored by `res/xml/usb_device_filter.xml`. */
enum class HwUsbId(
    val vendorId: Int,
    val productId: Int,
    val vendor: HwWalletVendor,
    val isBootloader: Boolean = false,
) {
    TREZOR_WEBUSB(
        vendorId = 0x1209,
        productId = 0x53C1,
        vendor = HwWalletVendor.TREZOR,
    ),
    TREZOR_WEBUSB_BOOTLOADER(
        vendorId = 0x1209,
        productId = 0x53C0,
        vendor = HwWalletVendor.TREZOR,
        isBootloader = true,
    ),
    TREZOR_LEGACY(
        vendorId = 0x534C,
        productId = 0x0001,
        vendor = HwWalletVendor.TREZOR,
    ),

    /** Jade v1: Silicon Labs CP210x USB-serial bridge. */
    JADE_CP210X(
        vendorId = 0x10C4,
        productId = 0xEA60,
        vendor = HwWalletVendor.BLOCKSTREAM,
    ),

    /** Jade Plus: Espressif native USB CDC. */
    JADE_ESPRESSIF_CDC(
        vendorId = 0x303A,
        productId = 0x4001,
        vendor = HwWalletVendor.BLOCKSTREAM,
    ),

    /** Jade Plus: Espressif USB serial/JTAG. */
    JADE_ESPRESSIF_SERIAL_JTAG(
        vendorId = 0x303A,
        productId = 0x1001,
        vendor = HwWalletVendor.BLOCKSTREAM,
    ),
    ;

    companion object {
        fun of(vendorId: Int, productId: Int): HwUsbId? =
            entries.firstOrNull { it.vendorId == vendorId && it.productId == productId }
    }
}

fun UsbDevice.hwUsbId(): HwUsbId? = HwUsbId.of(vendorId, productId)

fun UsbDevice.hwVendorOrNull(): HwWalletVendor? = hwUsbId()?.vendor

fun UsbDevice.isHwBootloader(): Boolean = hwUsbId()?.isBootloader == true
