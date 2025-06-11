package to.bitkit.data.dto.price

import kotlinx.serialization.Serializable

@Serializable
data class CandleResponse(
    val timestamp: Long,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double
)
