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
)
