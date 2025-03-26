package to.bitkit.models

import kotlinx.serialization.Serializable

@Serializable
data class BalanceState(
    val totalOnchainSats: ULong = 0uL,
    val totalLightningSats: ULong = 0uL,
    val totalSats: ULong = 0uL,
)
