package to.bitkit.models

import kotlinx.serialization.Serializable

@Serializable
data class BalanceState(
    val totalOnchainSats: ULong = 0uL,
    val totalLightningSats: ULong = 0uL,
    val maxSendLightningSats: ULong = 0uL, // TODO use where applicable
    val totalSats: ULong = 0uL,
)
