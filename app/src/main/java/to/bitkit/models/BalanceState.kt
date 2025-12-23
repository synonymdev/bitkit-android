package to.bitkit.models

import kotlinx.serialization.Serializable

@Serializable
data class BalanceState(
    val totalOnchainSats: ULong = 0uL,
    val totalLightningSats: ULong = 0uL,
    val maxSendLightningSats: ULong = 0uL, // Without account routing fees
    val maxSendOnchainSats: ULong = 0uL,
    val balanceInTransferToSavings: ULong = 0uL,
    val balanceInTransferToSpending: ULong = 0uL,
) {
    val totalSats get() = totalOnchainSats + totalLightningSats
}
