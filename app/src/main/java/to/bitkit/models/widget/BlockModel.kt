package to.bitkit.models.widget

import kotlinx.serialization.Serializable
import to.bitkit.data.dto.BlockDTO

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
    time = this.timestamp.toString(), // TODO FORMMAT
    date = this.timestamp.toString(), //TODO FORMAT,
    transactionCount = this.transactionCount,
    size = this.size,
    fees = "", //TODO
    source = this.source
)
