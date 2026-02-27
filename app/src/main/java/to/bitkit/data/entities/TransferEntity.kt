package to.bitkit.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import to.bitkit.models.TransferType

@Serializable
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val type: TransferType,
    val amountSats: Long,
    val channelId: String? = null,
    val fundingTxId: String? = null,
    val lspOrderId: String? = null,
    val isSettled: Boolean = false,
    val createdAt: Long,
    val settledAt: Long? = null,
    val claimableAtHeight: UInt? = null,
)
