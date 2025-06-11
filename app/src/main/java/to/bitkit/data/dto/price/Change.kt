package to.bitkit.data.dto.price

import kotlinx.serialization.Serializable

@Serializable

data class Change(
    val isPositive: Boolean,
    val formatted: String
)
