package to.bitkit.models

data class BalanceState(
    val totalOnchainSats: ULong = 0uL,
    val totalLightningSats: ULong = 0uL,
    val totalSats: ULong = 0uL,
)
