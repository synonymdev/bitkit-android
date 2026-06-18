package to.bitkit.models

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.synonym.bitkitcore.Activity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.Serializable

/** A paired hardware wallet tracked as a watch-only balance. */
@Stable
data class HwWallet(
    val id: String,
    val name: String,
    val model: String?,
    val transportType: TransportType,
    val isConnected: Boolean,
    val balanceSats: ULong,
    val activities: ImmutableList<Activity>,
    val deviceIds: ImmutableSet<String> = persistentSetOf(id),
)

/** Serializable per-device balance snapshot carried by [BalanceState]. */
@Immutable
@Serializable
data class HwWalletBalance(
    val id: String,
    val sats: ULong,
)

/** A newly detected inbound transaction to a watched hardware wallet. */
@Immutable
data class HwWalletReceivedTx(
    val txid: String,
    val sats: ULong,
)

fun HwWallet.toBalance() = HwWalletBalance(id = id, sats = balanceSats)
