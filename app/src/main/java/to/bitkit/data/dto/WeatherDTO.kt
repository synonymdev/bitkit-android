
package to.bitkit.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class WeatherDTO(
    val condition: FeeCondition,
    val currentFee: String,
    val nextBlockFee: Int,
    val avgFeeSats: Long = 0L,
)
