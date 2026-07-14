package to.bitkit.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.lightningdevkit.ldknode.Network
import to.bitkit.env.Env

@Serializable
enum class WatchOnlyAccountSetupState {
    @SerialName("pendingDelivery")
    PendingDelivery,

    @SerialName("active")
    Active,
}

@Serializable
data class WatchOnlyAccountRecord(
    val id: String,
    val walletIndex: Int,
    val accountIndex: Int,
    val addressType: String,
    val xpub: String,
    val requestFingerprint: String,
    val createdAt: Long,
    val name: String,
    val isTrackingEnabled: Boolean,
    val setupState: WatchOnlyAccountSetupState,
) {
    val derivationPath: String
        get() {
            val coinType = if (Env.network == Network.BITCOIN) 0 else 1
            return "m/84'/$coinType'/$accountIndex'"
        }
}

data class PreparedWatchOnlyAccountClaim(
    val account: WatchOnlyAccountRecord,
    val payload: ByteArray,
)
