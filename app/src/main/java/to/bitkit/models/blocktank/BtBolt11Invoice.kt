package to.bitkit.models.blocktank

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BtBolt11Invoice(
    val request: String,
    val state: BtBolt11InvoiceState,
    val expiresAt: Instant,
    val updatedAt: Instant,
)
