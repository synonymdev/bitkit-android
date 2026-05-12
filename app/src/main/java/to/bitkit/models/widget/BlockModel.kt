package to.bitkit.models.widget

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import to.bitkit.data.dto.BlockDTO
import to.bitkit.ext.toDateUTC
import to.bitkit.ext.toTimeUTC

@Immutable
@Serializable
data class BlockModel(
    val height: String,
    val time: String,
    val date: String,
    val transactionCount: String,
    val size: String,
    val source: String,
    val fees: String,
)

fun BlockDTO.toBlockModel() = BlockModel(
    height = this.height,
    time = this.timestamp.toTimeUTC() + " UTC",
    date = this.timestamp.toDateUTC(),
    transactionCount = this.transactionCount,
    size = this.size,
    source = this.source,
    fees = this.fees,
)
