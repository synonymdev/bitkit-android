package to.bitkit.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class BalanceState(
    val totalOnchainSats: ULong = 0uL,
    val totalLightningSats: ULong = 0uL,
    val maxSendLightningSats: ULong = 0uL, // Without account routing fees
    val maxSendOnchainSats: ULong = 0uL,
    val balanceInTransferToSavings: ULong = 0uL,
    val balanceInTransferToSpending: ULong = 0uL,
    @Transient val forceCloseRemainingDuration: String? = null,
) {
    val totalSats get() = totalOnchainSats + totalLightningSats
}
