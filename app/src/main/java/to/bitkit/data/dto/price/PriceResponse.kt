package to.bitkit.data.dto.price

import kotlinx.serialization.Serializable

@Serializable
data class PriceResponse(
    val price: Double,
    val timestamp: Long
)
