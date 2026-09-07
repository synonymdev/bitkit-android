package to.bitkit.models

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class KnownDevice(
    val id: String,
    val name: String?,
    val path: String,
    val transportType: TransportType,
    val label: String?,
    val model: String?,
    val lastConnectedAt: Long,
    /** Account-level extended public keys per address type. */
    val xpubs: Map<String, String> = emptyMap(),
    /** Bitkit-side funds label set by the user while pairing; null until renamed within Bitkit. */
    val customLabel: String? = null,
    val walletId: String = "",
    /**
     * Whether this entry is a passphrase (hidden) wallet. Nothing else in the record can tell one
     * apart from the standard wallet: the xpubs are opaque and the selected mode only lives in
     * memory, so reconnects would silently fall back to the standard wallet without this. The
     * passphrase itself is never persisted.
     */
    val passphraseProtected: Boolean = false,
    /**
     * The Trezor's own device id, which it regenerates when wiped. Entries of the same transport
     * that report a different one belong to a seed the device can no longer sign for.
     */
    val trezorDeviceId: String? = null,
    /** Entries stored before other vendors existed carry no vendor and are Trezor ones. */
    val vendor: HwWalletVendor = HwWalletVendor.TREZOR,
    /** The Jade's efuse MAC: the one identifier that survives a USB replug, which renumbers [path]. */
    val jadeDeviceId: String? = null,
) {
    /** The vendor's own stable device identifier, when the device reported one. */
    val hardwareId: String?
        get() = when (vendor) {
            HwWalletVendor.TREZOR -> trezorDeviceId
            HwWalletVendor.BLOCKSTREAM -> jadeDeviceId
        }
}

internal fun KnownDevice.matches(deviceId: String) = id == deviceId || path == deviceId

/**
 * Cross-transport identity of the wallet a device entry tracks: entries created by pairing the same
 * physical device over different transports share the same xpubs. Entries without captured xpubs fall
 * back to their own transport-level id.
 */
internal val KnownDevice.walletKey: String
    get() = walletKey(xpubs, id)

internal fun walletKey(xpubs: Map<String, String>, fallback: String): String =
    xpubs.values.sorted().joinToString().ifEmpty { fallback }

/**
 * Whether a stored entry gives way to the one just read. That covers the identity it holds and the
 * entry this connect refreshed, since reading a previously rejected address type changes the
 * walletKey and matching on the new key alone would leave the old entry behind as a duplicate.
 * Wallets of a seed the device no longer carries go too: nothing would ever supersede them by key
 * material. An unknown device id proves nothing, so those entries are left alone.
 */
internal fun KnownDevice.isReplacedBy(known: KnownDevice, refreshed: KnownDevice?): Boolean {
    if (id != known.id) return false
    if (walletKey == known.walletKey) return true
    if (refreshed != null && walletKey == refreshed.walletKey) return true
    return known.hardwareId != null && hardwareId != null && hardwareId != known.hardwareId
}

internal fun deriveHardwareWalletId(xpubs: Map<String, String>, vendor: HwWalletVendor): String? =
    if (xpubs.isEmpty()) {
        null
    } else {
        runCatching { HwWalletId.derive(xpubs, deviceType = vendor.deviceType) }.getOrNull()
    }

internal fun List<KnownDevice>.findHardwareWalletId(
    xpubs: Map<String, String>,
    fallback: String,
    vendor: HwWalletVendor,
): String {
    val walletKey = walletKey(xpubs, fallback)
    return firstOrNull { it.walletKey == walletKey }?.walletId?.takeIf { it.isNotBlank() }
        ?: deriveHardwareWalletId(xpubs, vendor).orEmpty()
}

internal fun List<KnownDevice>.withHardwareWalletIds(): List<KnownDevice> {
    val existingByWallet = filter { it.walletId.isNotBlank() }
        .associate { it.walletKey to it.walletId }
    val generatedByWallet = mutableMapOf<String, String>()

    return map {
        val walletId = existingByWallet[it.walletKey]
            ?: generatedByWallet.getOrPut(it.walletKey) {
                deriveHardwareWalletId(it.xpubs, it.vendor).orEmpty()
            }
        if (it.walletId == walletId) it else it.copy(walletId = walletId)
    }
}
