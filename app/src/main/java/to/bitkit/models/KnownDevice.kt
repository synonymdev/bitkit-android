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
    /** Stable Bitkit Core wallet scope for hardware activity metadata. */
    val walletId: String = "",
)
