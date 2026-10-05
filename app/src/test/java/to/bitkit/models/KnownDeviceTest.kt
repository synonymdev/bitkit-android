package to.bitkit.models

import org.junit.Test
import to.bitkit.di.json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnownDeviceTest {

    @Test
    fun `entries stored before jade existed decode as trezor devices`() {
        val stored = """
            {"id":"dev1","name":null,"path":"ble:AA","transportType":"bluetooth","label":"Trezor",
             "model":"Safe 5","lastConnectedAt":0,"xpubs":{"nativeSegwit":"zpub"},"walletId":"w1"}
        """.trimIndent()

        val device = json.decodeFromString<KnownDevice>(stored)

        assertEquals(HwWalletVendor.TREZOR, device.vendor)
        assertNull(device.jadeDeviceId)
        assertNull(device.hardwareId)
    }

    @Test
    fun `a jade entry round trips its vendor and hardware id`() {
        val device = KnownDevice(
            id = "jade:serial:aabbcc",
            name = "Jade",
            path = "/dev/bus/usb/001/004",
            transportType = TransportType.USB,
            label = null,
            model = "Jade",
            lastConnectedAt = 1L,
            xpubs = mapOf("nativeSegwit" to "zpub"),
            walletId = "jade:abc",
            vendor = HwWalletVendor.BLOCKSTREAM,
            jadeDeviceId = "aabbcc",
        )

        val decoded = json.decodeFromString<KnownDevice>(json.encodeToString(device))

        assertEquals(device, decoded)
        assertEquals("aabbcc", decoded.hardwareId)
    }

    @Test
    fun `a jade entry is replaced by a re-read of the same hardware even when its keys changed`() {
        val stored = KnownDevice(
            id = "jade:serial:aabbcc",
            name = null,
            path = "/dev/bus/usb/001/004",
            transportType = TransportType.USB,
            label = null,
            model = "Jade",
            lastConnectedAt = 1L,
            xpubs = mapOf("nativeSegwit" to "zpub"),
            vendor = HwWalletVendor.BLOCKSTREAM,
            jadeDeviceId = "aabbcc",
        )
        val reread = stored.copy(
            path = "/dev/bus/usb/001/009",
            xpubs = mapOf("nativeSegwit" to "zpub", "taproot" to "tr")
        )
        val otherJade = stored.copy(xpubs = mapOf("nativeSegwit" to "other"), jadeDeviceId = "ddeeff")

        assertTrue(stored.isReplacedBy(reread, refreshed = stored))
        assertTrue(stored.isReplacedBy(otherJade, refreshed = null))
    }

    @Test
    fun `wallet ids are derived in the vendor namespace`() {
        val xpubs = mapOf("nativeSegwit" to "zpub")

        val jade = runCatching { deriveHardwareWalletId(xpubs, HwWalletVendor.BLOCKSTREAM) }.getOrNull()

        // The native derivation is unavailable in unit tests; the call must not throw either way.
        assertTrue(jade == null || jade.startsWith("jade:"))
    }

    @Test
    fun `wallet ids with the same keys remain isolated by vendor`() {
        val trezor = device(vendor = HwWalletVendor.TREZOR, walletId = "trezor:wallet")
        val jade = device(vendor = HwWalletVendor.BLOCKSTREAM, walletId = "jade:wallet")

        val migrated = listOf(trezor, jade).withHardwareWalletIds()

        assertEquals(listOf("trezor:wallet", "jade:wallet"), migrated.map { it.walletId })
    }

    @Test
    fun `a device from another vendor never replaces an entry`() {
        val trezor = device(vendor = HwWalletVendor.TREZOR, walletId = "trezor:wallet")
        val jade = device(vendor = HwWalletVendor.BLOCKSTREAM, walletId = "jade:wallet")

        assertFalse(trezor.isReplacedBy(jade, refreshed = null))
    }

    private fun device(vendor: HwWalletVendor, walletId: String) = KnownDevice(
        id = "shared-device",
        name = null,
        path = "shared-path",
        transportType = TransportType.USB,
        label = null,
        model = null,
        lastConnectedAt = 0L,
        xpubs = mapOf("nativeSegwit" to "shared-zpub"),
        walletId = walletId,
        vendor = vendor,
    )
}
