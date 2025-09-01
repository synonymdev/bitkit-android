package to.bitkit.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tag_metadata")
/**
 * @param id This will be paymentHash, txId, or address depending on context
 * @param txId on-chain transaction id
 * @param address on-chain address
 * @param isReceive true for receive, false for send
 * */
data class TagMetadataEntity(
    @PrimaryKey val id: String,
    val paymentHash: String? = null,
    val txId: String? = null,
    val address: String,
    val isReceive: Boolean,
    val tags: List<String>,
    val createdAt: Long,
)
