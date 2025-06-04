package to.bitkit.models.widget

import kotlinx.serialization.Serializable
import to.bitkit.data.dto.BlockDTO
import to.bitkit.ext.toDateUTC
import to.bitkit.ext.toTimeUTC

@Serializable
data class BlockModel(
    val height: String,
    val time: String,
    val date: String,
    val transactionCount: String,
    val size: String,
    val fees: String,
    val source: String,
)

fun BlockDTO.toBlockModel() = BlockModel(
    height = this.height,
    time = this.timestamp.toTimeUTC() + " UTC",
    date = this.timestamp.toDateUTC(),
    transactionCount = this.transactionCount,
    size = this.size,
    fees = "", //TODO
    source = this.source
)
