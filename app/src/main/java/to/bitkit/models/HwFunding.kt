package to.bitkit.models

data class HwFundingTransaction(
    val psbt: String,
    val miningFeeSats: ULong,
    val feeRate: Float,
    val totalSpent: ULong,
    val satsPerVByte: ULong,
)

data class HwFundingBroadcastResult(
    val txId: String,
    val miningFeeSats: ULong,
    val feeRate: ULong,
    val totalSpent: ULong,
)
