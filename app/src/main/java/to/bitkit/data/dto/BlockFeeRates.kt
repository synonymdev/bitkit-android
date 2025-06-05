package to.bitkit.data.dto

@Serializable
data class BlockFeeRates(
    val avgHeight: Int,
    val timestamp: Long,
    @SerialName("avgFee_0") val avgFee0: Double,
    @SerialName("avgFee_10") val avgFee10: Double,
    @SerialName("avgFee_25") val avgFee25: Double,
    @SerialName("avgFee_50") val avgFee50: Double,
    @SerialName("avgFee_75") val avgFee75: Double,
    @SerialName("avgFee_90") val avgFee90: Double,
    @SerialName("avgFee_100") val avgFee100: Double
)
