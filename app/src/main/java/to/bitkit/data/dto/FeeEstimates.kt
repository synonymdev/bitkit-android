package to.bitkit.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class FeeEstimates(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
) {
    val fast: Int get() = fastestFee
    val normal: Double get() = halfHourFee.toDouble()
}
